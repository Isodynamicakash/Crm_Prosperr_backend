# BRD + PRD traceability

Every requirement from the **Consolidated PRD v1** and the **Dashboard BRD**, with where it lives in the code and the test that proves it.

**Status**
- ✅ Done: implemented and covered by a test.
- 🟡 Partial: implemented with a stated limitation.
- ⏳ Not in v1: the PRD itself puts it in a later phase.

**Test column**
- `Unit:` tests that were **executed** here (64/64 pass).
- `IT:` integration tests that are written and type-checked but **not yet executed** (run `mvn test`).
- `SQL✓` means the query was checked against the real schema in Postgres.

Where the BRD and PRD differ, the PRD decides; those rows say "PRD over BRD".

---

## A. PRD functional requirements

### M1 Accounts and stakeholders
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M1-1 | Create by hand, by LinkedIn URL, CSV/XLSX import with column mapping + duplicate report | `AccountController`, `StakeholderController`, `importer/*` | IT: `AccountsIT.Duplicates_M2_9…`, `ReportingAndImportIT.M1_1_csvImportWithMappingAndDuplicateReport` | ✅ ("by LinkedIn URL" = person created from name + profile URL; scraping is out of scope per PRD) |
| M1-2 | Account page: details, owners, stage, status, stakeholders by dept, meetings, opportunity, timeline | `AccountQueryService.page` | IT: `AccountsIT.M1_2_accountPage…` | ✅ |
| M1-3 | Many emails/phones; mark number invalid without closing stakeholder | `StakeholderService.addEmail/addPhone/markPhoneInvalid` | Unit: `NormalizerTest.M1_3_phoneToE164`; IT: `AccountsIT.M1_3_…` | ✅ |
| M1-4 | Size bucket from license potential; admin bounds | DB trigger `account_set_size_bucket`, `SizeBucketClassifier`, `PUT /api/admin/size-buckets` | Unit: `SizeAndDesignationTest.M1_4…`; IT: `AccountsIT.M1_4…` | ✅ |
| M1-5 | Level + right-POC from designation map; unmapped → review list | `DesignationResolver`, `v_unmapped_designations`, `/api/admin/designations/review`, backfill listener | Unit: `SizeAndDesignationTest.M1_5…` (2); IT: `AccountsIT.M1_5…` | ✅ |
| M1-6 | Global search (account, person, email, phone, LinkedIn); saved filters | `AccountQueryService.search`, `SavedViewService` | IT: `AccountsIT.M1_6…` | ✅ |

### M2 BDR prospecting and calling
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M2-1 | My Day: overdue + due today, untouched, revival due | `MyDayService`, `MyDayOrdering` | Unit: `MyDayOrderingTest`; IT: `ProspectingIT.M2_1…` | ✅ |
| M2-2 | Click-to-call / WhatsApp; outcome in two clicks | `MyDayItem.phone`, `PhoneView.telLink/whatsappLink`, `POST /calls` | IT: `ProspectingIT.M2_1…` | ✅ (links rendered by the frontend) |
| M2-3 | Outcome rule sets status, next task + due; editable before save | `call_outcome_rule`, `OutcomePlanner`, `/calls/preview`, overrides | Unit: `OutcomePlannerTest` (9); IT: `ProspectingIT.M2_3…` (2) | ✅ |
| M2-4 | Unlimited activities; save and next | `ActivityService`, `LogCallResult.nextQueueItem` | IT: `ProspectingIT.M2_4…`, `M2_3_didNotPick…` | ✅ |
| M2-5 | Referral creates/links referred stakeholder with connection type | `CallService.log` (referral), `StakeholderService.connectionType` | Unit: `OutcomePlannerTest.M2_5…`; IT: `ProspectingIT.M2_5…` | ✅ |
| M2-6 | Priority order; suggest next when unresponsive/redirected | `StakeholderPrioritizer` | Unit: `MultithreadingRulesTest` (3); IT: `ProspectingIT.Decision7…` | ✅ |
| M2-7 | Parallel stakeholders, own reminders, account shows who/when/result | per-stakeholder tasks; `multithreading.lastTouchByStakeholder` | IT: `ProspectingIT.M2_3…`, `LifecycleIT.M7_5…` | ✅ |
| M2-8 | Prospecting private to owning BDR + leadership | `AccessPolicy`, `AccountFilter` visibility | Unit: `AccessPolicyTest.M2_8…`; IT: `AccountsIT.M2_8…` | ✅ |
| M2-9 | Several BDRs per account; join instead of duplicate; see taken (name, designation, BDR only) | `AccountService.resolveOrCreate`, `takenBy`, masked `StakeholderView` | IT: `AccountsIT.Duplicates_M2_9…`, `ProspectingIT.M2_9_M2_10…` | ✅ |
| M2-10 | One BDR owner per stakeholder; L1 booker gets credit; others contributors | `stakeholder.bdr_owner_id`, `setSqlCredit`, `account_bdr.role` | IT: `ProspectingIT.M2_9_M2_10…` | ✅ |

