(ns fluree.db.query.index-range-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer :all]
    [fluree.db.test-utils :as test-utils]
    [fluree.db.json-ld.api :as fluree]
    [fluree.db.util.log :as log]
    [fluree.db.flake :as flake]))

(deftest ^:integration index-range-scans
  (testing "Various index range scans using the API."
    (let [conn    (test-utils/create-conn)
          ledger  @(fluree/create conn "query/index-range"
                                  {:context {:ex "http://example.org/ns/"}})
          db      @(fluree/stage
                     ledger
                     [{:id           :ex/brian,
                       :type         :ex/User,
                       :schema/name  "Brian"
                       :schema/email "brian@example.org"
                       :schema/age   50
                       :ex/favNums   7}
                      {:id           :ex/alice,
                       :type         :ex/User,
                       :schema/name  "Alice"
                       :schema/email "alice@example.org"
                       :schema/age   50
                       :ex/favNums   [42, 76, 9]}
                      {:id           :ex/cam,
                       :type         :ex/User,
                       :schema/name  "Cam"
                       :schema/email "cam@example.org"
                       :schema/age   34
                       :ex/favNums   [5, 10]
                       :ex/friend    [:ex/brian :ex/alice]}])
          cam-sid @(fluree/internal-id db :ex/cam)]

      (is (= "http://example.org/ns/cam"
             (fluree/expand-iri db :ex/cam))
          "Expanding compact IRI is broken, likely other tests will fail.")

      (is (int? cam-sid)
          "The compact IRI did not resolve to an integer subject id.")

      (testing "Slice operations"
        (testing "Slice for subject id only"
          (let [alice-sid @(fluree/internal-id db :ex/alice)]
            (is (= (->> @(fluree/slice db :spot [alice-sid])
                        (mapv flake/Flake->parts))
                   [[alice-sid 0 "http://example.org/ns/alice" 1 -1 true nil]
                    [alice-sid 200 1014 0 -1 true nil]
                    [alice-sid 1015 "Alice" 1 -1 true nil]
                    [alice-sid 1016 "alice@example.org" 1 -1 true nil]
                    [alice-sid 1017 50 7 -1 true nil]
                    [alice-sid 1018 9 7 -1 true nil]
                    [alice-sid 1018 42 7 -1 true nil]
                    [alice-sid 1018 76 7 -1 true nil]])
                "Slice should return a vector of flakes for only Alice")))

        (testing "Slice for subject + predicate"
          (let [alice-sid   @(fluree/internal-id db :ex/alice)
                favNums-pid @(fluree/internal-id db :ex/favNums)]
            (is (= (->> @(fluree/slice db :spot [alice-sid favNums-pid])
                        (mapv flake/Flake->parts))
                   [[alice-sid favNums-pid 9 7 -1 true nil]
                    [alice-sid favNums-pid 42 7 -1 true nil]
                    [alice-sid favNums-pid 76 7 -1 true nil]])
                "Slice should only return Alice's favNums (multi-cardinality)")))

        (testing "Slice for subject + predicate + value"
          (let [alice-sid   @(fluree/internal-id db :ex/alice)
                favNums-pid @(fluree/internal-id db :ex/favNums)]
            (is (= (->> @(fluree/slice db :spot [alice-sid favNums-pid 42])
                        (mapv flake/Flake->parts))
                   [[alice-sid favNums-pid 42 7 -1 true nil]])
                "Slice should only return the specified favNum value")))

        (testing "Slice for subject + predicate + value + datatype"
          (let [alice-sid   @(fluree/internal-id db :ex/alice)
                favNums-pid @(fluree/internal-id db :ex/favNums)]
            (is (= (->> @(fluree/slice db :spot [alice-sid favNums-pid [42 7]])
                        (mapv flake/Flake->parts))
                   [[alice-sid favNums-pid 42 7 -1 true nil]])
                "Slice should only return the specified favNum value with matching datatype")))

        (testing "Slice for subject + predicate + value + mismatch datatype"
          (let [alice-sid   @(fluree/internal-id db :ex/alice)
                favNums-pid @(fluree/internal-id db :ex/favNums)]
            (is (= (->> @(fluree/slice db :spot [alice-sid favNums-pid [42 8]])
                        (mapv flake/Flake->parts))
                   [])
                "We specify a different datatype for the value, nothing should be returned")))))))
