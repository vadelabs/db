(ns fluree.db.query.reverse-query-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer :all]
    [fluree.db.test-utils :as test-utils]
    [fluree.db.json-ld.api :as fluree]
    [fluree.db.util.log :as log]))

(deftest ^:integration context-reverse-test
  (testing "Test that the @reverse context values pulls select values back correctly."
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "query/reverse" {:context {:ex "http://example.org/ns/"}})
          db     @(fluree/stage
                    ledger
                    [{:id           :ex/brian,
                      :type         :ex/User,
                      :schema/name  "Brian"
                      :ex/friend    [:ex/alice]}
                     {:id           :ex/alice,
                      :type         :ex/User,
                      :schema/name  "Alice"}
                     {:id           :ex/cam,
                      :type         :ex/User,
                      :schema/name  "Cam"
                      :ex/friend    [:ex/brian :ex/alice]}])]

      (is (= @(fluree/query db {:context   {:friended {:reverse :ex/friend}}
                                :selectOne [:schema/name :friended]
                                :from      :ex/brian})
             {:schema/name "Brian"
              :friended    :ex/cam}))

      (is (= @(fluree/query db {:context   {:friended {:reverse :ex/friend}}
                                :selectOne [:schema/name :friended]
                                :from      :ex/alice})
             {:schema/name "Alice"
              :friended    [:ex/cam :ex/brian]}))


      (is (= @(fluree/query db {:context   {:friended {:reverse :ex/friend}}
                                :selectOne [:schema/name {:friended [:*]}]
                                :from      :ex/brian})
             {:schema/name "Brian",
              :friended    {:id           :ex/cam,
                            :rdf/type     [:ex/User],
                            :schema/name  "Cam",
                            :ex/friend    [{:id :ex/brian} {:id :ex/alice}]}})))))