### M3 Email sequences
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M3-1 | Connect each BDR's Gmail; send from own mailbox in one thread | `PUT /api/mailbox`, `MailGateway` | IT: `OutreachIT.M3_1…`, `M3_3_M3_5…` | 🟡 Engine sends from the BDR's mailbox address in one thread; **Gmail OAuth + Gmail API adapter not built** (README §6) |
| M3-2 | Start on one/many; valid email; skip DNC and already running | `SequenceService.start`, `SequenceRules.checkEnroll` | Unit: `OutreachRulesTest.M3_2_and_M3_6…`; IT: `OutreachIT.M3_2…` | ✅ |
| M3-3 | Intro d0 → F1 d2 → F2 d5 → Reminder + call task d7 | V2 seed `sequence_step`, `processOne` | Unit: `OutreachRulesTest.M3_3…`; IT: `OutreachIT.M3_3_reminderToBdr…` | ✅ |
| M3-4 | Templates, merge fields, snippets, preview, block send on empty required field | `TemplateRenderer`, V2 Appendix A seed | Unit: `OutreachRulesTest.M3_4…` (3) | ✅ |
| M3-5 | Persona pitch by department | `TemplateRenderer.personaVariant` | Unit: `OutreachRulesTest.M3_5…`; IT: `OutreachIT.M3_3_M3_5…` | ✅ |
| M3-6 | Check thread before every send; stop on reply/bounce/opt-out/L1 booked/manual; reply → Connected + call task | `processOne`, `pollReplies`, `onMeetingBooked`, `handleStop` | Unit: `OutreachRulesTest`; IT: `OutreachIT.M3_6…` (2) | ✅ (real reply detection needs the Gmail adapter) |
| M3-7 | Did Not Pick offers "Start sequence"; Call Line only after a missed call | `OutcomePlan.offerSequence`, `{{Call Line}}` | Unit: `OutcomePlannerTest.M2_3_didNotPick…`, `OutreachRulesTest.M3_4_emptyFieldRules`; IT: `ProspectingIT.M2_3…` | ✅ |
| M3-8 | Each email logged on stakeholder + multithreading timeline | `email_message` + EMAIL activity | IT: `OutreachIT.M3_3_M3_5…` | ✅ |
| M3-9 | Window weekdays 9:30–18:30 IST, daily cap, spacing | `SendWindow`, `user_mailbox.daily_send_cap/min_gap_seconds` | Unit: `OutreachRulesTest.M3_9…`; IT: `OutreachIT.M3_9…` | ✅ |
| M3-10 | Monitor: running, overdue, completed, replied, stopped by reason; failed send alerts | `SequenceService.monitor`, `SequenceSendFailed` | IT: `OutreachIT.M3_9_…M3_10…` | ✅ |
| M3-11 | Parallel at one account; warn same company same day | `start` warning | IT: `OutreachIT.M3_2…M3_11…` | ✅ |
| M3-12 | Reply rate per template and step | `/api/reports/sequence-performance` | IT: `ReportingAndImportIT.Report1…`; SQL✓ | ✅ |

