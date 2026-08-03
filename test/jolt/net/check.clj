(ns jolt.net.check
  "Shared assertion harness for the jolt-net suite.

  Deliberately framework-less, matching jolt-tcp and jolt-http: a `check` that
  counts into one atom, so every namespace's results roll up into a single exit
  code. clojure.test is used only where jolt-hegel's integration wants it.

  A SKIP is not a pass and not a failure. Some behavior (IPv6 loopback) is
  genuinely unavailable in some containers, and silently counting that as a pass
  would be the worst outcome -- it would read as coverage we do not have."
  (:require [clojure.string :as str]))

(def failures (atom 0))
(def passes (atom 0))
(def skips (atom 0))

(defn check
  "Assert equality. Returns true when it passed, so callers can short-circuit."
  [label expected actual]
  (if (= expected actual)
    (do (swap! passes inc) (println "ok   " label) true)
    (do (swap! failures inc)
        (println "FAIL " label)
        (println "       expected:" (pr-str expected))
        (println "       actual:  " (pr-str actual))
        false)))

(defn check-pred
  "Assert a predicate holds, printing the offending value on failure."
  [label pred actual]
  (if (pred actual)
    (do (swap! passes inc) (println "ok   " label) true)
    (do (swap! failures inc)
        (println "FAIL " label)
        (println "       value:" (pr-str actual))
        false)))

(defn check-throws
  "Assert `thunk` throws, and that its ex-data matches every key/value in
  `expected-data`. Checking the DATA, not the message, is the point: the error
  contract is the map, and a test that matches on message text would pass while
  the structured data silently regressed."
  [label expected-data thunk]
  (let [r (try {:value (thunk)}
               (catch :default e {:ex e}))]
    (if-let [e (:ex r)]
      (let [d (ex-data e)
            bad (remove (fn [[k v]] (= v (get d k))) expected-data)]
        (if (empty? bad)
          (do (swap! passes inc) (println "ok   " label) true)
          (do (swap! failures inc)
              (println "FAIL " label "(threw, but ex-data did not match)")
              (println "       wanted: " (pr-str expected-data))
              (println "       ex-data:" (pr-str d))
              false)))
      (do (swap! failures inc)
          (println "FAIL " label "(did not throw)")
          (println "       returned:" (pr-str (:value r)))
          false))))

(defn skip
  "Record that a check could not run here. Never counted as a pass."
  [label why]
  (swap! skips inc)
  (println "SKIP " label "--" why))

(defn section [title]
  (println)
  (println (str "-- " title " " (apply str (repeat (max 0 (- 68 (count title))) \-)))))

(defn monotonic-clock-facts!
  "Check the public upstream clock contract used by every deadline in jolt-net.

  The API intentionally exposes nanoseconds from an arbitrary origin, not an
  implementation/source identifier.  Keep these checks behavioral so a valid
  upstream clock implementation can change without forcing jolt-net to know its
  private name."
  []
  (let [a (jolt.host/mono-nanos)
        b (jolt.host/mono-nanos)]
    (check-pred "jolt.host/mono-nanos returns an exact integer" integer? a)
    (check-pred "jolt.host/mono-nanos is nondecreasing" #(>= % a) b)
    ;; This is the same discriminator used by Jolt's own telemetry gate.  It
    ;; rules out the former currentTimeMillis*1e6 implementation while avoiding
    ;; any assertion about Chez's private clock-source identity.
    (check-pred
     "jolt.host/mono-nanos is not millisecond-truncated"
     identity
     (some (fn [_]
             (pos? (rem (jolt.host/mono-nanos) 1000000)))
           (range 200)))))

(defn summary
  "Print the roll-up. Returns the process exit code."
  []
  (println)
  (println (apply str (repeat 72 \=)))
  (println (str "passed " @passes "   failed " @failures "   skipped " @skips))
  (when (pos? @skips)
    (println "NOTE: skipped checks are unproven behavior, not passing behavior."))
  (if (pos? @failures)
    (do (println "RESULT: FAILED") 1)
    (do (println "RESULT: ALL PASS") 0)))
