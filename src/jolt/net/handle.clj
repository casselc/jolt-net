(ns jolt.net.handle
  "Owned socket handles: opaque, idempotently closed, with diagnostic raw access.

  A plain map with a zero-arity :close fn, which is exactly what jolt's with-open
  invokes (clojure.core/__close dispatches a map through its :close value). A
  defrecord would work too but goes through a different branch of that dispatch;
  a map is the smaller surface.

  Close is compare-and-set guarded. That is not tidiness: descriptors are
  aggressively reused, so a second close(2) on the same number can close an
  unrelated socket that happened to inherit it -- a failure that shows up far
  from its cause, in whatever code owned the new descriptor."
  (:require [jolt.net.ffi :as nffi]
            [jolt.net.target :as t]))

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
  (let [state (atom :open)]
    (merge extra
           {:jolt.net/handle true
            :jolt.net/kind kind
            :jolt.net/raw raw
            :jolt.net/state state
            ;; must be 0-arity and keyed by the unqualified :close for with-open
            :close (fn []
                     (when (compare-and-set! state :open :closed)
                       (nffi/invoke :close raw))
                     nil)})))

(defn handle? [h] (true? (:jolt.net/handle h)))

(defn closed? [h] (= :closed @(:jolt.net/state h)))

(defn close!
  "Close the handle. Returns true if this call performed the close, false if it
  was already closed. Idempotent."
  [h]
  (if (compare-and-set! (:jolt.net/state h) :open :closed)
    (do (nffi/invoke :close (:jolt.net/raw h)) true)
    false))

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
