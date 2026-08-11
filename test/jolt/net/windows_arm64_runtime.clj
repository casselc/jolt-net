;; Windows/aarch64 architecture agreement, run under source-mode Jolt on the
;; native ARM64 runner immediately before the W1-W4 socket gates.
;;
;; This script opens no socket and is NOT socket evidence. Its whole job is to
;; make the ARM64 claim unforgeable: the gate must fail unless every independent
;; statement of "which architecture is this" agrees. An x86-64 Chez running under
;; Windows-on-ARM emulation would report x86-64 here and stop the job, which is
;; exactly the accident this file exists to prevent -- the runner image ships an
;; x86_64 MinGW gcc and an x64 emulator, so "it ran on an ARM runner" is not by
;; itself evidence that an ARM64 process did the work.
;;
;; Four of the six required agreements are checked here:
;;   - the Jolt runtime architecture, via (jolt.net.target/current-target);
;;   - the jolt-net target selector, via jolt.net.target/supported-target?;
;;   - the committed descriptor's own architecture and evidence label;
;;   - the committed probe file the descriptor was reviewed from.
;; The other two are properties of binaries rather than of this process, so the
;; workflow checks them there: the Chez machine type is read straight from
;; scheme.exe with `(machine-type)` and its PE header, and the compiled ABI
;; probe's PE machine type with dumpbin. Note that (jolt.net.target/current-target) derives
;; :arch FROM (machine-type), so those are NOT two independent witnesses; the
;; independent one is the PE header, which reports what the file actually is
;; rather than what the process says about itself.
(require '[jolt.net.target :as target])

(let [observed (jolt.net.target/current-target)
      tuple [(:os observed) (:arch observed) (:pointer-bits observed)]
      probe (read-string
              (slurp
                (str (System/getenv "JOLT_PWD")
                     "/tools/probed/windows-aarch64.edn")))
      descriptor (target/descriptor observed)
      x86-64 (target/descriptor {:os :windows :arch :x86-64 :pointer-bits 64})]

  (println "jolt.net.target/current-target: " (pr-str observed))

  ;; 1. Source Jolt reports native Windows ARM64. The core derives this from
  ;; Chez's (machine-type) through an exact allowlist, so an emulated ta6nt
  ;; process reports :x86-64 here and stops the job before a single socket call.
  (when-not (= [:windows :aarch64 64] tuple)
    (throw
      (ex-info "source Jolt did not report native Windows ARM64"
               {:target observed})))

  ;; 2. The committed probe agrees. Reading it here also proves the file the
  ;; descriptor review was based on is present in this checkout, so the gate
  ;; cannot pass against a descriptor whose evidence was deleted.
  (when-not (= [:windows :aarch64 64]
               [(:os probe) (:arch probe) (:pointer-bits probe)])
    (throw
      (ex-info "committed probe target disagrees with source Jolt"
               {:target observed :probe probe})))

  ;; 3. The selector now resolves this target instead of failing closed. W7 is
  ;; the promotion, so the previous fail-closed assertion is inverted rather
  ;; than deleted: an unsupported target here would mean the descriptor never
  ;; landed and the socket gates below would be running on nothing.
  (when-not (target/supported-target? observed)
    (throw
      (ex-info "Windows ARM64 is not a supported target; the descriptor is missing"
               {:target observed
                :supported (target/supported-targets)})))

  ;; 4. Provenance. The Windows ARM64 facts were obtained from a native ARM64
  ;; probe, not inferred from the x86-64 column, and the label must say so.
  (when-not (= :probed (:evidence descriptor))
    (throw
      (ex-info "Windows ARM64 descriptor does not record probed evidence"
               {:evidence (:evidence descriptor)})))

  ;; 5. The recorded equality with Windows x86-64 is an observation, and it is
  ;; checked rather than trusted. If a future edit changes one Windows column
  ;; and not the other, this gate fails on the machine that would be hurt by it.
  (when-not (= x86-64 descriptor)
    (throw
      (ex-info "the two Windows descriptors diverged"
               {:aarch64 descriptor :x86-64 x86-64})))

  ;; 6. And the descriptor really does match this machine's own probed headers,
  ;; field by field, not merely at the architecture label.
  (doseq [[k probed] [[:socket-handle-bytes (if (= :uptr (:handle-type descriptor)) 8 4)]
                      [:socklen-bytes (case (:socklen-type descriptor)
                                        (:int :uint) 4 :size_t 8 nil)]
                      [:ioctl-cmd-bytes (:ioctl-cmd-bytes descriptor)]
                      [:ioctl-arg-bytes (:ioctl-arg-bytes descriptor)]]]
    (when-not (= (get probe k) probed)
      (throw
        (ex-info (str "descriptor disagrees with the native ARM64 probe at " k)
                 {:fact k :probe (get probe k) :descriptor probed}))))
  (when-not (= (:const probe) (select-keys (:const descriptor) (keys (:const probe))))
    (throw
      (ex-info "descriptor constants disagree with the native ARM64 probe"
               {:probe (:const probe) :descriptor (:const descriptor)})))
  (when-not (= (get-in probe [:layout :wsapollfd])
               (assoc (get-in descriptor [:layout :wsapollfd])
                      :fd-bytes 8 :events-bytes 2 :revents-bytes 2))
    (throw
      (ex-info "WSAPOLLFD layout disagrees with the native ARM64 probe"
               {:probe (get-in probe [:layout :wsapollfd])
                :descriptor (get-in descriptor [:layout :wsapollfd])})))
  (when-not (= (:wsapoll probe) (:wsapoll descriptor))
    (throw
      (ex-info "WSAPoll signature widths disagree with the native ARM64 probe"
               {:probe (:wsapoll probe) :descriptor (:wsapoll descriptor)})))

  ;; One real Windows ARM64/x86-64 difference exists in the TARGET map, and it is
  ;; recorded rather than smoothed over: the core's machine-name allowlist maps
  ;; tarm64nt to :abi :unknown where ta6nt maps to :abi :win64. jolt-net reads
  ;; only :os, :arch, and :pointer-bits, and :abi is consumed nowhere outside the
  ;; core's own AOT cache key, so this difference is informational. Asserting it
  ;; here means that if :abi ever becomes load-bearing for socket ABI selection,
  ;; this gate fails on ARM64 rather than quietly selecting an :unknown ABI.
  (when-not (= :unknown (:abi observed))
    (throw
      (ex-info "tarm64nt no longer reports :abi :unknown; re-review whether jolt-net may read :abi"
               {:abi (:abi observed) :target observed})))

  (println "PASS native Windows ARM64: jolt.net.target/current-target" (pr-str tuple)
           "descriptor :probed and equal to windows/x86-64")
  (flush))
