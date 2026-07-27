(ns jolt.net.windows-handle-count
  "Windows process-handle-count oracle for native leak tests.

  Socket handle values are allocator positions, not counts. They can move when
  unrelated handles open or close and therefore cannot establish that a socket
  rollback leaked nothing. GetProcessHandleCount measures the process resource
  directly. Callers still owe a live non-vacuity check showing that the
  measurement observes sockets on the current runtime."
  (:require [jolt.ffi :as ffi]))

(def ^:private windows?
  (= :windows (:os (jolt.host/target))))

;; Keep POSIX test namespace loading dependency-free. The bindings are lazy, but
;; loading kernel32 itself must also remain Windows-only.
(when windows?
  (ffi/load-library "kernel32.dll"))

(ffi/defcfn get-current-process
  "GetCurrentProcess" [] :uptr)

(ffi/defcfn get-process-handle-count-with-error
  "GetProcessHandleCount" [:uptr :pointer] :int
  {:capture-native-error true})

(defn current!
  "Return this process's current Windows handle count, or throw with the
  captured GetLastError code. Windows-only by contract."
  []
  (when-not windows?
    (throw (ex-info "GetProcessHandleCount is available only on Windows"
                    {:jolt.net/kind :unsupported-target
                     :jolt.net/op :process-handle-count
                     :target (jolt.host/target)})))
  (let [out (ffi/alloc (ffi/sizeof :uint))]
    (try
      (ffi/write out :uint 0 0)
      (let [[ok error-code]
            (get-process-handle-count-with-error (get-current-process) out)]
        (when (zero? ok)
          (throw (ex-info "GetProcessHandleCount failed"
                          {:jolt.net/kind :native-error
                           :jolt.net/op :process-handle-count
                           :jolt.net/code error-code})))
        (ffi/read out :uint))
      (finally
        (ffi/free out)))))