### M4 SQL qualification
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| ICP: right stakeholder | L1/L2 direct, or L3–L5 referred by L1/L2 at same account | `RightStakeholderCriterion` | Unit: `QualificationEngineTest.M4_rightStakeholder…`, `M4_unmapped…`; IT: `QualificationIT.M4_rightStakeholder…` | ✅ |
| ICP: licenses | >250; Small only as exception with approval, ≤20% | `LicensesCriterion`, `SmallShareGuard` | Unit: `…M4_11…` (2); IT: `QualificationIT.M4_11…` | ✅ |
| ICP: location | P0 pass; P1 only with High intent | `LocationCriterion` | Unit: `…M4_7…`; IT: `QualificationIT.M4_location…` | ✅ |
| ICP: BANT | all four recorded | `BantCriterion` | Unit: `…M4_10…`; IT: `QualificationIT.M4_3_M4_10…` | ✅ |
| M4-1 | Checklist auto-checked from account + stakeholder | `SqlService.checklist` | IT: `QualificationIT.M4_1…` | ✅ |
| M4-2 | Criteria + pass rules configurable; shows pass/fail | `icp_rule.config`, `/api/admin/icp-rules`, `sql_criterion_result` | Unit: `…M4_2…` | ✅ |
| M4-3 | BRD statuses; mandatory fields validated | `SqlStatus`, `missingFields` | IT: `QualificationIT.M4_3_M4_10…`, `BRD_4_3…` | ✅ |
| M4-4 | Booking L1 is part of submission but not itself an SQL | `SqlService.submit` | IT: `QualificationIT.M4_4…` | ✅ |
| M4-5 | Rejected needs reason, returns to BDR | `validate` (reject) | IT: `QualificationIT.M4_5…` | ✅ |
| M4-6 | Referral pass needs referrer linked; chain shown | `referring_stakeholder_id`, `GET /api/sql/{id}` | IT: `ProspectingIT.M2_9_M2_10…` | ✅ |
| M4-7 | Chennai/Mumbai: High intent + reason; manager confirms | `LocationCriterion`, `confirmIntent` | Unit + IT: `M4_7…` | ✅ |
| M4-8 | SQL quality by location priority and level | `ReportService.sqlQuality` | IT: `ReportingAndImportIT.Report1…`; SQL✓ | ✅ |
| M4-9 | AE corrects after L1; audited; BDR original kept | `SqlService.correct`, `qualification_correction` | IT: `QualificationIT.M4_9…` | ✅ |
| M4-10 | BANT on the form; all four required; AE can update | `SubmitSql.bant`, correctable `bant_*` | IT: `QualificationIT.M4_3_M4_10…` | ✅ |
| M4-11 | Small needs reason + manager approval; share vs 20% shown | `submit`, `validate`, BDR dashboard `smallAccountShare` | IT: `QualificationIT.M4_11…`, `ReportingAndImportIT.Dashboards…` | ✅ |

### M5 Assignment and AE capacity
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M5-1 | SQLs land in manager queue with notification | `AssignmentService.queue`, `SQL_SUBMITTED` | IT: `AssignmentAndPipelineIT.M5_1_M5_3…`, `QualificationIT.M4_4…` | ✅ |
| M5-2 | Recommend by active, pending L1, advanced load (round-robin among least loaded) | `v_ae_capacity`, `CapacityAllocation` | Unit: `AllocationTest` (3); IT: `…M5_2…` | ✅ |
| M5-3 | No automatic assignment | only `POST /assign` by leadership | IT: `…M5_1_M5_3…` | ✅ |
| M5-4 | Reassignment needs reason; history; open tasks move | `assign`, `assignment_history`, `moveOpen` | IT: `…M5_4…` | ✅ |
| M5-5 | Deactivating a user lists all accounts for bulk reassignment | `UserService.deactivate`, `/api/assignment/bulk` | IT: `AuthAndAdminIT.M5_5…` | ✅ |

