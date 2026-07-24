(ns jolt.net.handle
  "Owned socket handles: opaque, idempotently closed, with diagnostic raw access.

  A plain map with a zero-arity :close fn, which is exactly what jolt's with-open
  invokes (clojure.core/__close dispatches a map through its :close value). A
  defrecord would work too but goes through a different branch of that dispatch;
  a map is the smaller surface.

  Close is compare-and-set guarded and coordinated with short operation leases.
  Once close starts no new operation can acquire the raw descriptor; the native
  close is deferred until existing operations release it. Descriptors are
  aggressively reused, so closing while another thread is entering a syscall can
  otherwise target an unrelated socket that inherited the same number."
  (:require [jolt.net.ffi :as nffi]
            [jolt.net.target :as t]))

(def ^:private next-generation (atom 0))
(def ^:private next-listener (atom 0))

(defn raw-close!
  "Close a raw handle with no ownership bookkeeping. For rollback paths only,
  where the handle never became owned. Deliberately ignores the return value:
  this runs while an exception is already in flight, and errno has already been
  captured by then."
  [h]
  (try (nffi/invoke :close h) (catch :default _ nil))
  nil)

(defn own
  "Wrap a raw handle in an owned handle. Ownership transfers only when this
  returns -- a constructor that throws before calling it must roll back itself."
  [raw kind extra]
  (let [state (atom {:phase :open :leases 0 :listeners {} :notified? false})
        nonblocking (atom false)
        generation (swap! next-generation inc)
        close-fn
        (fn []
          (loop []
            (let [old @state]
              (case (:phase old)
                :open
                (let [closing (assoc old :phase :closing)]
                  (if (compare-and-set! state old closing)
                    (do
                      ;; Notify readiness owners before the descriptor can be
                      ;; closed. A listener may wake a blocked poller.
                      (doseq [[_ listener] (:listeners closing)]
                        (try (listener) (catch :default _ nil)))
                      (loop []
                        (let [s @state
                              done? (zero? (:leases s))
                              next (assoc s
                                          :listeners {}
                                          :notified? true
                                          :phase (if done? :closed :closing))]
                          (if (compare-and-set! state s next)
                            (when done? (nffi/invoke :close raw))
                            (recur))))
                      true)
                    (recur)))
                :closing false
                :closed false))))]
    (merge extra
           {:jolt.net/handle true
            :jolt.net/kind kind
            :jolt.net/raw raw
            :jolt.net/generation generation
            :jolt.net/state state
            :jolt.net/nonblocking nonblocking
            ;; must be 0-arity and keyed by the unqualified :close for with-open
            :close (fn [] (close-fn) nil)
            :jolt.net/close-fn close-fn})))

(defn handle? [h] (true? (:jolt.net/handle h)))

(defn closed?
  "True as soon as close begins. Native close may still be waiting for a short
  in-flight operation lease to be released."
  [h]
  (not= :open (:phase @(:jolt.net/state h))))

(defn close!
  "Close the handle. Returns true if this call performed the close, false if it
  was already closed. Idempotent."
  [h]
  ((:jolt.net/close-fn h)))

(defn generation
  "The ownership generation for this handle. It is stable for the handle's
  lifetime and distinguishes a reused descriptor from its former owner."
  [h]
  (:jolt.net/generation h))

(defn nonblocking?
  "Whether this handle's descriptor has already been placed in non-blocking
  mode by jolt.net."
  [h]
  @(:jolt.net/nonblocking h))

(defn mark-nonblocking!
  "Record a successfully applied native non-blocking transition."
  [h]
  (reset! (:jolt.net/nonblocking h) true)
  h)

(defn acquire!
  "Acquire a short operation lease, or throw once close has begun. The returned
  value is internal bookkeeping and must be released in a finally clause."
  [h]
  (loop []
    (let [state-atom (:jolt.net/state h)
          old @state-atom]
      (if-not (= :open (:phase old))
        (throw (ex-info "jolt.net: socket is closed"
                        {:jolt.net/kind :invalid :jolt.net/op :use-after-close}))
        (if (compare-and-set! state-atom old (update old :leases inc))
          {:handle h
           :raw (:jolt.net/raw h)
           :generation (:jolt.net/generation h)}
          (recur))))))

(defn release!
  "Release a short operation lease. Exactly one releaser performs the deferred
  native close when it drops the final lease after close has begun."
  [lease]
  (let [h (:handle lease)
        state-atom (:jolt.net/state h)]
    (loop []
      (let [old @state-atom
            remaining (dec (:leases old))
            finalize? (and (= :closing (:phase old))
                           (:notified? old)
                           (zero? remaining))
            next (assoc old
                        :leases remaining
                        :phase (if finalize? :closed (:phase old)))]
        (if (compare-and-set! state-atom old next)
          (when finalize? (nffi/invoke :close (:jolt.net/raw h)))
          (recur)))))
  nil)

(defn with-lease
  "Run f with [raw generation] while a short operation lease is held."
  [h f]
  (let [lease (acquire! h)]
    (try
      (f (:raw lease) (:generation lease))
      (finally (release! lease)))))

(defn on-close!
  "Register a zero-arity callback invoked after close wins ownership but before
  the raw descriptor can be closed. Returns an opaque listener id, or nil if
  close has already begun."
  [h callback]
  (let [id (swap! next-listener inc)
        state-atom (:jolt.net/state h)]
    (loop []
      (let [old @state-atom]
        (if-not (= :open (:phase old))
          nil
          (if (compare-and-set! state-atom old
                                (assoc-in old [:listeners id] callback))
            id
            (recur)))))))

(defn remove-close-listener!
  "Remove a close callback. Safe and idempotent in every handle phase."
  [h id]
  (let [state-atom (:jolt.net/state h)]
    (loop []
      (let [old @state-atom
            next (update old :listeners dissoc id)]
        (if (= old next)
          false
          (if (compare-and-set! state-atom old next)
            true
            (recur)))))))

(defn raw
  "The underlying descriptor, for diagnostics. Conveys NO ownership: closing it
  yourself breaks the handle's invariant and can close an unrelated socket later."
  [h]
  (:jolt.net/raw h))

(defn raw-open
  "The descriptor, or throw if the handle is closed. Used internally by every
  operation so a use-after-close is a clear error rather than a syscall on a
  recycled descriptor."
  [h]
  (when (closed? h)
    (throw (ex-info "jolt.net: socket is closed"
                    {:jolt.net/kind :invalid :jolt.net/op :use-after-close})))
  (:jolt.net/raw h))
