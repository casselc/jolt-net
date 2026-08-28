(ns jolt.net.handle-trace-property-test
  "Generation-scoped semantic traces for the owned-handle lifecycle.

  Capture is test-side so this suite still runs on released Jolt v0.7.28.  The
  event maps deliberately use the compiler-aspect journal vocabulary; a woven
  capture provider can replace `around!` without changing the Hegel rules."
  (:require [clojure.test :refer [deftest is testing]]
            [hegel.clojure-test :as ht]
            [hegel.generator :as g]
            [hegel.trace :as trace]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as handle]))

(def ^:dynamic *operation-id* nil)

(defn- journal []
  {:state (atom {:next-seq 0 :next-operation 0 :events []})})

(defn- append!
  [j event]
  (swap! (:state j)
         (fn [{:keys [next-seq] :as state}]
           (let [sequence (inc next-seq)]
             (-> state
                 (assoc :next-seq sequence)
                 (update :events conj (assoc event :seq sequence))))))
  nil)

(defn- next-operation! [j]
  (:next-operation
   (swap! (:state j) update :next-operation inc)))

(defn- around!
  ([j resource role f]
   (around! j resource role {} f))
  ([j resource role attributes f]
   (let [operation-id (next-operation! j)
         context (merge
                  {:operation-id operation-id
                   :parent-operation-id *operation-id*
                   :aspect :jolt.net.test/owned-handle-lifecycle
                   :library 'io.github.casselc/jolt-net
                   :resource-id (:jolt.net/generation resource)
                   :fd (:jolt.net/raw resource)
                   :role role}
                  attributes)]
     (append! j (assoc context :phase :enter))
     (binding [*operation-id* operation-id]
       (try
         (let [result (f)]
           (append! j (cond-> (assoc context :phase :return)
                        (boolean? result) (assoc :result result)))
           result)
         (catch :default error
           (append! j (assoc context :phase :throw
                                    :exception-kind
                                    (:jolt.net/kind (ex-data error))
                                    :exception-reason
                                    (:jolt.net/reason (ex-data error))))
           (throw error)))))))

(defn- invalidate [state]
  (assoc state :invalid? true))

(defn- lifecycle-step
  [state {:keys [lease-id operation-id phase result role]}]
  (if (:invalid? state)
    state
    (case [role phase]
      [:jolt.net.handle/acquire :return]
      (if (= :open (:phase state))
        (update state :leases conj lease-id)
        (invalidate state))

      [:jolt.net.handle/acquire :throw]
      (if (not= :open (:phase state)) state (invalidate state))

      [:jolt.net.handle/release :enter]
      (let [expected (if (contains? (:leases state) lease-id)
                       :return
                       :throw)]
        (cond-> (assoc-in state [:release-results operation-id] expected)
          (= :return expected) (update :leases disj lease-id)))

      [:jolt.net.handle/release :return]
      (if (= :return (get-in state [:release-results operation-id]))
        (update state :release-results dissoc operation-id)
        (invalidate state))

      [:jolt.net.handle/release :throw]
      (if (= :throw (get-in state [:release-results operation-id]))
        (update state :release-results dissoc operation-id)
        (invalidate state))

      [:jolt.net.handle/close :enter]
      (let [winner? (= :open (:phase state))]
        (-> state
            (assoc-in [:close-results operation-id] winner?)
            (assoc :phase (if winner? :closing (:phase state)))))

      [:jolt.net.handle/close :return]
      (let [expected (get-in state [:close-results operation-id] ::missing)]
        (if (= expected result)
          (update state :close-results dissoc operation-id)
          (invalidate state)))

      [:jolt.net.ffi/close :enter]
      (if (and (= :closing (:phase state))
               (empty? (:leases state))
               (zero? (:native-closes state)))
        (-> state
            (assoc :phase :closed)
            (update :native-closes inc))
        (invalidate state))

      state)))

(def handle-linearity
  (trace/event-model
   :owned-handle-is-linear
   {:scope :resource-id
    :initial {:phase :open
              :leases #{}
              :native-closes 0
              :close-results {}
              :release-results {}}
    :step lifecycle-step
    :invariant (fn [state _event]
                 (and (not (:invalid? state))
                      (<= (:native-closes state) 1)))
    :final (fn [state]
             (and (= :closed (:phase state))
                  (empty? (:leases state))
                  (= 1 (:native-closes state))
                  (empty? (:close-results state))
                  (empty? (:release-results state))))}))

