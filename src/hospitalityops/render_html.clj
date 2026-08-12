(ns hospitalityops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo previously shipped a
  `docs/samples/operator-console.html` that was a leftover ROBOTICS
  scaffold stub (`<title>cloud-itonami · robotics</title>`, a
  `robot-1`/`deliver parcel` mission table) -- hand-written, and about
  a different vertical entirely. It is replaced here by a page whose
  every number, id, disposition and hold reason is produced by really
  running this repo's own actor stack.

  What actually runs when you invoke this namespace:

    hospitalityops.store/seed-db      -- the real seeded stay set
    hospitalityops.operation/build    -- the real langgraph StateGraph
      -> :advise  (hospitalityops.hospitalityopsllm mock advisor)
      -> :govern  (hospitalityops.governor/check -- the real censor)
      -> :decide  (hospitalityops.phase/gate    -- the real phase gate)
      -> :commit | :hold | :request-approval

  and the page is rendered from the resulting store: `store/ledger`,
  `store/all-stays`, `store/check-in-history`, `store/check-out-history`.
  Rule/gate tables are read out of `hospitalityops.phase/phases`,
  `phase/write-ops`, `phase/read-ops`, `governor/high-stakes` and
  `governor/confidence-floor` -- the vars themselves, so the page
  cannot drift from the code it documents.

  DETERMINISM: no timestamps, no random ids, no wall clock. The store
  is seeded from `store/demo-data` and every collection rendered is
  either an append-ordered ledger or explicitly sorted, so two runs
  from the same seed are byte-identical.

  BUILD-TIME INVARIANT: `-main` refuses to write the file if the
  resulting ledger contains zero `:governor-hold` facts. A console that
  only ever shows happy paths is not evidence that the governor works,
  so the HARD-hold requirement is enforced by the build rather than by
  convention. (Precedent: cloud-itonami-isic-2513.)

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [hospitalityops.facts :as facts]
            [hospitalityops.governor :as governor]
            [hospitalityops.operation :as op]
            [hospitalityops.phase :as phase]
            [hospitalityops.store :as store]))

(def ^:private operator
  "The human operator identity every run in this scenario is attributed
  to. `:phase` is `hospitalityops.phase/default-phase` -- read from the
  var, not typed in, so the page follows a rollout change."
  {:actor-id "op-1" :actor-role :accommodation-operator
   :phase phase/default-phase})

(defn- record!
  "Keeps ONE final state per thread-id, in first-appearance order.

  `langgraph.graph/run*` accumulates the `:audit` channel with `into`,
  so a resumed run's audit is a SUPERSET of the interrupted run's. Last
  state wins per thread; concatenating the survivors gives each audit
  entry exactly once."
  [seen tid result]
  (swap! seen
         (fn [v]
           (if-let [i (first (keep-indexed (fn [i [t _]] (when (= t tid) i)) v))]
             (assoc v i [tid result])
             (conj v [tid result]))))
  result)

