(ns jolt.net.nonblocking
  "The POSIX non-blocking transition shared by sockets and poller wake pipes.

  F_SETFL reporting success is not enough: a mismatched variadic FFI ABI can
  return normally without delivering its third argument. Read the flags back
  before any handle is marked non-blocking, so a substrate mismatch fails
  closed instead of parking a supposedly short operation lease in accept(2)."
  (:require [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.target :as t]))

(def ^:private d nffi/descriptor)

(defn enabled?
  "Whether flags observed through F_GETFL contain this target's O_NONBLOCK bit."
  [flags]
  (not (zero? (bit-and flags (t/const d :o-nonblock)))))

(defn set-raw!
  "Set O_NONBLOCK on raw and verify the kernel-visible postcondition.

  Returns raw for constructor threading. ctx is merged into any structured
  native or fail-closed exception."
  ([raw] (set-raw! raw nil))
  ([raw ctx]
   (let [get-flags #(err/checked :fcntl-getfl neg?
                                  (fn [] (nffi/invoke-captured :fcntl
                                                               raw
                                                               (t/const d :f-getfl)
                                                               0))
                                  ctx)
         before (get-flags)
         desired (bit-or before (t/const d :o-nonblock))]
     (err/checked :fcntl-setfl neg?
                  #(nffi/invoke-captured :fcntl raw (t/const d :f-setfl) desired)
                  ctx)
     (let [observed (get-flags)]
       (when-not (enabled? observed)
         (throw
           (ex-info
             "jolt.net fcntl-setfl: O_NONBLOCK was not observable after F_SETFL"
             (merge {:jolt.net/op :fcntl-setfl
                     :jolt.net/kind :unsupported-target
                     :jolt.net/platform (:platform d)
                     :jolt.net/message
                     "O_NONBLOCK was not observable after F_SETFL"
                     :jolt.net/expected-flag (t/const d :o-nonblock)
                     :jolt.net/observed-flags observed}
                    ctx)))))
     raw)))
