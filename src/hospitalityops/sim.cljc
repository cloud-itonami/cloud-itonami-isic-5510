(ns hospitalityops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean stay through
  intake -> jurisdiction assessment -> check-in (escalate/approve/
  commit) -> check-out (escalate/approve/commit), then a SEPARATE
  clean disclosure-requested stay through the same lifecycle
  (demonstrating the conditional guest-disclosure-authorization check
  passing cleanly), then shows HARD-hold scenarios: a jurisdiction
  with no spec-basis, a folio-total mismatch (verified first), an
  incomplete guest registration, and an unauthorized guest-disclosure
  request on a disclosure-requested stay, a double check-in, and a
  double check-out.

  Like `retailops`/4711's, `freightops`/4920's, `quarryops`/0810's and
  `agronomyops`/0162's own new checks, this actor's new checks
  (`guest-registration-incomplete?`, `guest-disclosure-authorization-
  unconfirmed?`) are evaluated directly at `:stay/check-in`/`:stay/
  check-out` time rather than via a separate screening op -- a real
  check-in/check-out decision validates guest registration and
  disclosure authorization at the point of the act itself. Each check
  is still exercised directly and independently below, one stay per
  HARD-hold scenario, following the SAME 'exercise the failure mode
  directly, never only via a happy-path actuation' discipline
  `parksafety`'s ADR-2607071922 Decision 5 and every sibling since
  establish."
  (:require [langgraph.graph :as g]
            [hospitalityops.store :as store]
            [hospitalityops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :accommodation-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== stay/intake stay-1 (JPN, clean, no disclosure request) ==")
    (println (exec-op actor "t1" {:op :stay/intake :subject "stay-1"
                                  :patch {:id "stay-1" :property "Kita Inn"}} operator))

    (println "== jurisdiction/assess stay-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :jurisdiction/assess :subject "stay-1"} operator))
    (println (approve! actor "t2"))

    (println "== stay/check-in stay-1 (always escalates -- actuation/check-in-guest) ==")
    (let [r (exec-op actor "t3" {:op :stay/check-in :subject "stay-1"} operator)]
      (println r)
      (println "-- human accommodation operator approves --")
      (println (approve! actor "t3")))

    (println "== stay/check-out stay-1 (always escalates -- actuation/check-out-guest) ==")
    (let [r (exec-op actor "t4" {:op :stay/check-out :subject "stay-1"} operator)]
      (println r)
      (println "-- human accommodation operator approves --")
      (println (approve! actor "t4")))

    (println "== stay/intake stay-6 (JPN, clean, disclosure requested and authorized) ==")
    (println (exec-op actor "t5" {:op :stay/intake :subject "stay-6"
                                  :patch {:id "stay-6" :property "Chuo Inn"}} operator))

    (println "== jurisdiction/assess stay-6 (escalates -- human approves) ==")
    (println (exec-op actor "t6" {:op :jurisdiction/assess :subject "stay-6"} operator))
    (println (approve! actor "t6"))

    (println "== stay/check-in stay-6 (always escalates) ==")
    (println (exec-op actor "t6b" {:op :stay/check-in :subject "stay-6"} operator))
    (println (approve! actor "t6b"))

    (println "== stay/check-out stay-6 (disclosure requested, authorized -- escalates -- human approves) ==")
    (println (exec-op actor "t7" {:op :stay/check-out :subject "stay-6"} operator))
    (println (approve! actor "t7"))

    (println "== jurisdiction/assess stay-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :jurisdiction/assess :subject "stay-2" :no-spec? true} operator))

    (println "== jurisdiction/assess stay-3 (escalates -- human approves; sets up the folio-mismatch test) ==")
    (println (exec-op actor "t9" {:op :jurisdiction/assess :subject "stay-3"} operator))
    (println (approve! actor "t9"))

    (println "== stay/check-in stay-3 (always escalates) ==")
    (println (exec-op actor "t9b" {:op :stay/check-in :subject "stay-3"} operator))
    (println (approve! actor "t9b"))

    (println "== stay/check-out stay-3 (claimed 350.0 vs recompute 300.0 -> HARD hold) ==")
    (println (exec-op actor "t10" {:op :stay/check-out :subject "stay-3"} operator))

    (println "== jurisdiction/assess stay-4 (escalates -- human approves; sets up the guest-registration test) ==")
    (println (exec-op actor "t11" {:op :jurisdiction/assess :subject "stay-4"} operator))
    (println (approve! actor "t11"))

    (println "== stay/check-in stay-4 (guest registration incomplete -> HARD hold) ==")
    (println (exec-op actor "t12" {:op :stay/check-in :subject "stay-4"} operator))

    (println "== jurisdiction/assess stay-5 (escalates -- human approves; sets up the disclosure-authorization test) ==")
    (println (exec-op actor "t13" {:op :jurisdiction/assess :subject "stay-5"} operator))
    (println (approve! actor "t13"))

    (println "== stay/check-in stay-5 (always escalates) ==")
    (println (exec-op actor "t13b" {:op :stay/check-in :subject "stay-5"} operator))
    (println (approve! actor "t13b"))

    (println "== stay/check-out stay-5 (disclosure requested, unauthorized -> HARD hold) ==")
    (println (exec-op actor "t14" {:op :stay/check-out :subject "stay-5"} operator))

    (println "== stay/check-in stay-1 AGAIN (double-check-in -> HARD hold) ==")
    (println (exec-op actor "t15" {:op :stay/check-in :subject "stay-1"} operator))

    (println "== stay/check-out stay-1 AGAIN (double-check-out -> HARD hold) ==")
    (println (exec-op actor "t16" {:op :stay/check-out :subject "stay-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft check-in records ==")
    (doseq [r (store/check-in-history db)] (println r))

    (println "== draft check-out records ==")
    (doseq [r (store/check-out-history db)] (println r))))
