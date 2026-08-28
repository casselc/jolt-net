(ns jolt.net.test-main
  "Single entry point for the jolt-net suite: `jolt -M:test`.

  Every namespace's checks roll into jolt.net.check's counters, and the process
  exits non-zero if any failed. The explicit System/exit matters -- a suite that
  starts sockets or futures can leave non-daemon threads alive, which would hang
  the process after the last test printed PASS."
  (:require [jolt.net.check :as c]
            [jolt.net.target :as target]
            [jolt.net.target-test :as target-test]
            [jolt.net.socket-test :as socket-test]
            [jolt.net.poller-test :as poller-test]
            [jolt.net.resolver-test :as resolver-test]
            [jolt.net.address-test :as address-test]
            [clojure.test :as ct]))

(defn -main [& _]
  (println "jolt-net test suite")
  (println (str "target: " (target/current-target)))
  (println (str "nano-time: " (System/nanoTime)))

  (c/section "scaffold")
  ;; The scaffold's own gate proves the released runtime exposes the primitives
  ;; this socket layer actually consumes. Native-error capture is a defcfn-time
  ;; option rather than a queryable capability, so an older runtime fails while
  ;; loading jolt.net.ffi before this runner can begin.
  (c/check-pred "current target resolves an os"
                #(contains? #{:linux :darwin :windows} %)
                (:os (target/current-target)))
  (c/check-pred "current target reports pointer width"
                #(or (= 32 %) (= 64 %))
                (:pointer-bits (target/current-target)))
  (c/check-pred "released System/nanoTime is monotonic-shaped"
                integer? (System/nanoTime))
  (c/check-pred "released 16-bit foreign types exist"
                #(= 2 %) (jolt.ffi/sizeof :uint16))

  (target-test/run!)
  (address-test/run!)
  (resolver-test/run!)
  (socket-test/run!)
  (poller-test/run!)

  ;; Generative properties run under clojure.test (hegel's integration reports
  ;; through it), so fold its counters into the same exit code.
  (c/section "generative properties (jolt-hegel)")
  ;; Loaded at RUNTIME, not in the ns form: the generative engine is a
  ;; downloaded native library, and its absence must cost us the properties
  ;; only -- not the socket coverage, which is the more important half.
  (if-not (try (require 'jolt.net.address-property-test) true
               (catch :default _ false))
    (c/skip "hegel generative properties"
            "libhegel unavailable here; the property engine could not be loaded")
    (let [r (ct/run-tests 'jolt.net.address-property-test)]
    (c/check "hegel address properties report no failures" 0 (+ (:fail r) (:error r)))
    ;; A suite that ran ZERO properties also reports zero failures. Assert the
    ;; properties actually executed, or a require that silently stopped loading
    ;; would read as success.
    (c/check-pred "hegel properties actually ran" pos? (:test r))
    (c/check-pred "hegel properties made assertions" pos? (:pass r))
      (println (str "     " (:test r) " properties, " (:pass r) " assertions passed"))))

  (flush)
  (System/exit (c/summary)))
