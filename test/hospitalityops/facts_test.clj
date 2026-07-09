(ns hospitalityops.facts-test
  (:require [clojure.test :refer [deftest is]]
            [hospitalityops.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest three-of-four-seeded-jurisdictions-have-a-registration-spec-basis
  ;; UNLIKE quarryops/0810's own full blast-safety sub-citation and
  ;; agronomyops/0162's own full water-buffer sub-citation, this
  ;; vertical has an HONEST single-jurisdiction gap for the US -- see
  ;; hospitalityops.facts's own ns docstring
  (doseq [iso3 ["JPN" "GBR" "DEU"]]
    (is (some? (facts/registration-spec-basis iso3)) (str iso3 " registration-spec-basis"))
    (is (string? (:registration-provenance (facts/registration-spec-basis iso3))) (str iso3 " registration-provenance")))
  (is (nil? (facts/registration-spec-basis "USA")) "honest US gap -- no single controlling federal guest-registration statute"))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest unknown-jurisdiction-has-no-registration-spec-basis
  (is (nil? (facts/registration-spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
