(ns jolt.net.test-main
  "Single entry point for the jolt-net suite: `bin/jnc -M:test`.

  Every namespace's checks roll into jolt.net.check's counters, and the process
  exits non-zero if any failed. The explicit System/exit matters -- a suite that
  starts sockets or futures can leave non-daemon threads alive, which would hang
  the process after the last test printed PASS."
  (:require [jolt.net.check :as c]))

(defn -main [& _]
  (println "jolt-net test suite")
  (println (str "target: " (jolt.host/target)))
  (println (str "errno-source: " (jolt.ffi/errno-source)))
  (println (str "monotonic-source: " (jolt.host/monotonic-source)))

  (c/section "scaffold")
  ;; The scaffold's own gate: prove we are running on the fork, not stock joltc.
  ;; Every later namespace depends on these three, so failing here first gives a
  ;; readable diagnosis instead of an unbound-var deep in a socket call.
  (c/check-pred "jolt.host/target resolves an os"
                #(contains? #{:linux :darwin :windows} %)
                (:os (jolt.host/target)))
  (c/check-pred "jolt.host/target reports pointer width"
                #(or (= 32 %) (= 64 %))
                (:pointer-bits (jolt.host/target)))
  (c/check-pred "fork prerequisite: jolt.ffi/errno is available"
                some? (jolt.ffi/errno-source))
  (c/check-pred "fork prerequisite: a real monotonic clock backs deadlines"
                #(= :monotonic %) (jolt.host/monotonic-source))
  (c/check-pred "fork prerequisite: 16-bit foreign types exist"
                #(= 2 %) (jolt.ffi/sizeof :uint16))

  (flush)
  (System/exit (c/summary)))