### M6 Meetings and opportunity
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M6-1 | L1/L2/L3 with booked-on, scheduled, status, outcome, notes; several per level | `meeting`, `MeetingService` | IT: `…M6_10…`, `…M6_3_noShow…` | ✅ |
| M6-2 | Outcome required after meeting time; overdue on AE queue | `overdueOutcomes`, `MEETING_NO_OUTCOME` | Unit: `StagePolicyTest.M6_3…M6_2…`; IT: `…M6_2…` | ✅ |
| M6-3 | BRD L1 outcomes; No Show → reschedule task for BDR | `MeetingRules`, `recordOutcome` | IT: `…M6_3_noShow…`, `…M6_3_M6_4…` | ✅ |
| M6-4 | Stage follows latest completed meeting; then Negotiation/Won/Lost/Implementation by AE | `StagePolicy.derive/checkManualMove` | Unit: `StagePolicyTest` (3); IT: `…M6_5_M6_6…` | ✅ |
| M6-5 | Temperature by AE with change history | `temperature_history` | IT: `…M6_5_M6_6…` | ✅ |
| M6-6 | Expected MRR, license count | `opportunity` | IT: `…M6_5_M6_6…` | ✅ |
| M6-7 | Stagnation reason for Large/Enterprise at L1 > N days | `StagnationPolicy`, `stagnationReasonRequired`, hygiene list | Unit: `StagePolicyTest.M6_7…` | 🟡 Flagged and listed, not a hard block on save |
| M6-8 | Google Calendar invite | — | — | ⏳ P2 / phase 3 |
| M6-9 | Stage checklists built from definitions | `stage_checklist_item` seed, `/checklist` | IT: `…M6_10…` | ✅ |
| M6-10 | Stage done only when exit step logged (with attachment) | `StagePolicy`, `tick`, `ATTACHMENT_REQUIRED` | Unit: `…M6_10…`; IT: `…M6_10…` | ✅ |
| M6-11 | One meeting L1+L2 completes both; counted once each, 0 days between | `also_completes_l2`, stepwise `reevaluate` | Unit: `…M6_11…`; IT: `…M6_11…` | ✅ |

