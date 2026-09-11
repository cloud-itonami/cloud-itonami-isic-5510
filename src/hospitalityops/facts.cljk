(ns hospitalityops.facts
  "Per-jurisdiction accommodation-operations AND guest-registration
  regulatory catalog -- the G2-style spec-basis table the Hospitality
  Governor checks every `:jurisdiction/assess` proposal against ('did
  the advisor cite an OFFICIAL public source for this jurisdiction's
  requirements, or did it invent one?').

  This blueprint's own text (docs/operator-guide.md's Minimum
  Production Controls: 'human sign-off for :high/:safety-critical
  robot actions... operating in guest rooms, near guests or handling
  guest belongings') and its own docs/business-model.md ('guest
  disclosures require approval') name two real, distinct regulatory
  concerns: the general fire-safety/health/licensing framework every
  accommodation establishment must operate under, and a SEPARATE
  statutory guest-registration regime specifically requiring hotels to
  record identifying information about guests at check-in (independent
  of the general operating-license framework -- the operating-license
  framework covers whether a property may run as an accommodation
  establishment at all; guest-registration law covers WHAT must be
  recorded about each individual guest). Each jurisdiction entry below
  therefore cites the general accommodation-operations law AND, where
  one exists, a SEPARATE guest-registration law.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. Unlike the recent
  streak of full-coverage sub-citations (`quarryops`/0810's own blast-
  safety, `agronomyops`/0162's own water-buffer, `leathergoods`/9523's
  own brand-authenticity, `ictrepair`/9511's own media-sanitization,
  `retailops`/4711's own unit-pricing and `freightops`/4920's own
  cargo-liability-disclosure), the guest-registration sub-citation here
  is an HONEST single-jurisdiction gap for the US: guest registration
  in the US is governed by fragmented state-by-state 'innkeeper'
  statutes rather than a single uniform national law, so no US
  guest-registration sub-citation is asserted here -- reported
  honestly rather than straining for a shaky single-state citation to
  force apparent full coverage.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  intake-record/advisory-record/check-in-record/check-out-record
  evidence set (PLUS a guest-registration record for jurisdictions that
  have one); `:legal-basis` / `:owner-authority` / `:provenance` are
  the G2 citation the governor requires before any `:jurisdiction/
  assess` proposal can commit. `:registration-owner-authority` /
  `:registration-legal-basis` / `:registration-provenance` are the
  SEPARATE guest-registration citation the governor's
  `guest-registration-incomplete?` check is grounded in -- absent for
  jurisdictions with no such regime (see ns docstring)."
  {"JPN" {:name "Japan"
          :owner-authority "都道府県 (prefectural governments) / 保健所 (public health centers), 厚生労働省 (MHLW)"
          :legal-basis "旅館業法 (Hotel Business Act)"
          :national-spec "旅館業の営業許可及び構造設備基準"
          :provenance "https://www.mhlw.go.jp/stf/seisakunitsuite/bunya/kenkou_iryou/kenkou/seikatsu-eisei/ryokan/"
          :required-evidence ["宿泊記録 (intake record)"
                              "助言記録 (advisory record)"
                              "チェックイン記録 (check-in record)"
                              "チェックアウト記録 (check-out record)"
                              "宿泊者名簿記録 (guest-registration record)"]
          :registration-owner-authority "都道府県 (prefectural governments) / 保健所 (public health centers)"
          :registration-legal-basis "旅館業法第6条 (宿泊者名簿の備付け義務)"
          :registration-provenance "https://www.mhlw.go.jp/stf/seisakunitsuite/bunya/kenkou_iryou/kenkou/seikatsu-eisei/ryokan/"}
   "USA" {:name "United States"
          :owner-authority "U.S. Fire Administration (USFA) / Federal Emergency Management Agency (FEMA)"
          :legal-basis "Hotel and Motel Fire Safety Act of 1990 (15 U.S.C. §2225)"
          :national-spec "Federal fire-safety standards for hotels eligible to host federally-funded travel"
          :provenance "https://www.usfa.fema.gov/prevention/outreach/hotel_motel.html"
          :required-evidence ["Intake record"
                              "Advisory record"
                              "Check-in record"
                              "Check-out record"]
          :registration-owner-authority nil
          :registration-legal-basis nil
          :registration-provenance nil}
   "GBR" {:name "United Kingdom"
          :owner-authority "Fire and Rescue Authorities (local)"
          :legal-basis "Regulatory Reform (Fire Safety) Order 2005"
          :national-spec "Fire risk assessment and life-safety standards for guest accommodation"
          :provenance "https://www.gov.uk/guidance/fire-safety-law-and-guidance-documents-for-business"
          :required-evidence ["Intake record"
                              "Advisory record"
                              "Check-in record"
                              "Check-out record"
                              "Guest-registration record"]
          :registration-owner-authority "Immigration officers / police (Immigration Act 1971)"
          :registration-legal-basis "Immigration (Hotel Records) Order 1972"
          :registration-provenance "https://www.legislation.gov.uk/uksi/1972/1689/contents/made"}
   "DEU" {:name "Germany"
          :owner-authority "Ordnungsämter (local regulatory/public order offices)"
          :legal-basis "Gaststättengesetz und landesrechtliche Beherbergungsstättenverordnung"
          :national-spec "Betriebs- und Sicherheitsanforderungen für Beherbergungsstätten"
          :provenance "https://www.gesetze-im-internet.de/gastg/"
          :required-evidence ["Aufnahmeprotokoll (intake record)"
                              "Beratungsprotokoll (advisory record)"
                              "Check-in-Protokoll (check-in record)"
                              "Check-out-Protokoll (check-out record)"
                              "Meldescheinnachweis (guest-registration record)"]
          :registration-owner-authority "Meldebehörden (local registration authorities)"
          :registration-legal-basis "Bundesmeldegesetz (BMG) §29 (Beherbergungsstättenmeldepflicht)"
          :registration-provenance "https://www.gesetze-im-internet.de/bmg/__29.html"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to check a guest
  in or check a guest out on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-5510 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `hospitalityops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn registration-spec-basis
  "The jurisdiction's guest-registration requirement map, or nil -- nil
  means this jurisdiction has NO formal statutory guest-registration
  regime this catalog is aware of (an HONEST single-jurisdiction gap
  for the US -- see ns docstring)."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:registration-owner-authority sb)
      (select-keys sb [:registration-owner-authority :registration-legal-basis :registration-provenance]))))
