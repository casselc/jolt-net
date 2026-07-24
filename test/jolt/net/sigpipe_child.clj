(ns jolt.net.sigpipe-child
  "Subprocess probe: a write to a closed peer must become data, never SIGPIPE."
  (:require [jolt.net :as net]))

(defn- connected-pair []
  (let [listener (net/listen (net/endpoint "127.0.0.1" 0)
                             {:reuse-address? true})
        port (:jolt.net/port (net/local-endpoint listener))]
    (try
      (let [client (net/connect (net/endpoint "127.0.0.1" port))
            server (net/accept listener)]
        [client server])
      (finally
        (net/close! listener)))))

(defn- await-peer-close! [socket]
  (let [dest (byte-array 1)]
    (loop [attempt 0]
      (let [result (net/try-read-bytes! socket dest 0 1)]
        (cond
          (= net/eof result) true
          (= net/would-block result)
          (if (< attempt 200)
            (do (Thread/sleep 2) (recur (inc attempt)))
            false)
          :else (recur attempt))))))

(defn- closed-peer-write-is-data? []
  (let [[client server] (connected-pair)]
    (try
      (net/shutdown! server :both)
      (net/close! server)
      (and
        (await-peer-close! client)
        (loop [attempt 0]
          (if (= attempt 200)
            false
            (let [outcome
                  (try
                    ;; A graceful FIN can allow one buffered send. Repeat until
                    ;; the peer's reset reaches us; without suppression, that
                    ;; send would terminate this process before catch can run.
                    (net/try-write-bytes! client (byte-array [1]) 0 1)
                    :sent
                    (catch :default e
                      (:jolt.net/kind (ex-data e))))]
              (if (= :sent outcome)
                (do (Thread/sleep 2) (recur (inc attempt)))
                (= :connection-reset outcome))))))
      (finally
        (net/close! server)
        (net/close! client)))))

(defn -main [& _]
  (System/exit (if (closed-peer-write-is-data?) 0 2)))