### M7 Multithreading
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M7-1 | Grouped by Finance/HR/Payroll, priority order, SPOC + DM marked | `multithreading()` | IT: `LifecycleIT.M7_5…` | ✅ |
| M7-2 | Engagement per stakeholder with last interaction and next follow-up | `PATCH /engagement`, `last_interaction_at` (trigger) | IT: `LifecycleIT.M7_5…` | ✅ |
| M7-3 | Unresponsive SPOC prompts next stakeholder | `SPOC_UNRESPONSIVE` + `suggestedNext` | Unit: `MultithreadingRulesTest.M2_6_and_M7_3…`; IT: `LifecycleIT.M7_5…` | ✅ |
| M7-4 | Coverage indicator per function and level | `AccountEngagement.coverage` | Unit: `…M7_4…` | ✅ |
| M7-5 | One unresponsive SPOC never makes the account Inactive | no automatic inactivation anywhere | Unit: `…M7_5…`; IT: `LifecycleIT.M7_5…` | ✅ |
| M7-6 | Timeline: rows per stakeholder, calls/emails/meetings/referrals/status changes, gaps highlighted | `timeline()` + STATUS_CHANGE/REFERRAL activities | IT: `LifecycleIT.M7_5…`, `ProspectingIT.M2_5…` | ✅ (drawing is frontend) |
| M7-7 | Prospecting touches only to owning BDR + leadership | `AccessPolicy.canSeeActivity`, `zone_at_time` | Unit: `AccessPolicyTest.M7_7…` | ✅ (decision #13 setting) |
| M7-8 | Filters by date, type, owner | `timeline(from,to,type,ownerId)` | SQL✓ | ✅ |

### M8 Active, inactive and revival
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M8-1 | Flag after 60 days (configurable per stage) → "To be moved" | `flagStaleAccounts`, `stage_threshold` | Unit: `InactivityPolicyTest.M8_1…`; IT: `LifecycleIT.M8_1…` | ✅ |
| M8-2 | Move (reason + remark) or keep (next step + date) | `moveToInactive`, `keepActive` | Unit: `…M8_2…`; IT: `LifecycleIT.M8_1…`, `M8_2…` | ✅ |
| M8-3 | Optional manager approval | `require_inactive_approval` | IT: `LifecycleIT.M8_3…` | ✅ |
| M8-4 | Inactive keeps stage, owners, stakeholders, history | status flag only | IT: `LifecycleIT.M8_2…` | ✅ |
| M8-5 | Changeable reasons need revisit date → revival queue | `requires_revisit_date`, `openRevisits` | Unit: `…M8_5…`; IT: `LifecycleIT.M8_5…` | ✅ |
| M8-6 | Revival queue by size and reason; BDRs add stakeholders without duplicates | `revivalQueue`, REACTIVATE rule in `create` | IT: `LifecycleIT.M8_5_M8_6_M8_7…` | ✅ |
| M8-7 | Reactivation records date/reason/who; last stage or L1 with new stakeholder | `reactivate`, `account_status_history` | Unit: `…M8_7…`; IT: `LifecycleIT.M8_5_M8_6_M8_7…` | ✅ |
| M8-8 | Active / Inactive views; combined for managers | `GET /api/accounts?status=` | IT: `LifecycleIT.M8_2…` | ✅ |

### M9 Targets, M10 Admin and audit
| ID | Requirement | Where | Test | Status |
|---|---|---|---|---|
| M9-1 | Manager sets targets per user per week/month/quarter | `TargetService.set` | IT: `ReportingAndImportIT.M9…` | ✅ |
| M9-2 | Achievement live, incl. small-account share | `achievement`, BDR dashboard | IT: `ReportingAndImportIT.M9…`, `Dashboards…` | ✅ (decision #9: BDR "achieved" = L1 done) |
| M10-1 | Users, roles, picklists, outcome rules, ICP rules, designation map, buckets, thresholds without code | `UserAdminController`, `ConfigController` | IT: `AuthAndAdminIT.M10_1…` (2) | ✅ |
| M10-2 | Audit for owner, stage, status, temperature, reason, SQL changes | `AuditApi` + history tables | IT: `ReportingAndImportIT.M10_2…`, `…M6_5_M6_6…` | ✅ |
| M10-3 | Export any list/report CSV/XLSX | `TableExport`, `format=` | IT: `ReportingAndImportIT.Dashboards_everyNumber…` | ✅ |

### Permission table (PRD "Users, roles and permissions")
Every row is in `AccessPolicy` and **unit-tested** in `AccessPolicyTest` (9 tests, executed). Admin field-level overrides use
`role_field_permission` (`AuthAndAdminIT.M10_1_adminEdits…`). PRD over BRD: AE edits are limited to their own accounts, BDRs cannot
update L1–L3, BDR reactivation is allowed and AE reactivation is not.

### Dashboards
| Dashboard | Shows (PRD) | Where | Test | Status |
|---|---|---|---|---|
| BDR | calls, connects, sequences, SQLs submitted/approved/rejected, L1 booked/done vs target, no-shows, small share, revival due | `DashboardService.bdr` | IT: `Dashboards…`; SQL✓ | ✅ |
| AE | assigned, active, L1 pending, L2/L3 pending, follow-ups due/overdue, unresponsive, needs multithreading, to be moved, targets, MRR by temperature | `DashboardService.ae` | IT: `Dashboards…`; SQL✓ | ✅ |
| AE allocation | per AE active, L1 pending, L2, L3, negotiation, inactive, total; workload; recommended | `AssignmentService.capacity` | Unit: `AllocationTest`; IT: `…M5_2…` | ✅ |
| Leadership | funnel + conversion, time in stage, stuck, wins/losses, active vs inactive, MRR, by AE / BDR | `DashboardService.leadership` | IT: `Dashboards…`; SQL✓ | ✅ |
| Pipeline hygiene | no next step, overdue tasks by user, meetings without outcome, inactive without reason detail | `DashboardService.hygiene` | IT: `Dashboards…`; SQL✓ | ✅ |
| Shared filters + drill-down | date presets, BDR, AE, city, bucket, industry, stage, temperature, status; every number opens accounts | `AccountFilter`, `/drilldown` | IT: `Dashboards_everyNumber…`; SQL✓ (all filters) | ✅ |

### Reports (P1)
| # | Report | Where | Test | Status |
|---|---|---|---|---|
| 1 | L1 not reaching L2 by AE, BDR, size, SPOC designation, responsiveness, multithreading, reason, last interaction, next follow-up | `v_l1_not_progressed`, `ReportService.l1NotProgressed` | IT: `Report1…` | ✅ |
| 2 | Only junior stakeholders; no Finance/CFO or CHRO engagement | `stakeholderCoverage` | Unit: `Report2_onlyJunior…`; SQL✓ | ✅ |
| 3 | Inactive reasons over time; reactivations by reason | `inactiveReasons` | SQL✓ | ✅ |
| 4 | AE workload and assignment history | `workload` | SQL✓ | ✅ |
| 5 | Lead ageing and follow-up compliance | `ageing` | SQL✓ | ✅ |
| 6 | Sequence performance by template | `performance` | SQL✓ | ✅ |

### Notifications (PRD table)
| Trigger | Who | Where | Status |
|---|---|---|---|
| SQL submitted | Manager | `NotificationListener.sqlSubmitted` (IT `M4_4`) | ✅ |
| Assigned / reassigned | New AE (+ previous) | `assigned` (IT `M5_4`) | ✅ |
| Meeting tomorrow | AE + BDR | `ReminderService.meetingsTomorrow` (18:00 IST) | ✅ SQL✓ |
| Meeting passed, no outcome | AE | `meetingsWithoutOutcome` (hourly) | ✅ SQL✓ |
| Follow-up due / overdue | Owner; manager past threshold | `followUps` (09:00 IST) | ✅ SQL✓ |
| SPOC Unresponsive | AE, prompt to multithread | `unresponsive` (IT `M7_5`) | ✅ |
| Flagged "to be moved" | AE | `flagged` (IT `M8_1`) | ✅ |
| Revisit date reached | BDR revival queue | `revisit` + REVIVAL task (IT `M8_5…`) | ✅ |
| Account reactivated | AE + manager | `reactivated` (IT `M8_5…`) | ✅ |
| Email reply received | Owner | `reply` (IT `M3_6`) | ✅ |
| Daily digest 09:00 IST | Each user | `dailyDigest` | 🟡 in-app; email delivery needs the Gmail adapter |

### Non-functional
| Item | Status |
|---|---|
| Scale: 25 users, 100k stakeholders, 20k accounts, <1 s lists | 🟡 Indexes for every list/queue (V1); paging; **not load-tested** |
| Devices: My Day / account / outcome on phone | Frontend concern; API is responsive-agnostic |
| Security: Google sign-in on company domain, role-based, encryption | ✅ Google ID token verified (issuer, audience, expiry), domain + admin-created + active checks (IT `AuthAndAdminIT`); encryption in transit/at rest is Supabase/TLS |
| Privacy (DPDP): do-not-contact, unsubscribe link, deletion on request | ✅ DNC, `/public/unsubscribe` (IT), `DELETE …/personal-data` erase |
| Audit + backup | ✅ audit; backups are Supabase (daily, 30 days to configure) |
| Locale IST, ₹ | ✅ all schedules and date maths in Asia/Kolkata; MRR `numeric(14,2)`; display formatting in frontend |
| Integration-ready: Apollo (CSV first), Gmail, Calendar | ✅ CSV import; 🟡 Gmail port; ⏳ Calendar / Apollo API |

### Migration and phasing
| Item | Status |
|---|---|
| Import all workbooks in one pass; sheets read-only | 🟡 Generic importer with mapping, normalization and duplicate report is done. Workbook-specific clean-up rules need the real sheets (README §6) |
| Accounts matched across workbooks by name and domain | ✅ same duplicate rules as manual entry |
| Phase 1 scope (M1, M2, M3 except 11–12, M4, M5 manual, M6-1..5, M7-1/2/5, M8 except approval, M9, M10; 4 dashboards; notifications; migration) | ✅ all built, plus most phase 2 items (M3-11/12, M5-2, M7-3/4, M8-3, M6-6/7, allocation dashboard, reports 1–6) |

### PRD "Decisions needed": how each is implemented
| # | Decision | Implemented as |
|---|---|---|
| 1 | ICP cities | `city.icp_priority` P0/P1 (seeded), `LocationCriterion` |
| 2 | Minimum size | `icp_rule LICENSES min_licenses=251`, Small exception ≤20% with approval |
| 3 | First contact senior? | L1/L2 direct or L3–L5 referred by L1/L2 |
| 4 | Who picks the AE | Manager only, with recommendation |
| 5 | Inactivity flag | 60 days, per-stage `stage_threshold` |
| 6 | Approval for inactive/reactivate | Off by default; `require_inactive_approval` setting (inactive moves) |
| 7 | Call retry cadence | 2 working days, 6 tries, then Unresponsive + 30-day revisit (`call_outcome_rule`) |
| 8 | AE capacity weighting | active accounts, L2/L3/Negotiation × 1.5 (`v_ae_capacity`) |
| 9 | BDR target "achieved" | L1 done |
| 10 | Who sets temperature | AE (`PATCH /opportunities`) |
| 11 | Multiple opportunities | One primary in v1; schema allows more |
| 12 | Tech stack | Spring Boot + React + Supabase Postgres |
| 13 | Prospecting history visible at L1 | Yes, setting `prospecting_history_visible_after_l1` |
| 14 | 127 stuck sequences | ⏳ one-off migration step (needs the sheet) |

---

## B. BRD (point-wise)

| BRD section | Requirement | Covered by | Status |
|---|---|---|---|
| 1 Background | Central CRM replacing sheets; visibility, ownership, qualification, multithreading, active/inactive separation, bandwidth allocation, follow-up automation, dashboards | all modules | ✅ |
| 2 Objectives | Only qualified SQLs; senior access; BDR+AE owner; multithreading; reduced stagnation; active/inactive; allocation; transparency; traceability | M4, `ConnectionType`, `account_bdr`/`ae_owner_id`, M7, M8, M5, pipeline zone, audit + history | ✅ |
| 3.1 BDR | generate, qualify, approach seniors, book L1, keep source/qualification data, revive inactive | M1, M2, M4, M8-6 | ✅ |
| 3.2 AE | own from L1, outcomes, requirements, multithreading, L2/L3, stage, next steps, commercials, inactivity/loss reasons | M6, M7, M8 | ✅ |
| 3.3 Management | validate SQLs, assign, monitor bandwidth, reassign, monitor pipelines and performance, permissions, configuration | M4-5, M5, dashboards, M10 | ✅ |
| 4.1 | Qualification criteria, configurable | M4 (PRD values over BRD's ">50") | ✅ PRD over BRD |
| 4.2 | Designation + priority; direct senior / junior via senior referral / direct junior | designation map, `priority_rank`, `ConnectionType` | ✅ |
| 4.3 | SQL statuses; mandatory fields before L1 assignment | `SqlStatus`, `missingFields`, status listeners | ✅ |
| 5 Stage 1 | manual + bulk entry, duplicate check, required fields | M1-1, duplicates | ✅ |
| 5 Stage 2 | assignment queue, recommendation, manual / round-robin / capacity, reassign with reason, history, no auto | M5, `RoundRobinAllocation`, `CapacityAllocation` | ✅ |
| 5 Stage 3 | L1 owned by AE; date/time; completion; outcomes; requirements; next steps | M6 | ✅ |
| 5 Stage 4 | multiple stakeholders, SPOC, DM, engagement, last interaction, follow-up, introductions, link every activity | M7, `referred_by_id`, activity/meeting stakeholder links | ✅ |
| 6.1–6.2 | Active / Inactive with reason + remarks; BRD reason list | `INACTIVE_REASON` (merged list) | ✅ |
| 6.3 | Never deleted; BDR access; AE reactivation; manager reassign; reactivation tracking; history retained | M8, M5-4 | ✅ PRD over BRD: BDR + manager reactivate, AE does not |
| 6.4 | Separate Active / Inactive views; consolidated for management | `status=ACTIVE/INACTIVE/ALL` | ✅ |
| 7.1–7.3 | Capacity metrics, allocation logic, example 25/12/17, history | `v_ae_capacity`, `AllocationTest.BRD_7_2…`, `assignment_history` | ✅ |
| 8 | Module table (13 modules) | one-to-one with packages | ✅ |
| 9 | Role permissions matrix | `AccessPolicy` (PRD table over BRD) | ✅ PRD over BRD |
| 10.1–10.4 | Leadership, BDR, AE, allocation dashboards + filters | reporting + assignment | ✅ |
| 11 | Configurable alerts; intervals and thresholds not hardcoded | notifications + `app_setting`/`stage_threshold` | ✅ |
| 12 | Company → stakeholders → meetings → activities → opportunity; duplicates flagged; history kept on reassignment | data model, duplicates, history | ✅ |
| 13 | 12 reports + key L1→L2 report with 9 segments | reporting | ✅ |
| 14 | Centralised DB, auth, audit, search, bulk upload/export, duplicates, validation, retention, scalable, desktop, Apollo/calendar readiness | see non-functional | ✅ / 🟡 as above |
| 15 | **Acceptance criteria (17)** | see below | |
| 16 | Phases | PRD phasing followed (it overrides the BRD order) | ✅ |
| 17 | Open points | answered by PRD decisions (table above) | ✅ |

### BRD 15: acceptance criteria → tests
| # | Criterion | Test |
|---|---|---|
| 1 | BDRs create and submit qualified leads | `QualificationIT.M4_4…` |
| 2 | SQL criteria validated in the CRM | `QualificationEngineTest` (unit, run), `QualificationIT.M4_1…`, `M4_rightStakeholder…` |
| 3 | Management sees queue and assigns | `AssignmentAndPipelineIT.M5_1_M5_3…` |
| 4 | AEs update L1, L2, L3 independently | `AssignmentAndPipelineIT.M6_10…` |
| 5 | Multiple stakeholders under one account | `AccountsIT.Duplicates_M2_9…`, `LifecycleIT.M7_5…` |
| 6 | Each stakeholder's engagement tracked separately | `LifecycleIT.M7_5…`, `ProspectingIT.Decision7…` |
| 7 | Unresponsive SPOC does not make account inactive | `MultithreadingRulesTest.M7_5…` (unit, run), `LifecycleIT.M7_5…` |
| 8 | AEs initiate multithreading and record progress | `LifecycleIT.M7_5…` |
| 9 | Leads can be marked Active or Inactive | `LifecycleIT.M8_2…` |
| 10 | Inactive searchable and revivable by BDRs | `LifecycleIT.M8_5_M8_6_M8_7…` |
| 11 | Reactivated accounts keep all history | `LifecycleIT.M8_5_M8_6_M8_7…` (status history, stakeholders) |
| 12 | AE-wise active pipeline and pending workload | `AssignmentAndPipelineIT.M5_2…` |
| 13 | Recommends AE by capacity | `AllocationTest` (unit, run), `…M5_2…` |
| 14 | Management can override recommendation | `…M5_4…` (manual pick recorded with recommended id) |
| 15 | Role-based edit permissions enforced | `AccessPolicyTest` (unit, run) + 403 checks across every IT class |
| 16 | Key changes in audit trail | `ReportingAndImportIT.M10_2…` |
| 17 | Funnel, conversion, ageing, stagnation reports | `ReportingAndImportIT.Dashboards…`, `Report1…` |
