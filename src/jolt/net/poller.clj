(ns jolt.net.poller
  "POSIX poll(2) readiness with a non-blocking self-pipe waker.

  Registration mutations are values in one acknowledged queue. The caller does
  not return until its mutation has been applied, and every mutation wakes a
  native poll snapshot that may now be stale. Tokens carry both socket ownership
  generation and registration revision; events from an old snapshot are dropped
  unless its complete token is still current."
  (:require [jolt.ffi :as ffi]
            [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.target :as t]))

(def ^:private d nffi/descriptor)
(def ^:private next-poller (atom 0))
(def ^:private max-native-wait-ms 1000)

(declare submit! signal-wake!)

(defn- unsupported! [op]
  (throw (ex-info (str "jolt.net " (name op)
                       ": the poller runtime is POSIX-only in this slice")
                  {:jolt.net/op op
                   :jolt.net/kind :unsupported-target
                   :jolt.net/target (jolt.host/target)})))

(defn- require-posix! [op]
  (when-not (contains? #{:linux :darwin} (:os (jolt.host/target)))
    (unsupported! op)))

(defn- set-nonblocking-raw! [raw]
  (let [flags (err/checked :fcntl-getfl neg?
                           #(nffi/invoke :fcntl raw (t/const d :f-getfl) 0))]
    (err/checked :fcntl-setfl neg?
                 #(nffi/invoke :fcntl raw (t/const d :f-setfl)
                               (bit-or flags (t/const d :o-nonblock)))))
  raw)

(defn- take-mutations! [poller]
  (let [q (:mutations poller)]
    (loop []
      (let [old @q]
        (if (empty? old)
          []
          (if (compare-and-set! q old [])
            old
            (recur)))))))

(defn- invalid-token [op token message]
  (err/invalid-ex op message {:jolt.net/token token}))

(defn- current-registration [poller token op]
  (when-not (= (:jolt.net/poller-id token) (:id poller))
    (throw (invalid-token op token "token belongs to another poller")))
  (let [registration (get @(:registrations poller)
                          (:jolt.net/registration token))]
    (when-not (and registration (= token (:token registration)))
      (throw (invalid-token op token "registration token is stale")))
    registration))

(defn- remove-registration! [poller registration]
  (swap! (:registrations poller) dissoc
         (:jolt.net/registration (:token registration)))
  (h/remove-close-listener! (:socket registration) (:listener registration))
  true)

(defn- apply-mutation! [poller mutation]
  (case (:op mutation)
    :register
    (let [_ (when-not (= :open (:phase @(:lifecycle poller)))
              (throw (err/invalid-ex :register "poller is closed" nil)))
          socket (:socket mutation)
          generation (h/generation socket)
          rid (swap! (:next-registration poller) inc)
          token {:jolt.net/poller-id (:id poller)
                 :jolt.net/registration rid
                 :jolt.net/generation generation
                 :jolt.net/revision 0}
          listener
          (h/on-close!
            socket
            (fn []
              ;; Close owns the socket state before invoking this callback. The
              ;; mutation is therefore ordered before native descriptor close.
              (try
                (submit! poller {:op :remove-closed
                                 :registration rid
                                 :generation generation})
                (catch :default _ nil))))]
      (when-not listener
        (throw (err/invalid-ex :register "socket is already closed" nil)))
      (swap! (:registrations poller)
             assoc rid {:socket socket
                        :interests (:interests mutation)
                        :token token
                        :listener listener})
      token)

    :update
    (let [_ (when-not (= :open (:phase @(:lifecycle poller)))
              (throw (err/invalid-ex :update "poller is closed" nil)))
          old (current-registration poller (:token mutation) :update)
          token (update (:token old) :jolt.net/revision inc)
          registration (assoc old
                              :interests (:interests mutation)
                              :token token)]
      (swap! (:registrations poller)
             assoc (:jolt.net/registration token) registration)
      token)

    :remove
    (do
      (when-not (= :open (:phase @(:lifecycle poller)))
        (throw (err/invalid-ex :remove "poller is closed" nil)))
      (remove-registration!
        poller
        (current-registration poller (:token mutation) :remove)))

    :remove-closed
    (let [registration (get @(:registrations poller)
                            (:registration mutation))]
      (if (and registration
               (= (:generation mutation)
                  (:jolt.net/generation (:token registration))))
        (remove-registration! poller registration)
        false))

    :clear
    (do
      (doseq [[_ registration] @(:registrations poller)]
        (h/remove-close-listener! (:socket registration)
                                  (:listener registration)))
      (reset! (:registrations poller) {})
      true)

    (throw (err/invalid-ex :poller-mutation "unknown poller mutation"
                           {:jolt.net/mutation (:op mutation)}))))

(defn- drain-mutations! [poller]
  (when (compare-and-set! (:draining poller) false true)
    (try
      (loop []
        (let [batch (take-mutations! poller)]
          (when (seq batch)
            (doseq [mutation batch]
              (try
                (deliver (:ack mutation)
                         {:ok (apply-mutation! poller mutation)})
                (catch :default e
                  (deliver (:ack mutation) {:error e}))))
            (recur))))
      (finally
        (reset! (:draining poller) false)
        ;; A producer can append after the final empty take but before the gate
        ;; is released. Re-acquire in that case so every caller receives an ack.
        (when (seq @(:mutations poller))
          (drain-mutations! poller))))))

(defn acquire-wake-write!
  "Internal forced-interleaving seam. Admit one short wake write and return its
  handle lease, or nil after close has retired wake writes.

  Admission linearizes on a CAS state shared with close. The write-handle lease
  is acquired while admission is held, so close cannot pass the drain point
  between admission and descriptor use."
  [poller]
  (let [admission (:wake-admission poller)]
    (loop []
      (let [old @admission]
        (if-not (= :open (:phase old))
          nil
          (if (compare-and-set! admission old (update old :writers inc))
            (try
              (h/acquire! (:wake-write poller))
              (catch :default e
                (loop []
                  (let [s @admission]
                    (if (compare-and-set! admission s (update s :writers dec))
                      nil
                      (recur))))
                (throw e)))
            (recur)))))))

(defn release-wake-write!
  "Release an admitted wake write. The handle lease is released BEFORE the
  poller admission count, so close cannot close the pipe read end while a writer
  can still issue write(2)."
  [poller lease]
  (h/release! lease)
  (let [admission (:wake-admission poller)]
    (loop []
      (let [old @admission]
        (if (compare-and-set! admission old (update old :writers dec))
          nil
          (recur))))))

(defn- retire-wake-writes! [poller]
  (let [admission (:wake-admission poller)]
    (loop []
      (let [old @admission]
        (if (= :retired (:phase old))
          nil
          (if (compare-and-set! admission old (assoc old :phase :retired))
            nil
            (recur)))))
    ;; Writers are non-blocking and allocation-bounded. No thread-owned lock is
    ;; involved: any future that releases the last CAS-counted admission makes
    ;; progress even if it shares host thread bookkeeping with this closer.
    (loop []
      (when (pos? (:writers @admission))
        (Thread/yield)
        (recur)))
    ;; Closing write first cannot SIGPIPE an admitted writer: there are none.
    ;; Keep read open until await has released its read lease.
    (h/close! (:wake-write poller)))
  nil)

(defn- wake-call
  "Invoke one non-blocking wake-pipe syscall and capture errno immediately.
  The keyed hook is a deterministic test seam with the same result-map shape."
  [poller hook-key op raw buf len]
  (if-let [hook (get poller hook-key)]
    (hook raw buf len)
    (let [result (nffi/invoke op raw buf len)]
      (if (neg? result)
        (let [code (err/capture)]
          {:result result :code code})
        {:result result}))))

(defn- ensure-wake-byte! [poller]
  (when (compare-and-set! (:wake-pending poller) false true)
    (if-let [lease (acquire-wake-write! poller)]
      (try
        (let [raw (:raw lease)]
          (let [buf (ffi/alloc 1)]
            (try
              (ffi/write buf :uint8 0 1)
              (loop []
                (let [{:keys [result code]}
                      (wake-call poller :jolt.net/wake-write-call
                                 :write raw buf 1)]
                  (when (neg? result)
                    (cond
                      (= code (t/errno-code d :eintr))
                      (recur)

                      (or (= code (t/errno-code d :eagain))
                          (= code (t/errno-code d :ewouldblock)))
                      nil

                      :else
                      (throw (err/native-ex :wake code nil))))))
              (finally (ffi/free buf)))))
        (catch :default e
          ;; Closing the poller can race a redundant wake. Only propagate while
          ;; the poller is still open; close supplies the terminal pipe HUP.
          (when (= :open (:phase @(:lifecycle poller)))
            (reset! (:wake-pending poller) false)
            (throw e)))
        (finally (release-wake-write! poller lease)))
      ;; Close retired admission after the caller observed an open poller.
      (reset! (:wake-pending poller) false)))
  nil)

(defn- signal-wake! [poller]
  ;; The sequence closes the drain/reset coalescing window: if a producer sees
  ;; :wake-pending while the consumer has already drained the byte, the consumer
  ;; observes this increment and restores a byte after clearing the gate.
  (swap! (:wake-sequence poller) inc)
  (ensure-wake-byte! poller))

(defn- submit! [poller mutation]
  (let [ack (promise)
        entry (assoc mutation :ack ack)]
    (swap! (:mutations poller) conj entry)
    (drain-mutations! poller)
    (let [result (deref ack)]
      (if-let [e (:error result)]
        (throw e)
        (do
          ;; Publish state before its wake epoch. A pre-snapshot drain may then
          ;; consume this wake only after the acknowledged mutation is visible.
          (signal-wake! poller)
          (:ok result))))))

(defn- finish-close! [poller]
  (let [lifecycle (:lifecycle poller)]
    (loop []
      (let [old @lifecycle]
        (cond
          (= :closed (:phase old)) false
          (and (= :closing (:phase old)) (not (:awaiting? old)))
          (if (compare-and-set! lifecycle old
                                (assoc old :phase :closed))
            (do
              ;; wake-write was retired and closed before finish-close! can run.
              (h/close! (:wake-write poller))
              (h/close! (:wake-read poller))
              true)
            (recur))
          :else false)))))

(defn close!
  "Close a poller. Registered sockets are borrowed and remain open. A blocked
  await is woken. The winning close does not return until that await exits and
  both pipe descriptors are closed."
  [poller]
  (let [lifecycle (:lifecycle poller)]
    (loop []
      (let [old @lifecycle]
        (case (:phase old)
          :open
          (if (compare-and-set! lifecycle old (assoc old :phase :closing))
            (do
              (submit! poller {:op :clear})
              (signal-wake! poller)
              (retire-wake-writes! poller)
              ;; Closing write makes the retained read end report HUP, so an
              ;; active poll cannot remain parked. Wait for await's finally to
              ;; release the read lease; the winning close is a completion
              ;; boundary, not merely a stop request.
              (loop []
                (when (:awaiting? @lifecycle)
                  (Thread/yield)
                  (recur)))
              (finish-close! poller)
              true)
            (recur))
          :closing false
          :closed false)))))

(defn open
  "Open a POSIX poller with a non-blocking self-pipe waker."
  []
  (require-posix! :open-poller)
  (let [fds (ffi/alloc 8)]
    (try
      (err/checked :pipe neg? #(nffi/invoke :pipe fds))
      (let [read-raw (ffi/read fds :int 0)
            write-raw (ffi/read fds :int 4)]
        (try
          (set-nonblocking-raw! read-raw)
          (set-nonblocking-raw! write-raw)
          (let [read-h (h/own read-raw :poller-wake-read {})
                write-h (h/own write-raw :poller-wake-write {})
                poller
                {:jolt.net/poller true
                 :id (swap! next-poller inc)
                 :lifecycle (atom {:phase :open :awaiting? false})
                 :registrations (atom {})
                 :mutations (atom [])
                 :draining (atom false)
                 :wake-pending (atom false)
                 :wake-sequence (atom 0)
                 :wake-admission (atom {:phase :open :writers 0})
                 :next-registration (atom 0)
                 :wake-read read-h
                 :wake-write write-h}]
            (assoc poller :close (fn [] (close! poller) nil)))
          (catch :default e
            (h/raw-close! read-raw)
            (h/raw-close! write-raw)
            (throw e))))
      (finally (ffi/free fds)))))

(def ^:private valid-interests #{:read :write})

(defn- check-interests! [op interests]
  (when-not (and (set? interests)
                 (every? valid-interests interests))
    (throw (err/invalid-ex op "interests must be a set of :read and/or :write"
                           {:jolt.net/interests interests})))
  interests)

(defn- require-open! [poller op]
  (when-not (= :open (:phase @(:lifecycle poller)))
    (throw (err/invalid-ex op "poller is closed" nil))))

(defn register!
  "Register a borrowed socket and return its generation-bearing token. Returns
  only after the registration is visible to subsequent awaits."
  [poller socket interests]
  (require-open! poller :register)
  (check-interests! :register interests)
  (submit! poller {:op :register :socket socket :interests interests}))

(defn update!
  "Replace a token's interests and return its successor revision. The old token
  becomes stale as soon as this acknowledged call returns."
  [poller token interests]
  (require-open! poller :update)
  (check-interests! :update interests)
  (submit! poller {:op :update :token token :interests interests}))

(defn remove!
  "Remove a current token. Stale or foreign tokens are rejected."
  [poller token]
  (require-open! poller :remove)
  (submit! poller {:op :remove :token token}))

(defn wake!
  "Wake one blocked await. Multiple outstanding wakes are coalesced."
  [poller]
  (require-open! poller :wake)
  (signal-wake! poller))

(defn- interest-mask [interests]
  (bit-or (if (contains? interests :read) (t/const d :pollin) 0)
          (if (contains? interests :write) (t/const d :pollout) 0)))

(defn- event-set [bits]
  (cond-> #{}
    (not (zero? (bit-and bits (t/const d :pollin)))) (conj :read)
    (not (zero? (bit-and bits (t/const d :pollout)))) (conj :write)
    (not (zero? (bit-and bits (t/const d :pollerr)))) (conj :error)
    (not (zero? (bit-and bits (t/const d :pollhup)))) (conj :hangup)
    (not (zero? (bit-and bits (t/const d :pollnval)))) (conj :error)))

(defn- drain-wake-pipe! [poller raw]
  (let [buf (ffi/alloc 64)
        before @(:wake-sequence poller)]
    (try
      (loop []
        (let [{:keys [result code]}
              (wake-call poller :jolt.net/wake-read-call
                         :read raw buf 64)
              n result]
          (cond
            (pos? n) (recur)
            (zero? n) nil
            (= code (t/errno-code d :eintr)) (recur)
            :else
            (when-not (or (= code (t/errno-code d :eagain))
                          (= code (t/errno-code d :ewouldblock)))
              (throw (err/native-ex :wake-read code nil))))))
      (finally
        (reset! (:wake-pending poller) false)
        (ffi/free buf)
        ;; A producer that incremented the epoch while the old byte was being
        ;; drained may have coalesced against the old true gate. Restore a byte
        ;; for that later epoch; a producer after the reset writes it itself.
        (when (not= before @(:wake-sequence poller))
          (ensure-wake-byte! poller))))))

(defn- acquire-snapshot [poller]
  (loop [registrations (vals @(:registrations poller))
         entries []
         leases []]
    (if-let [registration (first registrations)]
      (let [lease (try (h/acquire! (:socket registration))
                       (catch :default _ nil))]
        (if lease
          (recur (next registrations)
                 (conj entries
                       {:raw (:raw lease)
                        :token (:token registration)
                        :interests (:interests registration)})
                 (conj leases lease))
          ;; Socket close won the race. Its close-listener mutation removes the
          ;; registration; this snapshot simply omits it.
          (recur (next registrations) entries leases)))
      {:entries entries :leases leases})))

(defn- enter-await! [poller]
  (let [lifecycle (:lifecycle poller)]
    (loop []
      (let [old @lifecycle]
        (when-not (= :open (:phase old))
          (throw (err/invalid-ex :await-ready "poller is closed" nil)))
        (when (:awaiting? old)
          (throw (err/invalid-ex :await-ready
                                 "only one thread may await a poller" nil)))
        (if (compare-and-set! lifecycle old (assoc old :awaiting? true))
          true
          (recur))))))

(defn- exit-await! [poller]
  (let [lifecycle (:lifecycle poller)]
    (loop []
      (let [old @lifecycle
            next (assoc old :awaiting? false)]
        (if (compare-and-set! lifecycle old next)
          (finish-close! poller)
          (recur))))))

(defn- poll-once
  "Invoke poll and capture errno before any other native call. The optional map
  hook is an internal deterministic EINTR test seam."
  [poller buf n wait-ms]
  (if-let [hook (:jolt.net/poll-call poller)]
    (hook buf n wait-ms)
    (let [result (nffi/invoke :poll buf n wait-ms)]
      (if (neg? result)
        (let [code (err/capture)]
          {:result result :code code})
        {:result result}))))

(defn await-ready
  "Wait up to timeout-ms for readiness and return
  [{:token token :events #{:read ...}} ...].

  The native wait is capped at one second, so even a missed platform wake cannot
  make close unbounded. Error and hangup are reported independently of requested
  interests; readable data is never discarded merely because hangup is also set."
  [poller timeout-ms]
  (require-posix! :await-ready)
  (when-not (and (integer? timeout-ms) (not (neg? timeout-ms)))
    (throw (err/invalid-ex :await-ready
                           "timeout-ms must be a non-negative integer"
                           {:jolt.net/timeout-ms timeout-ms})))
  ;; Linearization boundary for explicit wake: an epoch after this read belongs
  ;; to this await even if it arrives between enter-await! and the pre-snapshot
  ;; drain. Epochs already visible here predate the await and may be consumed.
  (let [entry-wake-sequence @(:wake-sequence poller)]
    (enter-await! poller)
    (let [wake-lease (atom nil)
          socket-leases (atom [])]
      (try
        (drain-mutations! poller)
        (let [wl (h/acquire! (:wake-read poller))
              _ (reset! wake-lease wl)
              ;; A mutation completed before this await may have left a
              ;; coalesced byte in the pipe. Its state is already acknowledged,
              ;; so consume it before taking the native snapshot.
              _ (when @(:wake-pending poller)
                  (drain-wake-pipe! poller (:raw wl)))
              _ (when (not= entry-wake-sequence
                            @(:wake-sequence poller))
                  (ensure-wake-byte! poller))
              snapshot (acquire-snapshot poller)
              _ (reset! socket-leases (:leases snapshot))
              entries (:entries snapshot)
              layout (t/layout d :pollfd)
              size (:size layout)
              n (+ 1 (count entries))
              buf (ffi/alloc (* size n))]
          (try
            (ffi/write buf :int (:fd layout) (:raw wl))
            (ffi/write buf :int16 (:events layout) (t/const d :pollin))
            (ffi/write buf :int16 (:revents layout) 0)
            (doseq [[idx entry] (map-indexed vector entries)]
              (let [p (+ buf (* size (inc idx)))]
                (ffi/write p :int (:fd layout) (:raw entry))
                (ffi/write p :int16 (:events layout)
                           (interest-mask (:interests entry)))
                (ffi/write p :int16 (:revents layout) 0)))
            (let [deadline (+ (jolt.host/monotonic-nanos)
                              (* timeout-ms 1000000))
                  poll-result
                  (loop []
                    (let [remaining (max 0 (- deadline
                                              (jolt.host/monotonic-nanos)))
                          remaining-ms (quot (+ remaining 999999) 1000000)
                          wait-ms (min remaining-ms max-native-wait-ms)
                          outcome (poll-once poller buf n wait-ms)
                          result (:result outcome)]
                      (cond
                        (neg? result)
                        (let [code (:code outcome)]
                          ;; A signal does not consume the caller's timeout.
                          ;; Retry against the same absolute monotonic deadline.
                          (if (= code (t/errno-code d :eintr))
                            (if (< (jolt.host/monotonic-nanos) deadline)
                              (recur)
                              ;; poll may leave revents undefined on failure.
                              ;; A deadline-expired interruption is therefore a
                              ;; timeout sentinel, not an empty native result.
                              nil)
                            (throw (err/native-ex :poll code nil))))

                        ;; The ceiling is a lost-wake safety net, not an early
                        ;; return from the caller's timeout.
                        (and (zero? result)
                             (pos? remaining)
                             (< (jolt.host/monotonic-nanos) deadline))
                        (recur)

                        :else result)))]
              (if (nil? poll-result)
                []
                (do
                  (let [wake-bits (ffi/read buf :int16 (:revents layout))]
                    (when-not (zero? wake-bits)
                      (drain-wake-pipe! poller (:raw wl))))
                  (drain-mutations! poller)
                  (reduce
                    (fn [ready [idx entry]]
                      (let [p (+ buf (* size (inc idx)))
                            bits (ffi/read p :int16 (:revents layout))
                            events (event-set bits)
                            current (get @(:registrations poller)
                                         (:jolt.net/registration
                                           (:token entry)))]
                        (if (and (seq events)
                                 current
                                 (= (:token entry) (:token current)))
                          (conj ready
                                {:token (:token entry) :events events})
                          ready)))
                    []
                    (map-indexed vector entries)))))
            (finally (ffi/free buf))))
        (finally
          (doseq [lease @socket-leases] (h/release! lease))
          (when-let [lease @wake-lease] (h/release! lease))
          (exit-await! poller))))))
