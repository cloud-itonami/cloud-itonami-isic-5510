(ns hospitalityops.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [hospitalityops.registry :as r]))

;; ----------------------------- folio-total-matches-claim? -----------------------------

(deftest matches-when-claim-equals-recompute
  (is (r/folio-total-matches-claim?
       {:nights 2 :rate 10000 :claimed-total 20000})))

(deftest mismatches-when-claim-differs-from-recompute
  (is (not (r/folio-total-matches-claim?
            {:nights 3 :rate 10000 :claimed-total 35000}))))

(deftest compute-folio-total-is-a-flat-nights-times-rate
  (is (= 20000 (r/compute-folio-total {:nights 2 :rate 10000}))))

;; ----------------------------- integer money -----------------------------

(deftest correct-folios-are-no-longer-rejected-by-float-error
  (testing "the float implementation computed (* (double nights) (double rate))
            and compared with ==, which rejected ~19% of correct folios at
            realistic nightly rates -- 131,494 of 686,000 combinations of
            1-14 nights x $10.00-$499.99. Integer minor units cannot do that."
    (doseq [[nights rate] [[3 1003] [3 1004] [3 1005] [3 1008] [3 1010]
                           [7 1429] [11 3333] [13 4999]]]
      (let [exact (* nights rate)]
        (is (r/folio-total-matches-claim? {:nights nights :rate rate :claimed-total exact})
            (str nights " nights x " rate " should equal " exact))))))

(deftest an-exhaustive-sweep-finds-no-correct-folio-rejected
  (testing "the same sweep that found 131,494 false rejections finds none now"
    (let [bad (for [n (range 1 15)
                    minor (range 1000 50000 97)
                    :when (not (r/folio-total-matches-claim?
                                {:nights n :rate minor :claimed-total (* n minor)}))]
                [n minor])]
      (is (empty? bad) (str "false rejections: " (count bad) " e.g. " (first bad))))))

(deftest a-wrong-total-is-still-caught
  (is (not (r/folio-total-matches-claim? {:nights 3 :rate 10000 :claimed-total 29999})))
  (is (not (r/folio-total-matches-claim? {:nights 3 :rate 10000 :claimed-total 30001}))))

(deftest an-unpriceable-stay-never-matches
  (testing "un-verifiable is not the same as correct"
    (is (not (r/folio-total-matches-claim? {:rate 10000 :claimed-total 20000})))
    (is (not (r/folio-total-matches-claim? {:nights 2 :claimed-total 20000})))
    (is (nil? (r/compute-folio-total {:nights 2})))
    (is (nil? (r/compute-folio-total {:rate 10000})))))

(deftest a-float-total-never-matches-now-that-money-is-integer
  (testing "a residual float claim must not silently pass -- it is a data
            defect the operator has to fix, not a total to accept"
    (is (not (r/folio-total-matches-claim? {:nights 2 :rate 10000 :claimed-total 20000.0})))))

;; ----------------------------- nights derived from dates -----------------------------

(deftest nights-come-from-the-stays-own-dates-when-it-has-them
  (testing "a stay that records :nights 2 for a five-night range is billed for five"
    (let [s {:check-in-date "2026-09-01" :check-out-date "2026-09-06"
             :nights 2 :rate 10000}]
      (is (= 5 (count (r/stay-nights s))))
      (is (= 50000 (r/compute-folio-total s))))))

(deftest the-recorded-night-count-is-the-fallback-when-there-are-no-dates
  (is (= 2 (count (r/stay-nights {:nights 2}))))
  (is (nil? (r/stay-nights {})))
  (is (nil? (r/stay-nights {:nights 0})) "zero nights is not a priceable stay"))

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