;; ----------------------------- the real run -----------------------------

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches EVERY hard
  rule `hospitalityops.governor/check` can raise, plus clean approved
  paths on both actuations.

  Returns `{:db <store> :threads [[thread-id run*-result] ..]}` -- the
  store carries the committed SSoT and the append-only ledger, the
  thread results carry the graph's own `:audit` channel (advisor
  proposals, approval requests, approval grants), which the store
  ledger deliberately does not persist.

  Ordering is load-bearing in two places and is not cosmetic:

    - `room-double-booked` only fires while ANOTHER stay is checked in
      and not yet checked out, so stay-2's check-in attempt must happen
      between stay-1's check-in and stay-1's check-out.
    - `evidence-incomplete` only fires before a jurisdiction assessment
      is on file, so stay-3 is deliberately offered for check-in once
      BEFORE it is assessed (the hold writes no SSoT state, so the
      later clean lifecycle still runs).

  Hard rules covered (one scenario each):
    :no-spec-basis                              stay-2 assess (ATL, unregistered)
    :evidence-incomplete                        stay-3 check-in before assessment
    :guest-registration-incomplete              stay-4 (guest-id-verified? false)
    :room-double-booked                         stay-2 into Kita Inn 101 while stay-1 occupies it
    :already-checked-in                         stay-1 check-in twice
    :folio-total-mismatch                       stay-3 (claimed 35000, recompute 3x10000)
    :guest-disclosure-authorization-unconfirmed stay-5 (requested, not authorized)
    :already-checked-out                        stay-1 check-out twice

  Clean approved paths: stay-1 (no disclosure request) and stay-6
  (disclosure requested AND authorized) each walk intake -> assess ->
  check-in -> check-out with a human approval at every gate."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        seen (atom [])
        exec! (fn [tid request]
                (record! seen tid
                         (g/run* actor {:request request :context operator}
                                 {:thread-id tid})))
        approve! (fn [tid]
                   (record! seen tid
                            (g/run* actor {:approval {:status :approved :by "op-1"}}
                                    {:thread-id tid :resume? true})))]
    ;; -- stay-1: full clean lifecycle ------------------------------------
    (exec! "s1-intake" {:op :stay/intake :subject "stay-1"
                       :patch {:id "stay-1" :property "Kita Inn" :room "101"}})
    (exec! "s1-assess" {:op :jurisdiction/assess :subject "stay-1"})
    (approve! "s1-assess")
    (exec! "s1-checkin" {:op :stay/check-in :subject "stay-1"})
    (approve! "s1-checkin")

    ;; -- stay-2: no spec-basis, then a double-booked room ----------------
    (exec! "s2-assess-nospec" {:op :jurisdiction/assess :subject "stay-2"
                              :no-spec? true})
    ;; move stay-2 into the room stay-1 is currently occupying, over
    ;; overlapping dates, and into a jurisdiction that HAS a spec-basis
    ;; so the room clash is the only violation left standing.
    (exec! "s2-intake" {:op :stay/intake :subject "stay-2"
                       ;; NOTE: exactly 8 pairs -- a Clojure map literal
                       ;; stays an ordered array-map at 8 and becomes an
                       ;; unordered hash-map at 9, and the advisor cites
                       ;; `(keys patch)` into the committed fact's
                       ;; `:basis`, so a 9th key would make the page
                       ;; non-deterministic.
                       :patch {:id "stay-2" :property "Kita Inn" :room "101"
                               :jurisdiction "JPN"
                               :check-in-date "2026-09-02"
                               :check-out-date "2026-09-04"
                               :nights 2 :claimed-total 20000}})
    (exec! "s2-assess" {:op :jurisdiction/assess :subject "stay-2"})
    (approve! "s2-assess")
    (exec! "s2-checkin" {:op :stay/check-in :subject "stay-2"})

    ;; -- stay-1: check out, then prove both double-actuation guards -----
    (exec! "s1-checkout" {:op :stay/check-out :subject "stay-1"})
    (approve! "s1-checkout")
    (exec! "s1-checkin-again" {:op :stay/check-in :subject "stay-1"})
    (exec! "s1-checkout-again" {:op :stay/check-out :subject "stay-1"})

    ;; -- stay-6: full clean lifecycle WITH an authorized disclosure -----
    (exec! "s6-intake" {:op :stay/intake :subject "stay-6"
                       :patch {:id "stay-6" :property "Chuo Inn" :room "601"}})
    (exec! "s6-assess" {:op :jurisdiction/assess :subject "stay-6"})
    (approve! "s6-assess")
    (exec! "s6-checkin" {:op :stay/check-in :subject "stay-6"})
    (approve! "s6-checkin")
    (exec! "s6-checkout" {:op :stay/check-out :subject "stay-6"})
    (approve! "s6-checkout")

    ;; -- stay-3: unassessed check-in, then a folio mismatch -------------
    (exec! "s3-checkin-early" {:op :stay/check-in :subject "stay-3"})
    (exec! "s3-assess" {:op :jurisdiction/assess :subject "stay-3"})
    (approve! "s3-assess")
    (exec! "s3-checkin" {:op :stay/check-in :subject "stay-3"})
    (approve! "s3-checkin")
    (exec! "s3-checkout" {:op :stay/check-out :subject "stay-3"})

    ;; -- stay-4: incomplete guest registration --------------------------
    (exec! "s4-assess" {:op :jurisdiction/assess :subject "stay-4"})
    (approve! "s4-assess")
    (exec! "s4-checkin" {:op :stay/check-in :subject "stay-4"})

    ;; -- stay-5: pending disclosure request, never authorized -----------
    (exec! "s5-assess" {:op :jurisdiction/assess :subject "stay-5"})
    (approve! "s5-assess")
    (exec! "s5-checkin" {:op :stay/check-in :subject "stay-5"})
    (approve! "s5-checkin")
    (exec! "s5-checkout" {:op :stay/check-out :subject "stay-5"})
    {:db db :threads @seen}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw->s
  "Keyword -> its printed name WITHOUT the leading colon but WITH its
  namespace. `name` would silently turn `:actuation/check-in-guest`
  into `check-in-guest` and `:stay/intake` into `intake`, which reads
  as a different op than the one the code actually gates."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- join-names [coll]
  (str/join ", " (map kw->s coll)))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"muted\">no</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (if lede (str "    <p class=\"muted\">" lede "</p>\n") "")
       body
       "  </section>\n"))

