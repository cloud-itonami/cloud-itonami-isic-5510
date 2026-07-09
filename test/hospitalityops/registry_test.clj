(ns hospitalityops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [hospitalityops.registry :as r]))

;; ----------------------------- folio-total-matches-claim? -----------------------------

(deftest matches-when-claim-equals-recompute
  (is (r/folio-total-matches-claim?
       {:nights 2 :rate 100.0 :claimed-total 200.0})))

(deftest mismatches-when-claim-differs-from-recompute
  (is (not (r/folio-total-matches-claim?
            {:nights 3 :rate 100.0 :claimed-total 350.0}))))

(deftest compute-folio-total-is-a-flat-nights-times-rate
  (is (= 200.0 (r/compute-folio-total {:nights 2 :rate 100.0}))))

;; ----------------------------- register-check-in -----------------------------

(deftest check-in-is-a-draft-not-a-real-check-in
  (let [result (r/register-check-in "stay-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest check-in-assigns-check-in-number
  (let [result (r/register-check-in "stay-1" "JPN" 7)]
    (is (= (get result "check_in_number") "JPN-CHI-000007"))
    (is (= (get-in result ["record" "stay_id"]) "stay-1"))
    (is (= (get-in result ["record" "kind"]) "check-in-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest check-in-validation-rules
  (is (thrown? Exception (r/register-check-in "" "JPN" 0)))
  (is (thrown? Exception (r/register-check-in "stay-1" "" 0)))
  (is (thrown? Exception (r/register-check-in "stay-1" "JPN" -1))))

;; ----------------------------- register-check-out -----------------------------

(deftest check-out-is-a-draft-not-a-real-check-out
  (let [result (r/register-check-out "stay-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest check-out-assigns-check-out-number
  (let [result (r/register-check-out "stay-1" "JPN" 7)]
    (is (= (get result "check_out_number") "JPN-CHO-000007"))
    (is (= (get-in result ["record" "stay_id"]) "stay-1"))
    (is (= (get-in result ["record" "kind"]) "check-out-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest check-out-validation-rules
  (is (thrown? Exception (r/register-check-out "" "JPN" 0)))
  (is (thrown? Exception (r/register-check-out "stay-1" "" 0)))
  (is (thrown? Exception (r/register-check-out "stay-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-check-in "stay-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-check-in "stay-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-CHI-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-CHI-000001" (get-in hist2 [1 "record_id"])))))
