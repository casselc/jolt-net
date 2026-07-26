(ns jolt.net.wake-cursor-test
  "Forced-interleaving evidence for the publication/drain/arm ordering.

  The defect these checks pin down is a CONTRACT hole, not a timing wobble, so
  nothing here measures elapsed time. The observable is the state of the wake
  transport at the instant the native wait is entered: `:wake-pending` true
  means a byte is already sitting in the receiver, and poll(2)/WSAPoll must
  therefore return at once no matter what timeout it was handed. `:wake-pending`
  false with an unobserved publication outstanding is a real park, and on the
  witnessed workload it costs the full one-second native safety tick.

  Both the clock and the native call are hooks, so each case fixes ONE
  interleaving exactly and re-runs it identically on every host. A test that
  asserted \"the await returned quickly\" would pass on a fast machine with the
  bug still present; this one cannot.

  The ordering under test:

    producer:  publish state          then  wake!  (advances the cursor)
    consumer:  sample the cursor      then  read that state   then  await

  Sampling the cursor BEFORE the read is what makes the two halves compose. A
  wake at or below the sampled cursor was published before the consumer's read
  and so was necessarily observed by it -- parking is correct. A wake above it
  landed after the read and may have been missed -- parking is a lost wake."
  (:require [jolt.net :as net]
            [jolt.net.check :as c]))

(defn- probe
  "Instrument `base` so no real clock and no real native wait are used.

  `steps` maps a 1-based native-entry number to a thunk run at that entry, which
  is how a publication is placed INSIDE an already-armed wait. An entry named in
  `steps` leaves the deadline in the future so the wait loops once more; any
  other entry crosses the absolute deadline, so the await returns and the
  recorded entries are the complete history of that call.

  Every entry records `:armed?` -- the wake transport's own gate, read at the
  moment of native entry. That is the park predicate."
  [base steps]
  (let [entries (atom [])
        now (atom 0)
        calls (atom 0)]
    [(assoc base
            :jolt.net/monotonic-nanos #(deref now)
            :jolt.net/poll-call
            (fn [_ _ wait-ms]
              (let [n (swap! calls inc)]
                (swap! entries conj {:call n
                                     :wait-ms wait-ms
                                     :armed? @(:wake-pending base)})
                (if-let [step (get steps n)]
                  (do (step) {:result 0})
                  ;; Cross the caller's absolute deadline from inside the native
                  ;; call, so this entry terminates the wait without any sleep.
                  (do (reset! now 1000000000000) {:result 0})))))
     entries]))

(defn- armed-history [entries]
  (mapv :armed? @entries))

(defn- run-checks! []
  (c/section "wake cursor: publication/drain/arm ordering")

  ;; --- 1. the forced race -----------------------------------------------
  ;; The witnessed interleaving, reproduced exactly: the consumer samples its
  ;; cursor, drains its own publication queue (empty here -- the producer has
  ;; not published yet), and only THEN does the producer publish and wake. The
  ;; wake is already visible when await-ready begins, so the entry-relative rule
  ;; classifies it as stale. It is not stale: it describes state the consumer's
  ;; drain could not have seen.
  (let [base (net/open-poller)
        [p entries] (probe base {})]
    (try
      (let [cursor (net/wake-cursor p)]
        ;; The consumer's read of producer-owned state happens here.
        (net/wake! p)
        (c/check "forced race: the await returns its empty readiness set"
                 [] (net/await-ready p 1000 cursor)))
      (c/check "forced race: the wait was entered exactly once"
               1 (count @entries))
      (c/check "forced race: a post-read publication arms the wait, so no native park occurs"
               [true] (armed-history entries))
      (finally (net/close! p))))

  ;; --- 2. the same interleaving without a cursor -------------------------
  ;; The regression witness. This is the OLD contract -- the boundary is
  ;; await-ready's own entry -- and under it the identical publication is
  ;; consumed as stale and the wait parks. Keeping this check green is what
  ;; makes case 1 meaningful: it proves the interleaving really does lose the
  ;; wake when the cursor is not carried, so case 1 cannot be passing
  ;; vacuously.
  (let [base (net/open-poller)
        [p entries] (probe base {})]
    (try
      (net/wake! p)
      (c/check "no cursor: the await still returns an empty readiness set"
               [] (net/await-ready p 1000))
      (c/check "no cursor: an entry-relative boundary discards the publication and parks"
               [false] (armed-history entries))
      (finally (net/close! p))))

  ;; --- 3. control: publication BEFORE the consumer's cursor ---------------
  ;; The complementary half. Here the producer published first, so the
  ;; consumer's own read was guaranteed to observe that state and there is
  ;; nothing left to wake for. Parking is CORRECT, and the cursor must not
  ;; prevent it. Without this control the fix could be "always arm", which would
  ;; turn every await into a spin.
  (let [base (net/open-poller)
        [p entries] (probe base {})]
    (try
      (net/wake! p)
      (let [cursor (net/wake-cursor p)]
        (c/check "pre-cursor publication: the await returns empty"
                 [] (net/await-ready p 1000 cursor)))
      (c/check "pre-cursor publication: an already-observed wake is consumed and the wait parks"
               [false] (armed-history entries))
      (finally (net/close! p))))

  ;; --- 4. control: publication AFTER the wait is armed -------------------
  ;; The case the original wake-epoch handshake already covered, re-checked
  ;; through the cursor path so the fix cannot regress it. The producer lands
  ;; while the consumer is inside the native call; the next entry must be armed.
  (let [base (net/open-poller)
        holder (atom nil)
        [p entries] (probe base {1 (fn [] (net/wake! @holder))})]
    (reset! holder p)
    (try
      (let [cursor (net/wake-cursor p)]
        (c/check "in-wait publication: the await returns empty"
                 [] (net/await-ready p 1000 cursor)))
      (c/check "in-wait publication: the first entry parks and the re-entry is armed"
               [false true] (armed-history entries))
      (finally (net/close! p))))

  ;; --- 5. the cursor is monotonic and names real publications ------------
  (let [p (net/open-poller)]
    (try
      (let [a (net/wake-cursor p)
            _ (net/wake! p)
            b (net/wake-cursor p)
            _ (net/wake! p)
            d (net/wake-cursor p)]
        (c/check-pred "each publication advances the cursor"
                      (fn [[a b d]] (and (< a b) (< b d)))
                      [a b d])
        (c/check-pred "the cursor does not move without a publication"
                      #(= d %) (net/wake-cursor p)))
      (finally (net/close! p))))

  ;; --- 6. a cursor from the future is refused ----------------------------
  ;; The sequence only grows, so a cursor above it cannot have come from this
  ;; poller. Honouring it would mark undelivered wakes as stale -- precisely the
  ;; park this argument exists to prevent -- so it fails closed rather than
  ;; silently degrading to the old behaviour.
  (let [p (net/open-poller)]
    (try
      (c/check-throws "a cursor ahead of the wake sequence is refused"
                      {:jolt.net/kind :invalid}
                      #(net/await-ready p 1000 (+ 1000 (net/wake-cursor p))))
      (c/check-throws "a non-integer cursor is refused"
                      {:jolt.net/kind :invalid}
                      #(net/await-ready p 1000 :not-a-cursor))
      (finally (net/close! p)))))

(defn run! []
  (if (contains? #{:linux :darwin :windows} (:os (jolt.host/target)))
    (run-checks!)
    (do
      (c/section "wake cursor: publication/drain/arm ordering")
      (c/skip "wake cursor ordering checks"
              "this target has no readiness runtime"))))
