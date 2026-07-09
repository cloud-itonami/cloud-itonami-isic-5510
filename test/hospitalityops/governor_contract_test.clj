(ns hospitalityops.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  Trust Controls ('guest disclosures require approval; service records
  cannot be suppressed') implemented faithfully. The single invariant
  under test:

    HospitalityOps-LLM never checks a guest in or out the Hospitality
    Governor would reject, `:stay/check-in`/`:stay/check-out` NEVER
    auto-commit at any phase, `:stay/intake` (no direct guest-facing
    risk) MAY auto-commit when clean, and every decision (commit OR
    hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [hospitalityops.store :as store]
            [hospitalityops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :accommodation-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- check-in!
  "Walks `subject` through check-in -> approve, leaving :checked-in?
  true. Assumes `assess!` already ran for this subject."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-checkin") {:op :stay/check-in :subject subject} operator)
  (approve! actor (str tid-prefix "-checkin")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :stay/intake :subject "stay-1"
                   :patch {:id "stay-1" :property "Kita Inn"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Kita Inn" (:property (store/stay db "stay-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "stay-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "stay-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "stay-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "stay-1")) "no assessment written"))))

(deftest check-in-without-assessment-is-held
  (testing "stay/check-in before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :stay/check-in :subject "stay-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest guest-registration-incomplete-is-held-and-unoverridable
  (testing "an unverified guest registration -> HOLD, and never reaches request-approval -- the FLAGSHIP genuinely new check this vertical adds, the 80th unconditional-evaluation-discipline grounding overall, grounded in Japan's own 旅館業法第6条, the UK's Immigration (Hotel Records) Order 1972 and Germany's Bundesmeldegesetz §29 (an honest single-jurisdiction gap for the US)"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "stay-4")
          res (exec-op actor "t5" {:op :stay/check-in :subject "stay-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:guest-registration-incomplete} (-> (store/ledger db) last :basis)))
      (is (empty? (store/check-in-history db))))))

(deftest folio-total-mismatch-is-held
  (testing "a claimed folio total that doesn't equal nights x rate -> HOLD (the ground-truth-recompute discipline every sibling's cost/total-matching check establishes)"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "stay-3")
          _ (check-in! actor "t6pre" "stay-3")
          res (exec-op actor "t6" {:op :stay/check-out :subject "stay-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:folio-total-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/check-out-history db))))))

(deftest guest-disclosure-authorization-unconfirmed-is-held-and-unoverridable
  (testing "an unauthorized guest-disclosure request on a disclosure-requested stay -> HOLD, and never reaches request-approval -- a genuinely new check, the 81st unconditional-evaluation-discipline grounding overall, the TENTH conditional variant (see this actor's governor ns docstring / the full accumulated ADR-0001 chain: parksafety's ADR-2607071922 Decision 5 through leathergoods's, ictrepair's, retailops's, freightops's, quarryops's and agronomyops's own)"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "stay-5")
          _ (check-in! actor "t7pre" "stay-5")
          res (exec-op actor "t7" {:op :stay/check-out :subject "stay-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:guest-disclosure-authorization-unconfirmed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/check-out-history db))))))

(deftest check-out-is-a-noop-when-no-disclosure-requested
  (testing "the guest-disclosure-authorization check is CONDITIONAL: a stay with no pending disclosure request has no disclosure-authorization requirement at all"
    (let [[_db actor] (fresh)
          _ (assess! actor "t7bpre" "stay-1")
          _ (check-in! actor "t7bpre" "stay-1")
          res (exec-op actor "t7b" {:op :stay/check-out :subject "stay-1"} operator)]
      (is (= :interrupted (:status res)) "clean check-out still escalates for human sign-off, but is NOT a HARD hold"))))

(deftest check-out-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, matching-folio, no-pending-disclosure check-out still ALWAYS interrupts for human approval -- actuation/check-out-guest is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "stay-1")
          _ (check-in! actor "t8pre" "stay-1")
          r1 (exec-op actor "t8" {:op :stay/check-out :subject "stay-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, check-out record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:checked-out? (store/stay db "stay-1"))))
          (is (= 1 (count (store/check-out-history db))) "one draft check-out record"))))))

(deftest check-in-always-escalates-then-human-decides
  (testing "a clean, fully-assessed check-in still ALWAYS interrupts for human approval -- actuation/check-in-guest is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "stay-1")
          r1 (exec-op actor "t9" {:op :stay/check-in :subject "stay-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, check-in record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:checked-in? (store/stay db "stay-1"))))
          (is (= 1 (count (store/check-in-history db))) "one draft check-in record"))))))

(deftest stay-double-check-in-is-held
  (testing "checking in the same stay record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "stay-1")
          _ (check-in! actor "t10pre" "stay-1")
          res (exec-op actor "t10" {:op :stay/check-in :subject "stay-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-checked-in} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/check-in-history db))) "still only the one earlier check-in"))))

(deftest stay-double-check-out-is-held
  (testing "checking out the same stay twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "stay-1")
          _ (check-in! actor "t11pre" "stay-1")
          _ (exec-op actor "t11a" {:op :stay/check-out :subject "stay-1"} operator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :stay/check-out :subject "stay-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-checked-out} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/check-out-history db))) "still only the one earlier check-out"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :stay/intake :subject "stay-1"
                          :patch {:id "stay-1" :property "Kita Inn"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "stay-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