;; ----------------------------- derived views -----------------------------

(defn- holds
  "Every HARD hold this run produced, straight out of the ledger."
  [ledger]
  (filter #(= :governor-hold (:t %)) ledger))

(defn- commits [ledger]
  (filter #(= :committed (:t %)) ledger))

(defn- audit
  "Every audit entry the graph emitted, once per thread, in run order.
  The store ledger deliberately persists only commits and holds, so the
  approval traffic (`:approval-requested` / `:approval-granted`) and the
  advisor's own proposals live here."
  [threads]
  (mapcat (comp :audit :state second) threads))

(defn- approvals [audit-entries]
  (filter #(= :approval-granted (:t %)) audit-entries))

(defn- approval-requests [audit-entries]
  (filter #(= :approval-requested (:t %)) audit-entries))

(defn- proposals [audit-entries]
  (filter #(= :hospitalityopsllm-proposal (:t %)) audit-entries))

(defn- last-fact-for [ledger stay-id]
  (last (filter #(= (:subject %) stay-id) ledger)))

(defn- disposition-cell [ledger stay-id]
  (let [f (last-fact-for ledger stay-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> "
           "<code>" (esc (join-names (:basis f))) "</code>")
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      :else (str "<span class=\"warn\">" (esc (kw->s (:t f))) "</span>"))))

(defn- lifecycle-cell [{:keys [checked-in? checked-out?]}]
  (cond
    checked-out? "<span class=\"ok\">checked out</span>"
    checked-in?  "<span class=\"warn\">in house</span>"
    :else        "<span class=\"muted\">not checked in</span>"))

(defn- stay-row [ledger {:keys [id property room jurisdiction check-in-date check-out-date
                                nights rate claimed-total currency
                                check-in-number check-out-number] :as s}]
  (row (esc id)
       (esc property)
       (esc room)
       (esc jurisdiction)
       (str (esc check-in-date) " &rarr; " (esc check-out-date))
       (str (esc nights) " &times; " (esc rate) " " (esc currency))
       (esc claimed-total)
       (lifecycle-cell s)
       (str (if check-in-number (str "<code>" (esc check-in-number) "</code>") "&mdash;")
            " / "
            (if check-out-number (str "<code>" (esc check-out-number) "</code>") "&mdash;"))
       (disposition-cell ledger id)))

(defn- phase-row [[n {:keys [label writes auto]}]]
  (row (esc n)
       (str (esc label)
            (when (= n phase/default-phase)
              " <span class=\"badge\">default</span>"))
       (if (seq writes) (str "<code>" (esc (join-names (sort (map kw->s writes)))) "</code>")
           "<span class=\"muted\">none</span>")
       (if (seq auto) (str "<code>" (esc (join-names (sort (map kw->s auto)))) "</code>")
           "<span class=\"muted\">none</span>")))

(defn- op-gate-row
  "One row of the op gate table. `writes?`/`auto?` are looked up in
  `hospitalityops.phase/phases` for the default phase; the observed
  column is counted out of this run's own ledger and audit trail."
  [ledger audit-entries op-kw]
  (let [{:keys [writes auto]} (get phase/phases phase/default-phase)
        n (fn [coll t] (count (filter #(and (= op-kw (:op %)) (= t (:t %))) coll)))
        held (n ledger :governor-hold)]
    (row (str "<code>" (esc op-kw) "</code>")
         (yes-no (contains? writes op-kw))
         (yes-no (contains? auto op-kw))
         (str (n ledger :committed) " committed &middot; "
              (n audit-entries :approval-requested) " escalated &middot; "
              (n audit-entries :approval-granted) " approved &middot; "
              (if (pos? held)
                (str "<span class=\"critical\">" held " held</span>")
                "0 held")))))

(defn- thread-row
  "One graph run: what the contained advisor node proposed, and what the
  governor + phase gate then did with it. Both halves come out of that
  thread's own `:audit` channel -- the outcome is NOT looked up by
  (op, subject), because the same pair legitimately occurs twice (a
  clean check-in, then a rejected double check-in) and the second
  would mislabel the first."
  [[tid {:keys [state]}]]
  (let [a (:audit state)
        first-of (fn [t] (first (filter #(= t (:t %)) a)))
        p (first-of :hospitalityopsllm-proposal)
        hold (first-of :governor-hold)
        req (first-of :approval-requested)
        granted (first-of :approval-granted)
        committed (first-of :committed)]
    (row (str "<code>" (esc tid) "</code>")
         (str "<code>" (esc (:op p)) "</code>")
         (esc (:subject p))
         (esc (:summary p))
         (str (esc (:confidence p))
              (when (< (double (:confidence p 0.0)) governor/confidence-floor)
                " <span class=\"warn\">below floor</span>"))
         (cond
           hold (str "<span class=\"critical\">HARD hold</span> <code>"
                     (esc (join-names (:basis hold))) "</code>")
           (and granted committed)
           (str "<span class=\"ok\">approved by " (esc (:by granted))
                "</span> &rarr; committed (escalated: <code>"
                (esc (kw->s (:reason req))) "</code>)")
           committed "<span class=\"ok\">auto-committed</span>"
           req (str "<span class=\"warn\">awaiting human approval</span> <code>"
                    (esc (kw->s (:reason req))) "</code>")
           :else "<span class=\"muted\">no disposition</span>"))))

(defn- hold-row [{:keys [op subject basis violations confidence]}]
  (row (str "<code>" (esc op) "</code>")
       (esc subject)
       (str "<span class=\"critical\">" (esc (join-names basis)) "</span>")
       (esc (str/join " / " (map :detail violations)))
       (esc confidence)))

(defn- ledger-row [{:keys [t op subject disposition basis reason by phase]}]
  (row (esc (kw->s t))
       (str "<code>" (esc (or op "&mdash;")) "</code>")
       (esc subject)
       (esc (or (some-> disposition kw->s) ""))
       (esc (cond
              (seq basis) (join-names basis)
              reason (kw->s reason)
              by (str "by " by)
              phase (str "phase " phase)
              :else ""))))

(defn- record-row [r]
  (row (str "<code>" (esc (get r "record_id")) "</code>")
       (esc (get r "kind"))
       (esc (get r "stay_id"))
       (esc (get r "jurisdiction"))
       (yes-no (get r "immutable"))))

(defn- jurisdiction-row [iso3]
  (let [sb (facts/spec-basis iso3)
        reg (facts/registration-spec-basis iso3)]
    (row (str "<code>" (esc iso3) "</code>")
         (esc (:name sb))
         (esc (:legal-basis sb))
         (if reg
           (str "<span class=\"ok\">" (esc (:registration-legal-basis reg)) "</span>")
           "<span class=\"warn\">no statutory regime in catalog</span>")
         (esc (count (:required-evidence sb))))))

;; ----------------------------- page -----------------------------

(defn render
  "Renders the operator console from a `run-demo!` result (`{:db ..
  :threads ..}`). Everything below reads that result or a var --
  nothing on the page is typed in."
  [{:keys [db threads]}]
  (let [ledger (vec (store/ledger db))
        stays (store/all-stays db)
        hs (holds ledger)
        aud (audit threads)
        cov (facts/coverage)
        ops (sort-by str (into phase/read-ops phase/write-ops))]
    (str
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-5510 &middot; community accommodation &mdash; Operator Console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community accommodation (ISIC 5510) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">generated at build time from a real actor run &middot; "
     (count threads) " operations &middot; "
     (count ledger) " ledger facts &middot; "
     (count hs) " HARD holds &middot; "
     (count (approvals aud)) " human approvals</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "Stays"
      (str "Every column below is read out of <code>hospitalityops.store</code> after "
           "the scenario in <code>hospitalityops.render-html/run-demo!</code> really ran "
           "through <code>hospitalityops.operation</code>. Check-in / check-out numbers are "
           "<code>hospitalityops.registry</code> drafts assigned by the store's own "
           "jurisdiction sequence &mdash; not typed in.")
      (table ["Stay" "Property" "Room" "Jurisdiction" "Dates" "Nights x rate"
              "Claimed folio" "Lifecycle" "Check-in / check-out no." "Last disposition"]
             (map (partial stay-row ledger) stays)))

     (section
      "Governor holds (this run)"
      (str "A HARD hold is never offered to a human &mdash; "
           "<code>hospitalityops.governor/check</code> returns <code>:hard? true</code> and "
           "<code>hospitalityops.phase/gate</code> keeps it held at every phase. "
           "Reasons and details below are the governor's own strings, verbatim.")
      (table ["Op" "Stay" "Rule" "Governor detail" "Advisor confidence"]
             (map hold-row hs)))

     (section
      "Op gate"
      (str "Left two columns are read out of <code>hospitalityops.phase/phases</code> at "
           "phase <code>" phase/default-phase "</code>; the right column counts this run's own "
           "ledger. <code>hospitalityops.governor/confidence-floor</code> = <code>"
           governor/confidence-floor "</code>. "
           "<code>hospitalityops.governor/high-stakes</code> = <code>"
           (esc (join-names (sort (map kw->s governor/high-stakes))))
           "</code> &mdash; a proposal carrying one of those stakes escalates to a human even "
           "when the governor is clean.")
      (table ["Op" (str "Writable @ phase " phase/default-phase)
              (str "Auto-commit @ phase " phase/default-phase) "Observed this run"]
             (map (partial op-gate-row ledger aud) ops)))

     (section
      "Operations: advisor proposal vs governor disposition"
      (str "One row per graph run. The left half is the contained advisor node's own "
           "output (<code>hospitalityops.hospitalityopsllm/trace</code>); the right half is "
           "what <code>hospitalityops.governor</code> and <code>hospitalityops.phase/gate</code> "
           "then did with it. "
           (count (approval-requests aud)) " of " (count threads)
           " operations were escalated to a human, " (count (approvals aud))
           " of those were approved, and " (count hs)
           " were held outright and never offered to anyone.")
      (table ["Thread" "Op" "Stay" "Advisor summary" "Confidence" "Disposition"]
             (map thread-row threads)))

     (section
      "Rollout phases"
      "Straight out of <code>hospitalityops.phase/phases</code>."
      (table ["Phase" "Label" "Writable ops" "Auto-commit ops"]
             (map phase-row (sort-by key phase/phases))))

     (section
      "Jurisdiction catalog"
      (str "From <code>hospitalityops.facts/catalog</code>. Coverage is reported honestly: "
           (:covered cov) " of " (:requested cov) " requested jurisdictions have an official "
           "spec-basis. A jurisdiction absent from this table has NO spec-basis, and any "
           "proposal citing one is held (<code>:no-spec-basis</code>).")
      (table ["ISO3" "Jurisdiction" "Accommodation-operations basis"
              "Guest-registration basis" "Required evidence items"]
             (map jurisdiction-row (:covered-jurisdictions cov))))

     (section
      "Check-in record drafts"
      "<code>hospitalityops.registry/register-check-in</code> output, appended by the store."
      (table ["Record" "Kind" "Stay" "Jurisdiction" "Immutable"]
             (map record-row (store/check-in-history db))))

     (section
      "Check-out record drafts"
      "<code>hospitalityops.registry/register-check-out</code> output, appended by the store."
      (table ["Record" "Kind" "Stay" "Jurisdiction" "Immutable"]
             (map record-row (store/check-out-history db))))

     (section
      "Audit ledger (this run)"
      (str "The append-only decision-fact log, in commit order &mdash; "
           (count (commits ledger)) " commits and "
           (count hs) " HARD holds. Note what is NOT here: the store persists only "
           "committed records and rejections, so the "
           (count (proposals aud)) " advisor proposals and "
           (count (approvals aud)) " approval grants live in the graph's own "
           "<code>:audit</code> channel (rendered above), not in the SSoT ledger.")
      (table ["Fact" "Op" "Stay" "Disposition" "Basis / reason"]
             (map ledger-row ledger)))

     "</main>\n"
     "<footer>Generated by <code>clojure -M:dev:render-html</code> "
     "(<code>hospitalityops.render-html</code>) from a real "
     "<code>hospitalityops.operation</code> run against "
     "<code>hospitalityops.store/seed-db</code>. Deterministic: no clock, no random ids. "
     "Regenerate to refresh.</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db threads] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        aud (audit threads)
        hs (holds ledger)]
    ;; Build-time invariant: a console that shows only happy paths is not
    ;; evidence that the governor works. Refuse to write one.
    (when (zero? (count hs))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced ZERO :governor-hold ledger facts. "
                           "The operator console must demonstrate at least one HARD hold "
                           "that never reaches a human -- otherwise it is a happy-path "
                           "brochure, not evidence the Hospitality Governor censors "
                           "anything. Fix hospitalityops.render-html/run-demo!, do not "
                           "relax this check.")
                      {:out out
                       :ledger-facts (count ledger)
                       :governor-holds 0
                       :fact-kinds (frequencies (map :t ledger))})))
    (spit out (render result))
    (println "wrote" out
             (str "(" (count threads) " operations, "
                  (count ledger) " ledger facts, "
                  (count hs) " HARD holds ["
                  (str/join " " (sort (distinct (mapcat :basis hs))))
                  "], "
                  (count (approval-requests aud)) " escalations, "
                  (count (approvals aud)) " human approvals, "
                  (count (commits ledger)) " commits, "
                  (count (store/check-in-history db)) " check-in drafts, "
                  (count (store/check-out-history db)) " check-out drafts)"))))
