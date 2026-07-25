(require '[jolt.net.target :as target])

(let [observed (jolt.host/target)
      probe (read-string
              (slurp
                (str (System/getenv "JOLT_PWD")
                     "/tools/probed/windows-aarch64.edn")))
      error (try
              (target/descriptor observed)
              nil
              (catch Throwable cause cause))]
  (when-not (= [:windows :aarch64 64]
               [(:os observed) (:arch observed) (:pointer-bits observed)])
    (throw
      (ex-info "source Jolt did not report native Windows ARM64"
               {:target observed})))
  (when-not (= [:windows :aarch64 64]
               [(:os probe) (:arch probe) (:pointer-bits probe)])
    (throw
      (ex-info "native probe target disagrees with source Jolt"
               {:target observed :probe probe})))
  (when (target/supported-target? observed)
    (throw
      (ex-info "Windows ARM64 descriptor appeared without reviewed evidence"
               {:target observed})))
  (when-not (= :unsupported-target
               (:jolt.net/kind (ex-data error)))
    (throw
      (ex-info "unreviewed Windows ARM64 descriptor did not fail closed"
               {:target observed :error error})))
  (println "PASS source-mode Windows ARM64 target and fail-closed descriptor")
  (flush))