(defn- check-trace! [events]
  (trace/check!
   events
   [(trace/contiguous-sequence :handle-journal-not-truncated)
    (trace/closed-lifecycles :handle-operations-terminate)
    (trace/synchronous-parentage :native-close-is-nested)
    handle-linearity]
   {:max-events 128}))

(defn- run-script!
  [actions]
  (let [j (journal)
        resource-holder (atom nil)
        leases (atom [])
        released-leases (atom [])
        next-lease (atom 0)]
    (with-redefs
      [nffi/invoke
       (fn [op & _args]
         (if (= :close op)
           (around! j @resource-holder :jolt.net.ffi/close (fn [] 0))
           (throw (ex-info "unexpected native operation in handle trace test"
                           {:operation op}))))]
      (let [resource (handle/own 4242 :test {})]
        (reset! resource-holder resource)
        (doseq [action actions]
          (case action
            :acquire
            (let [lease-id (swap! next-lease inc)]
              (try
                (swap! leases conj
                       {:lease-id lease-id
                        :lease
                        (around! j resource :jolt.net.handle/acquire
                                 {:lease-id lease-id}
                                 #(handle/acquire! resource))})
                (catch :default error
                  (when-not (= :invalid (:jolt.net/kind (ex-data error)))
                    (throw error)))))

            :release
            (when-let [{:keys [lease-id lease] :as held} (peek @leases)]
              (swap! leases pop)
              (around! j resource :jolt.net.handle/release
                       {:lease-id lease-id}
                       #(handle/release! lease))
              (swap! released-leases conj held))

            :double-release
            (when-let [{:keys [lease-id lease]} (peek @released-leases)]
              (try
                (around! j resource :jolt.net.handle/release
                         {:lease-id lease-id}
                         #(handle/release! lease))
                (throw (ex-info "double release unexpectedly returned"
                                {:hegel/origin
                                 "jolt-net-handle-trace/double-release"}))
                (catch :default error
                  (when-not (= :already-released
                               (:jolt.net/reason (ex-data error)))
                    (throw error)))))

            :close
            (around! j resource :jolt.net.handle/close
                     #(handle/close! resource))))
        ;; Every generated prefix is completed to a quiescent snapshot. This
        ;; makes shrinking independent of previous cases and checks deferred
        ;; native close after an arbitrary number of admitted leases.
        (while (seq @leases)
          (let [{:keys [lease-id lease]} (peek @leases)]
            (swap! leases pop)
            (around! j resource :jolt.net.handle/release
                     {:lease-id lease-id}
                     #(handle/release! lease))))
        (around! j resource :jolt.net.handle/close
                 #(handle/close! resource))))
    (:events @(:state j))))

(deftest generated-owned-handle-traces-are-linear
  (ht/with {:test-cases 100
            :seed 20260828
            :database ""
            :verbosity :quiet}
    [actions (g/vector {:max-size 24}
                       (g/sampled-from
                        [:acquire :release :double-release :close]))]
    (let [events (run-script! actions)]
      (is (= events (check-trace! events))))))

(deftest duplicate-lease-release-is-a-modeled-terminal-error
  (let [events (run-script! [:acquire :release :double-release])
        duplicate (filter #(and (= :jolt.net.handle/release (:role %))
                                (= :already-released
                                   (:exception-reason %)))
                          events)]
    (is (= 1 (count duplicate)))
    (is (= :throw (:phase (first duplicate))))
    (is (= events (check-trace! events)))))

(deftest rule-rejects-native-close-with-a-live-lease
  (testing "the semantic model catches the descriptor-reuse hazard"
    (let [events [{:seq 1 :operation-id 1 :parent-operation-id nil
                   :resource-id 7 :role :jolt.net.handle/acquire :phase :enter}
                  {:seq 2 :operation-id 1 :parent-operation-id nil
                   :resource-id 7 :role :jolt.net.handle/acquire :phase :return}
                  {:seq 3 :operation-id 2 :parent-operation-id nil
                   :resource-id 7 :role :jolt.net.handle/close :phase :enter}
                  {:seq 4 :operation-id 3 :parent-operation-id 2
                   :resource-id 7 :role :jolt.net.ffi/close :phase :enter}
                  {:seq 5 :operation-id 3 :parent-operation-id 2
                   :resource-id 7 :role :jolt.net.ffi/close :phase :return}
                  {:seq 6 :operation-id 2 :parent-operation-id nil
                   :resource-id 7 :role :jolt.net.handle/close :phase :return
                   :result true}]
          error (try (check-trace! events) nil
                     (catch :default failure failure))]
      (is (= "hegel.trace/owned-handle-is-linear"
             (:hegel/origin (ex-data error)))))))
