(ns hospitalityops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [hospitalityops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/stay s "stay-1"))))
      (is (= 200.0 (:claimed-total (store/stay s "stay-1"))))
      (is (true? (:guest-id-verified? (store/stay s "stay-1"))))
      (is (false? (:disclosure-requested? (store/stay s "stay-1"))))
      (is (= 350.0 (:claimed-total (store/stay s "stay-3"))))
      (is (false? (:guest-id-verified? (store/stay s "stay-4"))))
      (is (true? (:disclosure-requested? (store/stay s "stay-5"))))
      (is (false? (:disclosure-authorized? (store/stay s "stay-5"))))
      (is (true? (:disclosure-authorized? (store/stay s "stay-6"))))
      (is (false? (:checked-in? (store/stay s "stay-1"))))
      (is (false? (:checked-out? (store/stay s "stay-1"))))
      (is (= ["stay-1" "stay-2" "stay-3" "stay-4" "stay-5" "stay-6"]
             (mapv :id (store/all-stays s))))
      (is (nil? (store/assessment-of s "stay-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/check-in-history s)))
      (is (= [] (store/check-out-history s)))
      (is (zero? (store/next-check-in-sequence s "JPN")))
      (is (zero? (store/next-check-out-sequence s "JPN")))
      (is (false? (store/stay-already-checked-in? s "stay-1")))
      (is (false? (store/stay-already-checked-out? s "stay-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :stay/upsert
                                 :value {:id "stay-1" :property "Kita Inn"}})
        (is (= "Kita Inn" (:property (store/stay s "stay-1"))))
        (is (= 200.0 (:claimed-total (store/stay s "stay-1"))) "unrelated field preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["stay-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "stay-1"))))
      (testing "check-in drafts a record and advances the check-in sequence"
        (store/commit-record! s {:effect :stay/mark-checked-in :path ["stay-1"]})
        (is (= "JPN-CHI-000000" (get (first (store/check-in-history s)) "record_id")))
        (is (= "check-in-draft" (get (first (store/check-in-history s)) "kind")))
        (is (true? (:checked-in? (store/stay s "stay-1"))))
        (is (= 1 (count (store/check-in-history s))))
        (is (= 1 (store/next-check-in-sequence s "JPN")))
        (is (true? (store/stay-already-checked-in? s "stay-1"))))
      (testing "check-out drafts a record and advances the check-out sequence"
        (store/commit-record! s {:effect :stay/mark-checked-out :path ["stay-1"]})
        (is (= "JPN-CHO-000000" (get (first (store/check-out-history s)) "record_id")))
        (is (= "check-out-draft" (get (first (store/check-out-history s)) "kind")))
        (is (true? (:checked-out? (store/stay s "stay-1"))))
        (is (= 1 (count (store/check-out-history s))))
        (is (= 1 (store/next-check-out-sequence s "JPN")))
        (is (true? (store/stay-already-checked-out? s "stay-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/stay s "nope")))
    (is (= [] (store/all-stays s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/check-in-history s)))
    (is (= [] (store/check-out-history s)))
    (is (zero? (store/next-check-in-sequence s "JPN")))
    (is (zero? (store/next-check-out-sequence s "JPN")))
    (store/with-stays s {"x" {:id "x" :property "p" :room "1"
                              :nights 1 :rate 1.0 :claimed-total 1.0
                              :guest-id-verified? true
                              :disclosure-requested? false :disclosure-authorized? false
                              :checked-in? false :checked-out? false
                              :jurisdiction "JPN" :status :intake}})
    (is (= "p" (:property (store/stay s "x"))))))
