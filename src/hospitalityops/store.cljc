(ns hospitalityops.store
  "SSoT for the community-accommodation actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/hospitalityops/store_contract_test.clj), which is the whole
  point: the actor, the Hospitality Governor and the audit ledger
  never know which SSoT they run on.

  Like `agronomyops`/0162's own `visit`, the primary entity here is a
  `stay` -- check-in and check-out actuation events apply SEQUENTIALLY
  to the SAME stay record (check in first, check out later), matching
  the freight/quarry/agronomy cluster's own sequential entity shape.
  Dedicated double-actuation-guard booleans (`:checked-in?`/`:checked-
  out?`, never a `:status` value).

  The ledger stays append-only on every backend: 'which stay was
  screened for incomplete guest registration or an unauthorized guest
  disclosure, which guest checked in, which guest checked out, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a small hotel or guesthouse
  trusting an accommodation operator needs, and the evidence an
  operator needs if a check-in or a check-out is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [hospitalityops.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (stay [s id])
  (all-stays [s])
  (assessment-of [s stay-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (check-in-history [s] "the append-only check-in history (hospitalityops.registry drafts)")
  (check-out-history [s] "the append-only check-out history (hospitalityops.registry drafts)")
  (next-check-in-sequence [s jurisdiction] "next check-in-number sequence for a jurisdiction")
  (next-check-out-sequence [s jurisdiction] "next check-out-number sequence for a jurisdiction")
  (stay-already-checked-in? [s stay-id] "has this stay already been checked in?")
  (stay-already-checked-out? [s stay-id] "has this stay already been checked out?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-stays [s stays] "replace/seed the stay directory (map id->stay)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained stay set covering both actuation lifecycles
  (check-in, check-out) plus the governor's own new checks, so the
  actor + tests run offline."
  []
  {:stays
   {"stay-1" {:id "stay-1" :property "Kita Inn" :room "101"
              :nights 2 :rate 100.0 :claimed-total 200.0
              :guest-id-verified? true
              :disclosure-requested? false :disclosure-authorized? false
              :checked-in? false :checked-out? false
              :jurisdiction "JPN" :status :intake}
    "stay-2" {:id "stay-2" :property "Atlantis Inn" :room "201"
              :nights 1 :rate 100.0 :claimed-total 100.0
              :guest-id-verified? true
              :disclosure-requested? false :disclosure-authorized? false
              :checked-in? false :checked-out? false
              :jurisdiction "ATL" :status :intake}
    "stay-3" {:id "stay-3" :property "Minami Inn" :room "301"
              :nights 3 :rate 100.0 :claimed-total 350.0
              :guest-id-verified? true
              :disclosure-requested? false :disclosure-authorized? false
              :checked-in? false :checked-out? false
              :jurisdiction "JPN" :status :intake}
    "stay-4" {:id "stay-4" :property "Higashi Inn" :room "401"
              :nights 2 :rate 120.0 :claimed-total 240.0
              :guest-id-verified? false
              :disclosure-requested? false :disclosure-authorized? false
              :checked-in? false :checked-out? false
              :jurisdiction "JPN" :status :intake}
    "stay-5" {:id "stay-5" :property "Nishi Inn" :room "501"
              :nights 2 :rate 90.0 :claimed-total 180.0
              :guest-id-verified? true
              :disclosure-requested? true :disclosure-authorized? false
              :checked-in? false :checked-out? false
              :jurisdiction "JPN" :status :intake}
    "stay-6" {:id "stay-6" :property "Chuo Inn" :room "601"
              :nights 4 :rate 110.0 :claimed-total 440.0
              :guest-id-verified? true
              :disclosure-requested? true :disclosure-authorized? true
              :checked-in? false :checked-out? false
              :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- check-in-guest!
  "Backend-agnostic `:stay/mark-checked-in` -- looks up the stay via
  the protocol and drafts the check-in record, and returns {:result ..
  :stay-patch ..} for the caller to persist."
  [s stay-id]
  (let [st (stay s stay-id)
        seq-n (next-check-in-sequence s (:jurisdiction st))
        result (registry/register-check-in stay-id (:jurisdiction st) seq-n)]
    {:result result
     :stay-patch {:checked-in? true
                 :check-in-number (get result "check_in_number")}}))

(defn- check-out-guest!
  "Backend-agnostic `:stay/mark-checked-out` -- looks up the stay via
  the protocol and drafts the check-out record, and returns {:result
  .. :stay-patch ..} for the caller to persist."
  [s stay-id]
  (let [st (stay s stay-id)
        seq-n (next-check-out-sequence s (:jurisdiction st))
        result (registry/register-check-out stay-id (:jurisdiction st) seq-n)]
    {:result result
     :stay-patch {:checked-out? true
                 :check-out-number (get result "check_out_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (stay [_ id] (get-in @a [:stays id]))
  (all-stays [_] (sort-by :id (vals (:stays @a))))
  (assessment-of [_ stay-id] (get-in @a [:assessments stay-id]))
  (ledger [_] (:ledger @a))
  (check-in-history [_] (:check-in-records @a))
  (check-out-history [_] (:check-out-records @a))
  (next-check-in-sequence [_ jurisdiction] (get-in @a [:check-in-sequences jurisdiction] 0))
  (next-check-out-sequence [_ jurisdiction] (get-in @a [:check-out-sequences jurisdiction] 0))
  (stay-already-checked-in? [_ stay-id] (boolean (get-in @a [:stays stay-id :checked-in?])))
  (stay-already-checked-out? [_ stay-id] (boolean (get-in @a [:stays stay-id :checked-out?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :stay/upsert
      (swap! a update-in [:stays (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :stay/mark-checked-in
      (let [stay-id (first path)
            {:keys [result stay-patch]} (check-in-guest! s stay-id)
            jurisdiction (:jurisdiction (stay s stay-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:check-in-sequences jurisdiction] (fnil inc 0))
                       (update-in [:stays stay-id] merge stay-patch)
                       (update :check-in-records registry/append result))))
        result)

      :stay/mark-checked-out
      (let [stay-id (first path)
            {:keys [result stay-patch]} (check-out-guest! s stay-id)
            jurisdiction (:jurisdiction (stay s stay-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:check-out-sequences jurisdiction] (fnil inc 0))
                       (update-in [:stays stay-id] merge stay-patch)
                       (update :check-out-records registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-stays [s stays] (when (seq stays) (swap! a assoc :stays stays)) s))

(defn seed-db
  "A MemStore seeded with the demo stay set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :check-in-sequences {} :check-in-records []
                           :check-out-sequences {} :check-out-records []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts,
  check-in/check-out records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:stay/id                        {:db/unique :db.unique/identity}
   :assessment/stay-id             {:db/unique :db.unique/identity}
   :ledger/seq                     {:db/unique :db.unique/identity}
   :check-in-record/seq            {:db/unique :db.unique/identity}
   :check-out-record/seq           {:db/unique :db.unique/identity}
   :check-in-sequence/jurisdiction    {:db/unique :db.unique/identity}
   :check-out-sequence/jurisdiction   {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- stay->tx [{:keys [id property room nights rate claimed-total
                         guest-id-verified?
                         disclosure-requested? disclosure-authorized?
                         checked-in? checked-out?
                         jurisdiction status check-in-number check-out-number]}]
  (cond-> {:stay/id id}
    property                                       (assoc :stay/property property)
    room                                              (assoc :stay/room room)
    nights                                               (assoc :stay/nights nights)
    rate                                                    (assoc :stay/rate rate)
    claimed-total                                              (assoc :stay/claimed-total claimed-total)
    (some? guest-id-verified?)                                    (assoc :stay/guest-id-verified? guest-id-verified?)
    (some? disclosure-requested?)                                    (assoc :stay/disclosure-requested? disclosure-requested?)
    (some? disclosure-authorized?)                                      (assoc :stay/disclosure-authorized? disclosure-authorized?)
    (some? checked-in?)                                                    (assoc :stay/checked-in? checked-in?)
    (some? checked-out?)                                                      (assoc :stay/checked-out? checked-out?)
    jurisdiction                                                                (assoc :stay/jurisdiction jurisdiction)
    status                                                                        (assoc :stay/status status)
    check-in-number                                                                 (assoc :stay/check-in-number check-in-number)
    check-out-number                                                                  (assoc :stay/check-out-number check-out-number)))

(def ^:private stay-pull
  [:stay/id :stay/property :stay/room :stay/nights :stay/rate :stay/claimed-total
   :stay/guest-id-verified? :stay/disclosure-requested? :stay/disclosure-authorized?
   :stay/checked-in? :stay/checked-out?
   :stay/jurisdiction :stay/status :stay/check-in-number :stay/check-out-number])

(defn- pull->stay [m]
  (when (:stay/id m)
    {:id (:stay/id m) :property (:stay/property m) :room (:stay/room m)
     :nights (:stay/nights m) :rate (:stay/rate m) :claimed-total (:stay/claimed-total m)
     :guest-id-verified? (boolean (:stay/guest-id-verified? m))
     :disclosure-requested? (boolean (:stay/disclosure-requested? m))
     :disclosure-authorized? (boolean (:stay/disclosure-authorized? m))
     :checked-in? (boolean (:stay/checked-in? m)) :checked-out? (boolean (:stay/checked-out? m))
     :jurisdiction (:stay/jurisdiction m) :status (:stay/status m)
     :check-in-number (:stay/check-in-number m) :check-out-number (:stay/check-out-number m)}))

(defrecord DatomicStore [conn]
  Store
  (stay [_ id]
    (pull->stay (d/pull (d/db conn) stay-pull [:stay/id id])))
  (all-stays [_]
    (->> (d/q '[:find [?id ...] :where [?e :stay/id ?id]] (d/db conn))
         (map #(pull->stay (d/pull (d/db conn) stay-pull [:stay/id %])))
         (sort-by :id)))
  (assessment-of [_ stay-id]
    (dec* (d/q '[:find ?p . :in $ ?sid
                :where [?a :assessment/stay-id ?sid] [?a :assessment/payload ?p]]
              (d/db conn) stay-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (check-in-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :check-in-record/seq ?s] [?e :check-in-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (check-out-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :check-out-record/seq ?s] [?e :check-out-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-check-in-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :check-in-sequence/jurisdiction ?j] [?e :check-in-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-check-out-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :check-out-sequence/jurisdiction ?j] [?e :check-out-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (stay-already-checked-in? [s stay-id]
    (boolean (:checked-in? (stay s stay-id))))
  (stay-already-checked-out? [s stay-id]
    (boolean (:checked-out? (stay s stay-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :stay/upsert
      (d/transact! conn [(stay->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/stay-id (first path) :assessment/payload (enc payload)}])

      :stay/mark-checked-in
      (let [stay-id (first path)
            {:keys [result stay-patch]} (check-in-guest! s stay-id)
            jurisdiction (:jurisdiction (stay s stay-id))
            next-n (inc (next-check-in-sequence s jurisdiction))]
        (d/transact! conn
                     [(stay->tx (assoc stay-patch :id stay-id))
                      {:check-in-sequence/jurisdiction jurisdiction :check-in-sequence/next next-n}
                      {:check-in-record/seq (count (check-in-history s)) :check-in-record/record (enc (get result "record"))}])
        result)

      :stay/mark-checked-out
      (let [stay-id (first path)
            {:keys [result stay-patch]} (check-out-guest! s stay-id)
            jurisdiction (:jurisdiction (stay s stay-id))
            next-n (inc (next-check-out-sequence s jurisdiction))]
        (d/transact! conn
                     [(stay->tx (assoc stay-patch :id stay-id))
                      {:check-out-sequence/jurisdiction jurisdiction :check-out-sequence/next next-n}
                      {:check-out-record/seq (count (check-out-history s)) :check-out-record/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-stays [s stays]
    (when (seq stays) (d/transact! conn (mapv stay->tx (vals stays)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:stays ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [stays]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-stays s stays))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo stay set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
