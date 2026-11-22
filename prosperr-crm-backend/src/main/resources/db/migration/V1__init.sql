-- =====================================================================
-- Prosperr Sales CRM — Database schema v1
-- Target: Supabase Postgres (PG 15+), accessed by a Spring Boot backend
-- Source: Consolidated PRD v1 (14 Nov 2026) + Dashboard BRD
--
-- Conventions
--   * Everything lives in schema `crm` (NOT `public`), so Supabase's
--     auto-generated Data API does not expose these tables to the browser.
--     Spring Boot connects over JDBC; set
--       spring.jpa.properties.hibernate.default_schema=crm
--   * Primary keys: uuid (gen_random_uuid()).  Map with @UuidGenerator or
--     let the DB default fill it.
--   * Fixed system values = text + CHECK  -> @Enumerated(EnumType.STRING)
--     Admin-editable lists  = rows in crm.picklist_value (FK by id)
--   * All times are timestamptz (store UTC, display IST).  Money = numeric(14,2) INR.
--   * Nothing in the pipeline is hard-deleted.  Inactive is a status.
--   * `version` columns are for JPA @Version optimistic locking.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS crm;
SET search_path = crm, public;

-- ---------------------------------------------------------------------
-- 0. Helpers
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION crm.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- =====================================================================
-- 1. USERS, ROLES, PERMISSIONS
-- =====================================================================
CREATE TABLE crm.app_user (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  auth_user_id     uuid UNIQUE,                    -- Supabase auth.users.id (Google sign-in), nullable until first login
  email            text NOT NULL UNIQUE,
  full_name        text NOT NULL,
  phone            text,
  role             text NOT NULL CHECK (role IN ('BDR','AE','MANAGER','ADMIN')),
  manager_id       uuid REFERENCES crm.app_user(id),
  is_active        boolean NOT NULL DEFAULT true,
  deactivated_at   timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          integer NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.app_user IS 'CRM users. MANAGER = sales manager / leadership. ADMIN = founder / ops.';

-- Field-level edit rights that admins can adjust (M10-1). Default rights live in code;
-- a row here overrides them.
CREATE TABLE crm.role_field_permission (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  role         text NOT NULL CHECK (role IN ('BDR','AE','MANAGER','ADMIN')),
  entity       text NOT NULL,           -- e.g. 'account', 'opportunity', 'sql_submission'
  field        text NOT NULL,           -- e.g. 'temperature', '*' for whole entity
  zone         text NOT NULL DEFAULT 'ANY' CHECK (zone IN ('PROSPECTING','PIPELINE','ANY')),
  scope        text NOT NULL CHECK (scope IN ('NONE','OWN','ALL')),  -- OWN = rows the user owns
  can_view     boolean NOT NULL DEFAULT true,
  can_edit     boolean NOT NULL DEFAULT false,
  updated_by   uuid REFERENCES crm.app_user(id),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (role, entity, field, zone)
);

-- Gmail connection per BDR (M3-1). Store tokens encrypted (Supabase Vault or app-side AES).
CREATE TABLE crm.user_mailbox (
  user_id                  uuid PRIMARY KEY REFERENCES crm.app_user(id),
  gmail_address            text NOT NULL UNIQUE,
  refresh_token_encrypted  bytea,
  status                   text NOT NULL DEFAULT 'CONNECTED' CHECK (status IN ('CONNECTED','EXPIRED','REVOKED')),
  daily_send_cap           integer NOT NULL DEFAULT 80,
  min_gap_seconds          integer NOT NULL DEFAULT 90,
  connected_at             timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now()
);

-- =====================================================================
-- 2. ADMIN SETTINGS & REFERENCE DATA (all editable without code, M10-1)
-- =====================================================================

-- Generic key/value settings: send window, small-share limit, digest time, etc.
CREATE TABLE crm.app_setting (
  key          text PRIMARY KEY,
  value        jsonb NOT NULL,
  description  text,
  updated_by   uuid REFERENCES crm.app_user(id),
  updated_at   timestamptz NOT NULL DEFAULT now()
);

-- One table for every admin-editable picklist.
CREATE TABLE crm.picklist_value (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  picklist_key  text NOT NULL CHECK (picklist_key IN (
                   'INDUSTRY','LEAD_SOURCE','INACTIVE_REASON','REACTIVATION_REASON',
                   'CALL_OUTCOME','STAGNATION_REASON','LOST_REASON','SQL_REJECTION_REASON',
                   'REASSIGNMENT_REASON','BANT_BUDGET','BANT_AUTHORITY','BANT_NEED','BANT_TIMELINE')),
  code          text NOT NULL,
  label         text NOT NULL,
  sort_order    integer NOT NULL DEFAULT 0,
  is_active     boolean NOT NULL DEFAULT true,
  requires_revisit_date boolean NOT NULL DEFAULT false,   -- used by INACTIVE_REASON
  requires_remark       boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (picklist_key, code)
);

-- Cities with ICP priority (M4: P0 Bengaluru + Delhi NCR, P1 Chennai/Mumbai).
CREATE TABLE crm.city (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name          text NOT NULL UNIQUE,
  region        text,                         -- 'Delhi NCR' groups Delhi, Gurugram, Noida...
  icp_priority  text NOT NULL DEFAULT 'NONE' CHECK (icp_priority IN ('P0','P1','NONE')),
  is_active     boolean NOT NULL DEFAULT true
);

-- Size buckets by license potential (M1-4). Bounds editable by admin.
CREATE TABLE crm.size_bucket (
  code          text PRIMARY KEY CHECK (code IN ('SMALL','MEDIUM','LARGE','ENTERPRISE')),
  label         text NOT NULL,
  min_licenses  integer NOT NULL,
  max_licenses  integer,                       -- null = no upper bound
  sort_order    integer NOT NULL,
  CHECK (max_licenses IS NULL OR max_licenses >= min_licenses)
);

-- Designation -> stakeholder level L1..L5 + right-POC flag (M1-5, Appendix B).
CREATE TABLE crm.designation_map (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  designation              text NOT NULL,
  designation_normalized   text GENERATED ALWAYS AS (lower(regexp_replace(btrim(designation), '\s+', ' ', 'g'))) STORED,
  level                    smallint NOT NULL CHECK (level BETWEEN 1 AND 5),
  department               text NOT NULL CHECK (department IN ('FINANCE','HR','PAYROLL','OTHER')),
  priority_rank            smallint NOT NULL DEFAULT 8 CHECK (priority_rank BETWEEN 1 AND 8),  -- 1 CFO ... 8 Other
  is_right_poc             boolean NOT NULL DEFAULT true,
  right_poc_min_bucket     text REFERENCES crm.size_bucket(code),  -- e.g. 'LARGE': right POC only for Large+ accounts
  created_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (designation_normalized)
);

-- Configurable ICP criteria (M4-2). config holds the parameters, e.g.
-- {"min_licenses":251,"small_share_limit":0.2} or {"direct_levels":[1,2],"referred_levels":[3,4,5]}
CREATE TABLE crm.icp_rule (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code         text NOT NULL UNIQUE CHECK (code IN ('RIGHT_STAKEHOLDER','LICENSES','LOCATION','BANT')),
  name         text NOT NULL,
  description  text,
  config       jsonb NOT NULL DEFAULT '{}'::jsonb,
  is_active    boolean NOT NULL DEFAULT true,
  updated_by   uuid REFERENCES crm.app_user(id),
  updated_at   timestamptz NOT NULL DEFAULT now()
);

-- What each call outcome does (Reference lists: call outcomes and their rules, M2-3).
CREATE TABLE crm.call_outcome_rule (
  outcome_id               uuid PRIMARY KEY REFERENCES crm.picklist_value(id),
  sets_engagement_status   text CHECK (sets_engagement_status IN (
                              'NOT_CONTACTED','CONTACTED','CONNECTED','MEETING_SCHEDULED','MEETING_COMPLETED',
                              'FOLLOW_UP_PENDING','UNRESPONSIVE','NOT_RELEVANT','REFERRED')),  -- null = no change
  next_task_type           text,                 -- 'CALL', 'SEND_TEMPLATE', 'FIND_NUMBER' ... null = none
  next_task_offset_wdays   smallint,             -- working days until the task is due
  requires_date            boolean NOT NULL DEFAULT false,   -- "Call Later" needs a chosen date
  requires_reason          boolean NOT NULL DEFAULT false,
  max_attempts             smallint,             -- e.g. 6 tries for Did Not Pick
  after_max_status         text,                 -- e.g. 'UNRESPONSIVE'
  revisit_days             smallint,             -- 30 / 90 day revisit
  offer_sequence           boolean NOT NULL DEFAULT false,   -- M3-7
  marks_phone_invalid      boolean NOT NULL DEFAULT false,
  sets_icp_fit_no          boolean NOT NULL DEFAULT false,
  opens_l1_booking         boolean NOT NULL DEFAULT false,
  creates_referral         boolean NOT NULL DEFAULT false
);

-- Per-stage thresholds: 60-day inactivity flag, stagnation days (M8-1, M6-7).
CREATE TABLE crm.stage_threshold (
  stage                text PRIMARY KEY CHECK (stage IN ('PROSPECT','SQL','L1','MULTITHREADING','L2','L3','NEGOTIATION')),
  inactivity_flag_days integer NOT NULL DEFAULT 60,
  stagnation_days      integer,              -- require stagnation reason after N days (Large/Enterprise)
  follow_up_interval_days integer NOT NULL DEFAULT 7
);

-- Stage checklist definitions (M6-9, M6-10).
CREATE TABLE crm.stage_checklist_item (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  stage                text NOT NULL CHECK (stage IN ('L1','L2','L3')),
  code                 text NOT NULL,
  label                text NOT NULL,
  sort_order           integer NOT NULL DEFAULT 0,
  is_exit_step         boolean NOT NULL DEFAULT false,   -- L1 'documents shared', L2 'commercials sent'
  requires_attachment  boolean NOT NULL DEFAULT false,
  is_active            boolean NOT NULL DEFAULT true,
  UNIQUE (stage, code)
);

-- =====================================================================
-- 3. ACCOUNTS & STAKEHOLDERS  (company-centric core)
-- =====================================================================
CREATE TABLE crm.account (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name                  text NOT NULL,
  name_normalized       text GENERATED ALWAYS AS (lower(regexp_replace(btrim(name), '\s+', ' ', 'g'))) STORED,
  domain                text,                -- normalized by app: lower, no scheme / www / path
  website               text,
  industry_id           uuid REFERENCES crm.picklist_value(id),
  sub_industry          text,
  city_id               uuid REFERENCES crm.city(id),
  employee_count        integer CHECK (employee_count >= 0),
  taxable_employee_count integer CHECK (taxable_employee_count >= 0),
  license_potential     integer CHECK (license_potential >= 0),
  size_bucket_code      text REFERENCES crm.size_bucket(code),      -- set by trigger from license_potential
  lead_source_id        uuid REFERENCES crm.picklist_value(id),
  apollo_id             text,

  -- ownership
  ae_owner_id           uuid REFERENCES crm.app_user(id),
  sql_credit_bdr_id     uuid REFERENCES crm.app_user(id),           -- BDR whose stakeholder led to L1 (M2-10)

  -- lifecycle
  lifecycle_stage       text NOT NULL DEFAULT 'PROSPECT' CHECK (lifecycle_stage IN (
                          'PROSPECT','SQL','L1','MULTITHREADING','L2','L3','NEGOTIATION','WON','LOST','IMPLEMENTATION')),
  visibility_zone       text NOT NULL DEFAULT 'PROSPECTING' CHECK (visibility_zone IN ('PROSPECTING','PIPELINE')),
  pipeline_since        timestamptz,                                -- when L1 was first booked
  account_status        text NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE','INACTIVE')),
  inactive_reason_id    uuid REFERENCES crm.picklist_value(id),
  inactive_remark       text,
  inactive_since        timestamptz,
  revisit_on            date,
  icp_fit               text NOT NULL DEFAULT 'UNKNOWN' CHECK (icp_fit IN ('YES','NO','UNKNOWN')),
  intent_rating         text CHECK (intent_rating IN ('LOW','MEDIUM','HIGH')),
  intent_reason         text,

  -- hygiene
  next_step             text,
  next_step_due         date,
  last_activity_at      timestamptz,
  review_flagged_at     timestamptz,                                -- "To be moved" queue (M8-1)

  created_by            uuid REFERENCES crm.app_user(id),
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  version               integer NOT NULL DEFAULT 0,

  CONSTRAINT inactive_needs_reason CHECK (
    account_status = 'ACTIVE' OR (inactive_reason_id IS NOT NULL AND inactive_since IS NOT NULL)),
  CONSTRAINT pipeline_from_l1 CHECK (
    visibility_zone = 'PROSPECTING' OR pipeline_since IS NOT NULL)
);
CREATE UNIQUE INDEX ux_account_name   ON crm.account (name_normalized);
CREATE UNIQUE INDEX ux_account_domain ON crm.account (domain) WHERE domain IS NOT NULL;
CREATE INDEX ix_account_ae_status     ON crm.account (ae_owner_id, account_status, lifecycle_stage);
CREATE INDEX ix_account_revisit       ON crm.account (revisit_on) WHERE account_status = 'INACTIVE';
CREATE INDEX ix_account_flagged       ON crm.account (review_flagged_at) WHERE review_flagged_at IS NOT NULL;
CREATE INDEX ix_account_name_prefix ON crm.account (name_normalized text_pattern_ops);  -- prefix search; add pg_trgm for fuzzy

-- BDRs working an account. Several BDRs per account (M2-9); a row is created when a
-- BDR adds their first stakeholder there. Drives prospecting-zone privacy.
CREATE TABLE crm.account_bdr (
  account_id   uuid NOT NULL REFERENCES crm.account(id),
  bdr_id       uuid NOT NULL REFERENCES crm.app_user(id),
  role         text NOT NULL DEFAULT 'CONTRIBUTOR' CHECK (role IN ('SOURCER','CONTRIBUTOR')),  -- SOURCER = got SQL/L1 credit
  joined_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, bdr_id)
);
CREATE INDEX ix_account_bdr_bdr ON crm.account_bdr (bdr_id);

CREATE TABLE crm.stakeholder (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id            uuid NOT NULL REFERENCES crm.account(id),
  bdr_owner_id          uuid REFERENCES crm.app_user(id),            -- one BDR owner per stakeholder (M2-10)
  first_name            text NOT NULL,
  last_name             text,
  designation           text,                                          -- raw title as captured
  designation_map_id    uuid REFERENCES crm.designation_map(id),       -- null = unmapped -> review queue
  level                 smallint CHECK (level BETWEEN 1 AND 5),        -- copied from map, admin can override
  department            text NOT NULL DEFAULT 'OTHER' CHECK (department IN ('FINANCE','HR','PAYROLL','OTHER')),
  buying_role           text CHECK (buying_role IN ('DECISION_MAKER','ECONOMIC_BUYER','INFLUENCER','EXECUTOR')),
  is_right_poc          boolean NOT NULL DEFAULT false,
  is_primary_spoc       boolean NOT NULL DEFAULT false,
  is_decision_maker     boolean NOT NULL DEFAULT false,
  engagement_status     text NOT NULL DEFAULT 'NOT_CONTACTED' CHECK (engagement_status IN (
                          'NOT_CONTACTED','CONTACTED','CONNECTED','MEETING_SCHEDULED','MEETING_COMPLETED',
                          'FOLLOW_UP_PENDING','UNRESPONSIVE','NOT_RELEVANT','REFERRED')),
  connection_type       text CHECK (connection_type IN ('DIRECT_SENIOR','JUNIOR_VIA_SENIOR_REFERRAL','DIRECT_JUNIOR')),
  referred_by_id        uuid REFERENCES crm.stakeholder(id),           -- referral chain (M4-6)
  linkedin_url          text,                                          -- normalized by app (no query params)
  do_not_contact        boolean NOT NULL DEFAULT false,
  dnc_reason            text,
  call_attempts         smallint NOT NULL DEFAULT 0,                   -- resets on connect
  last_interaction_at   timestamptz,
  next_follow_up_at     timestamptz,
  created_by            uuid REFERENCES crm.app_user(id),
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  version               integer NOT NULL DEFAULT 0,
  CHECK (referred_by_id IS NULL OR referred_by_id <> id)
);
CREATE INDEX ix_stakeholder_account   ON crm.stakeholder (account_id);
CREATE INDEX ix_stakeholder_bdr       ON crm.stakeholder (bdr_owner_id);
CREATE INDEX ix_stakeholder_followup  ON crm.stakeholder (next_follow_up_at) WHERE next_follow_up_at IS NOT NULL;
CREATE UNIQUE INDEX ux_stakeholder_linkedin ON crm.stakeholder (linkedin_url) WHERE linkedin_url IS NOT NULL;
CREATE UNIQUE INDEX ux_one_primary_spoc ON crm.stakeholder (account_id) WHERE is_primary_spoc;

CREATE TABLE crm.stakeholder_email (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  stakeholder_id   uuid NOT NULL REFERENCES crm.stakeholder(id) ON DELETE CASCADE,
  email            text NOT NULL,
  email_normalized text GENERATED ALWAYS AS (lower(btrim(email))) STORED,
  is_primary       boolean NOT NULL DEFAULT false,
  is_valid         boolean NOT NULL DEFAULT true,
  bounced_at       timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_email ON crm.stakeholder_email (email_normalized);
CREATE UNIQUE INDEX ux_one_primary_email ON crm.stakeholder_email (stakeholder_id) WHERE is_primary;

CREATE TABLE crm.stakeholder_phone (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  stakeholder_id   uuid NOT NULL REFERENCES crm.stakeholder(id) ON DELETE CASCADE,
  phone_raw        text NOT NULL,
  phone_e164       text NOT NULL,                     -- normalized, e.g. +919876543210
  is_primary       boolean NOT NULL DEFAULT false,
  is_whatsapp      boolean NOT NULL DEFAULT false,
  is_valid         boolean NOT NULL DEFAULT true,     -- "Wrong Number" marks false, stakeholder stays open (M1-3)
  invalid_marked_at timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now()
);
-- Not unique: switchboard numbers are shared. Duplicate check is a lookup + merge prompt.
CREATE INDEX ix_phone_e164 ON crm.stakeholder_phone (phone_e164);
CREATE UNIQUE INDEX ux_one_primary_phone ON crm.stakeholder_phone (stakeholder_id) WHERE is_primary;

-- =====================================================================
-- 4. OPPORTUNITY, SQL, MEETINGS
-- =====================================================================
CREATE TABLE crm.opportunity (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id            uuid NOT NULL REFERENCES crm.account(id),
  is_primary            boolean NOT NULL DEFAULT true,     -- v1: one per account; model allows more
  stage                 text NOT NULL DEFAULT 'SQL' CHECK (stage IN (
                          'SQL','L1','MULTITHREADING','L2','L3','NEGOTIATION','WON','LOST','IMPLEMENTATION')),
  stage_entered_at      timestamptz NOT NULL DEFAULT now(),
  temperature           text CHECK (temperature IN ('HOT','WARM','COLD')),
  expected_mrr          numeric(14,2) CHECK (expected_mrr >= 0),
  license_count         integer CHECK (license_count >= 0),
  stagnation_reason_id  uuid REFERENCES crm.picklist_value(id),
  stagnation_note       text,
  next_step             text,
  next_step_due         date,
  expected_close_date   date,
  closed_at             timestamptz,
  lost_reason_id        uuid REFERENCES crm.picklist_value(id),
  outcome_note          text,
  implementation_started_at timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  version               integer NOT NULL DEFAULT 0,
  CONSTRAINT lost_needs_reason CHECK (stage <> 'LOST' OR lost_reason_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_one_primary_opportunity ON crm.opportunity (account_id) WHERE is_primary;
CREATE INDEX ix_opportunity_stage ON crm.opportunity (stage);

-- One row per SQL submission. Stores what the BDR SUBMITTED (immutable snapshot);
-- AE corrections after L1 go to qualification_correction (M4-9).
CREATE TABLE crm.sql_submission (
  id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id                uuid NOT NULL REFERENCES crm.account(id),
  opportunity_id            uuid REFERENCES crm.opportunity(id),   -- set once validated
  attempt_no                smallint NOT NULL DEFAULT 1,
  status                    text NOT NULL DEFAULT 'PENDING_QUALIFICATION' CHECK (status IN (
                              'PENDING_QUALIFICATION','QUALIFIED_SQL','REJECTED_NOT_ICP',
                              'PENDING_SENIOR_CONNECTION','MEETING_BOOKED','NO_SHOW','DISQUALIFIED')),
  submitted_by              uuid NOT NULL REFERENCES crm.app_user(id),
  submitted_at              timestamptz,
  qualifying_stakeholder_id uuid REFERENCES crm.stakeholder(id),
  referring_stakeholder_id  uuid REFERENCES crm.stakeholder(id),   -- required when qualifying level is L3..L5
  connection_type           text CHECK (connection_type IN ('DIRECT_SENIOR','JUNIOR_VIA_SENIOR_REFERRAL','DIRECT_JUNIOR')),
  -- snapshot of BDR's numbers
  license_potential         integer,
  size_bucket_code          text REFERENCES crm.size_bucket(code),
  city_id                   uuid REFERENCES crm.city(id),
  location_priority         text CHECK (location_priority IN ('P0','P1','NONE')),
  business_relevance        text CHECK (business_relevance IN ('FBP','PAYROLL','BOTH')),
  intent_rating             text CHECK (intent_rating IN ('LOW','MEDIUM','HIGH')),
  intent_reason             text,
  intent_confirmed_by       uuid REFERENCES crm.app_user(id),       -- manager confirms P1 city intent (M4-7)
  -- small-account exception (M4-11)
  is_small_exception        boolean NOT NULL DEFAULT false,
  small_exception_reason    text,
  small_approved_by         uuid REFERENCES crm.app_user(id),
  small_approved_at         timestamptz,
  -- BANT (M4-10)
  bant_budget_id            uuid REFERENCES crm.picklist_value(id),
  bant_budget_note          text,
  bant_authority_id         uuid REFERENCES crm.picklist_value(id),
  bant_authority_note       text,
  bant_need_id              uuid REFERENCES crm.picklist_value(id),
  bant_need_note            text,
  bant_timeline_id          uuid REFERENCES crm.picklist_value(id),
  bant_timeline_note        text,
  -- validation
  validated_by              uuid REFERENCES crm.app_user(id),
  validated_at              timestamptz,
  rejection_reason_id       uuid REFERENCES crm.picklist_value(id),
  rejection_note            text,
  created_at                timestamptz NOT NULL DEFAULT now(),
  updated_at                timestamptz NOT NULL DEFAULT now(),
  version                   integer NOT NULL DEFAULT 0,
  UNIQUE (account_id, attempt_no),
  CONSTRAINT small_needs_reason CHECK (NOT is_small_exception OR small_exception_reason IS NOT NULL),
  CONSTRAINT rejected_needs_reason CHECK (status <> 'REJECTED_NOT_ICP' OR rejection_reason_id IS NOT NULL),
  CONSTRAINT bant_complete_on_submit CHECK (
    submitted_at IS NULL OR (bant_budget_id IS NOT NULL AND bant_authority_id IS NOT NULL
                             AND bant_need_id IS NOT NULL AND bant_timeline_id IS NOT NULL))
);
CREATE INDEX ix_sql_queue ON crm.sql_submission (status, submitted_at);
CREATE INDEX ix_sql_bdr   ON crm.sql_submission (submitted_by, submitted_at);

-- Pass/fail per ICP criterion, computed at submit time (M4-1, M4-2).
CREATE TABLE crm.sql_criterion_result (
  sql_submission_id  uuid NOT NULL REFERENCES crm.sql_submission(id) ON DELETE CASCADE,
  icp_rule_id        uuid NOT NULL REFERENCES crm.icp_rule(id),
  passed             boolean NOT NULL,
  detail             text,                       -- e.g. 'Level L4, referred by CFO (L1)'
  PRIMARY KEY (sql_submission_id, icp_rule_id)
);

-- AE corrections after L1; keeps BDR original for SQL-quality reports (M4-9).
CREATE TABLE crm.qualification_correction (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sql_submission_id  uuid NOT NULL REFERENCES crm.sql_submission(id),
  field_name         text NOT NULL,              -- 'license_potential', 'city_id', 'bant_need_id', ...
  bdr_value          text,
  corrected_value    text,
  note               text,
  corrected_by       uuid NOT NULL REFERENCES crm.app_user(id),
  corrected_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_qcorr_sql ON crm.qualification_correction (sql_submission_id);

CREATE TABLE crm.meeting (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id            uuid NOT NULL REFERENCES crm.account(id),
  opportunity_id        uuid REFERENCES crm.opportunity(id),
  level                 text NOT NULL CHECK (level IN ('L1','L2','L3','OTHER')),
  also_completes_l2     boolean NOT NULL DEFAULT false,   -- one meeting tagged L1 + L2 (M6-11)
  status                text NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN (
                          'SCHEDULED','RESCHEDULED','COMPLETED','NO_SHOW','CANCELLED')),
  outcome               text CHECK (outcome IN (
                          'COMPLETED_QUALIFIED','COMPLETED_FOLLOW_UP','COMPLETED_NOT_INTERESTED',
                          'NO_SHOW','RESCHEDULED','DISQUALIFIED')),
  booked_by             uuid NOT NULL REFERENCES crm.app_user(id),
  booked_on             timestamptz NOT NULL DEFAULT now(),
  ae_id                 uuid REFERENCES crm.app_user(id),           -- AE conducting the meeting
  scheduled_start       timestamptz NOT NULL,
  scheduled_end         timestamptz,
  meeting_link          text,
  notes                 text,
  next_step             text,
  next_step_due         date,
  rescheduled_from_id   uuid REFERENCES crm.meeting(id),
  outcome_recorded_by   uuid REFERENCES crm.app_user(id),
  outcome_recorded_at   timestamptz,
  calendar_event_id     text,                                       -- Google Calendar (phase 3)
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  version               integer NOT NULL DEFAULT 0,
  CHECK (NOT also_completes_l2 OR level = 'L1'),
  CHECK (scheduled_end IS NULL OR scheduled_end > scheduled_start)
);
CREATE INDEX ix_meeting_account ON crm.meeting (account_id, scheduled_start);
CREATE INDEX ix_meeting_ae_open ON crm.meeting (ae_id, scheduled_start) WHERE outcome IS NULL;

CREATE TABLE crm.meeting_attendee (
  meeting_id      uuid NOT NULL REFERENCES crm.meeting(id) ON DELETE CASCADE,
  stakeholder_id  uuid NOT NULL REFERENCES crm.stakeholder(id),
  attended        boolean,
  PRIMARY KEY (meeting_id, stakeholder_id)
);

-- Files in Supabase Storage (documents shared after L1, commercials after L2).
CREATE TABLE crm.attachment (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id      uuid NOT NULL REFERENCES crm.account(id),
  opportunity_id  uuid REFERENCES crm.opportunity(id),
  meeting_id      uuid REFERENCES crm.meeting(id),
  kind            text NOT NULL CHECK (kind IN ('DOCUMENTS_SHARED','COMMERCIAL','CONTRACT','OTHER')),
  file_name       text NOT NULL,
  storage_path    text,                 -- bucket/key in Supabase Storage
  external_url    text,                 -- or a shared link
  mime_type       text,
  size_bytes      bigint,
  uploaded_by     uuid NOT NULL REFERENCES crm.app_user(id),
  uploaded_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (storage_path IS NOT NULL OR external_url IS NOT NULL)
);

-- Ticked checklist items per opportunity (M6-9/M6-10).
CREATE TABLE crm.opportunity_checklist (
  opportunity_id     uuid NOT NULL REFERENCES crm.opportunity(id),
  checklist_item_id  uuid NOT NULL REFERENCES crm.stage_checklist_item(id),
  is_done            boolean NOT NULL DEFAULT false,
  done_at            timestamptz,
  done_by            uuid REFERENCES crm.app_user(id),
  attachment_id      uuid REFERENCES crm.attachment(id),
  note               text,
  PRIMARY KEY (opportunity_id, checklist_item_id)
);

-- =====================================================================
-- 5. EMAIL TEMPLATES & SEQUENCES  (M3)
-- =====================================================================
CREATE TABLE crm.email_template (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code         text NOT NULL UNIQUE,          -- INTRO, FOLLOWUP1, FOLLOWUP2, REMINDER
  name         text NOT NULL,
  subject      text NOT NULL,
  body         text NOT NULL,                 -- with {{merge fields}} and {{snippets}}
  is_internal  boolean NOT NULL DEFAULT false,
  is_active    boolean NOT NULL DEFAULT true,
  updated_by   uuid REFERENCES crm.app_user(id),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      integer NOT NULL DEFAULT 0
);

-- Admin-managed blocks; variant chosen by department for {{Persona Pitch}} (M3-5).
CREATE TABLE crm.email_snippet (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code         text NOT NULL,                 -- CALL_LINE, PERSONA_PITCH, CUSTOMER_PROOF, SIGNATURE
  variant      text NOT NULL DEFAULT 'DEFAULT' CHECK (variant IN ('DEFAULT','FINANCE','HR','PAYROLL')),
  content      text NOT NULL,
  is_active    boolean NOT NULL DEFAULT true,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (code, variant)
);

CREATE TABLE crm.email_sequence (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name         text NOT NULL UNIQUE,
  is_default   boolean NOT NULL DEFAULT false,
  is_active    boolean NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_one_default_sequence ON crm.email_sequence ((true)) WHERE is_default;

CREATE TABLE crm.sequence_step (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sequence_id     uuid NOT NULL REFERENCES crm.email_sequence(id) ON DELETE CASCADE,
  step_no         smallint NOT NULL CHECK (step_no >= 1),
  template_id     uuid NOT NULL REFERENCES crm.email_template(id),
  day_offset      smallint NOT NULL CHECK (day_offset >= 0),  -- 0, 2, 5, 7
  in_same_thread  boolean NOT NULL DEFAULT true,
  recipient       text NOT NULL DEFAULT 'STAKEHOLDER' CHECK (recipient IN ('STAKEHOLDER','BDR')),
  creates_task_type text,                                    -- Reminder step creates a CALL task
  UNIQUE (sequence_id, step_no)
);

CREATE TABLE crm.sequence_enrollment (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sequence_id        uuid NOT NULL REFERENCES crm.email_sequence(id),
  stakeholder_id     uuid NOT NULL REFERENCES crm.stakeholder(id),
  email_id           uuid NOT NULL REFERENCES crm.stakeholder_email(id),
  enrolled_by        uuid NOT NULL REFERENCES crm.app_user(id),    -- sending BDR; mail goes from their mailbox
  status             text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','PAUSED','COMPLETED','STOPPED')),
  current_step_no    smallint NOT NULL DEFAULT 1,
  started_from_call  boolean NOT NULL DEFAULT false,               -- include {{Call Line}}
  gmail_thread_id    text,
  last_sent_at       timestamptz,
  next_send_at       timestamptz,
  reply_received_at  timestamptz,
  stop_reason        text CHECK (stop_reason IN ('REPLY','BOUNCE','OPT_OUT','L1_BOOKED','MANUAL','COMPLETED')),
  started_at         timestamptz NOT NULL DEFAULT now(),
  ended_at           timestamptz,
  CHECK (status NOT IN ('COMPLETED','STOPPED') OR stop_reason IS NOT NULL)
);
-- A stakeholder can be in only one running sequence (M3-2).
CREATE UNIQUE INDEX ux_one_running_sequence ON crm.sequence_enrollment (stakeholder_id) WHERE status IN ('RUNNING','PAUSED');
CREATE INDEX ix_enrollment_due ON crm.sequence_enrollment (next_send_at) WHERE status = 'RUNNING';

CREATE TABLE crm.email_message (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  enrollment_id      uuid REFERENCES crm.sequence_enrollment(id),
  sequence_step_id   uuid REFERENCES crm.sequence_step(id),
  stakeholder_id     uuid REFERENCES crm.stakeholder(id),
  sender_user_id     uuid NOT NULL REFERENCES crm.app_user(id),
  direction          text NOT NULL CHECK (direction IN ('OUTBOUND','INBOUND')),
  to_address         text NOT NULL,
  subject            text,
  body_rendered      text,
  status             text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','SENT','FAILED','BOUNCED','RECEIVED')),
  gmail_message_id   text UNIQUE,
  gmail_thread_id    text,
  scheduled_for      timestamptz,
  sent_at            timestamptz,
  error              text,
  created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_thread ON crm.email_message (gmail_thread_id);
CREATE INDEX ix_email_sender_day ON crm.email_message (sender_user_id, sent_at);

-- =====================================================================
-- 6. ACTIVITIES & TASKS
-- =====================================================================
CREATE TABLE crm.activity (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id          uuid NOT NULL REFERENCES crm.account(id),
  stakeholder_id      uuid REFERENCES crm.stakeholder(id),
  opportunity_id      uuid REFERENCES crm.opportunity(id),
  meeting_id          uuid REFERENCES crm.meeting(id),
  email_message_id    uuid REFERENCES crm.email_message(id),
  phone_id            uuid REFERENCES crm.stakeholder_phone(id),
  type                text NOT NULL CHECK (type IN ('CALL','EMAIL','WHATSAPP','LINKEDIN','OFFICE_VISIT','NOTE','MEETING','REFERRAL','STATUS_CHANGE')),
  call_outcome_id     uuid REFERENCES crm.picklist_value(id),
  remarks             text,
  occurred_at         timestamptz NOT NULL DEFAULT now(),
  logged_by           uuid NOT NULL REFERENCES crm.app_user(id),
  zone_at_time        text NOT NULL CHECK (zone_at_time IN ('PROSPECTING','PIPELINE')),  -- privacy on the timeline (M7-7)
  created_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (type <> 'CALL' OR call_outcome_id IS NOT NULL)
);
CREATE INDEX ix_activity_account_time     ON crm.activity (account_id, occurred_at DESC);
CREATE INDEX ix_activity_stakeholder_time ON crm.activity (stakeholder_id, occurred_at DESC);
CREATE INDEX ix_activity_user_time        ON crm.activity (logged_by, occurred_at DESC);

CREATE TABLE crm.task (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id         uuid NOT NULL REFERENCES crm.account(id),
  stakeholder_id     uuid REFERENCES crm.stakeholder(id),
  opportunity_id     uuid REFERENCES crm.opportunity(id),
  meeting_id         uuid REFERENCES crm.meeting(id),
  type               text NOT NULL CHECK (type IN (
                        'CALL','FOLLOW_UP','SEND_TEMPLATE','FIND_NUMBER','RESCHEDULE_MEETING',
                        'RECORD_OUTCOME','REVIVAL','REVIEW_INACTIVE','MULTITHREAD','OTHER')),
  title              text NOT NULL,
  due_at             timestamptz NOT NULL,
  assignee_id        uuid NOT NULL REFERENCES crm.app_user(id),
  status             text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DONE','CANCELLED')),
  source             text NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','CALL_RULE','SEQUENCE','SYSTEM')),
  source_activity_id uuid REFERENCES crm.activity(id),
  created_by         uuid REFERENCES crm.app_user(id),       -- null when created by the system
  completed_at       timestamptz,
  completed_by       uuid REFERENCES crm.app_user(id),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_task_my_day ON crm.task (assignee_id, due_at) WHERE status = 'OPEN';
CREATE INDEX ix_task_account ON crm.task (account_id);

-- =====================================================================
-- 7. HISTORY & AUDIT  (every owner / stage / status change writes a row)
-- =====================================================================
-- AE and BDR (prospect) ownership changes (BRD 7.3, M5-4).
CREATE TABLE crm.assignment_history (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id         uuid NOT NULL REFERENCES crm.account(id),
  owner_type         text NOT NULL CHECK (owner_type IN ('AE','BDR')),
  stakeholder_id     uuid REFERENCES crm.stakeholder(id),       -- for BDR reassignment of one stakeholder
  previous_owner_id  uuid REFERENCES crm.app_user(id),
  new_owner_id       uuid NOT NULL REFERENCES crm.app_user(id),
  method             text NOT NULL DEFAULT 'MANUAL' CHECK (method IN ('MANUAL','ROUND_ROBIN','CAPACITY','USER_DEACTIVATED','MIGRATION')),
  recommended_owner_id uuid REFERENCES crm.app_user(id),        -- what the system suggested
  reason_id          uuid REFERENCES crm.picklist_value(id),
  reason_note        text,
  assigned_by        uuid NOT NULL REFERENCES crm.app_user(id),
  assigned_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reassignment_needs_reason CHECK (previous_owner_id IS NULL OR reason_id IS NOT NULL OR reason_note IS NOT NULL)
);
CREATE INDEX ix_assign_account ON crm.assignment_history (account_id, assigned_at);

-- Stage transitions -> time-in-stage and conversion reports.
CREATE TABLE crm.stage_history (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  opportunity_id  uuid NOT NULL REFERENCES crm.opportunity(id),
  account_id      uuid NOT NULL REFERENCES crm.account(id),
  from_stage      text,
  to_stage        text NOT NULL,
  meeting_id      uuid REFERENCES crm.meeting(id),     -- the meeting whose exit step moved it
  changed_by      uuid REFERENCES crm.app_user(id),
  changed_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_stage_hist_opp ON crm.stage_history (opportunity_id, changed_at);

-- Active <-> Inactive moves and reactivations (M8-2, M8-7).
CREATE TABLE crm.account_status_history (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id        uuid NOT NULL REFERENCES crm.account(id),
  from_status       text NOT NULL CHECK (from_status IN ('ACTIVE','INACTIVE')),
  to_status         text NOT NULL CHECK (to_status IN ('ACTIVE','INACTIVE')),
  stage_at_change   text NOT NULL,
  reason_id         uuid REFERENCES crm.picklist_value(id),   -- INACTIVE_REASON or REACTIVATION_REASON
  remark            text,
  revisit_on        date,
  returned_to_stage text,                                     -- on reactivation: last stage or L1
  approved_by       uuid REFERENCES crm.app_user(id),         -- when approval setting is on (phase 2)
  changed_by        uuid NOT NULL REFERENCES crm.app_user(id),
  changed_at        timestamptz NOT NULL DEFAULT now(),
  CHECK (from_status <> to_status)
);
CREATE INDEX ix_status_hist_account ON crm.account_status_history (account_id, changed_at);

CREATE TABLE crm.temperature_history (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  opportunity_id  uuid NOT NULL REFERENCES crm.opportunity(id),
  from_value      text,
  to_value        text NOT NULL CHECK (to_value IN ('HOT','WARM','COLD')),
  changed_by      uuid NOT NULL REFERENCES crm.app_user(id),
  changed_at      timestamptz NOT NULL DEFAULT now()
);

-- Generic field-level audit (M10-2). Written by the service layer
-- (or Hibernate Envers if you prefer: then drop this table).
CREATE TABLE crm.audit_log (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  entity_type  text NOT NULL,
  entity_id    uuid NOT NULL,
  action       text NOT NULL CHECK (action IN ('CREATE','UPDATE','MERGE','REASSIGN','STATUS','IMPORT')),
  field_name   text,
  old_value    text,
  new_value    text,
  changed_by   uuid REFERENCES crm.app_user(id),
  changed_at   timestamptz NOT NULL DEFAULT now(),
  context      jsonb
);
CREATE INDEX ix_audit_entity ON crm.audit_log (entity_type, entity_id, changed_at);

-- =====================================================================
-- 8. TARGETS, NOTIFICATIONS, IMPORTS
-- =====================================================================
CREATE TABLE crm.target (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL REFERENCES crm.app_user(id),
  period_type   text NOT NULL CHECK (period_type IN ('WEEK','MONTH','QUARTER')),
  period_start  date NOT NULL,
  metric        text NOT NULL CHECK (metric IN ('L1_BOOKED','L1_DONE','L2_DONE','L3_DONE','WON','MRR','SQL_APPROVED')),
  target_value  numeric(14,2) NOT NULL CHECK (target_value >= 0),
  set_by        uuid NOT NULL REFERENCES crm.app_user(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, period_type, period_start, metric)
);

CREATE TABLE crm.notification (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES crm.app_user(id),
  type         text NOT NULL,              -- SQL_SUBMITTED, ACCOUNT_ASSIGNED, MEETING_TOMORROW, ...
  title        text NOT NULL,
  body         text,
  entity_type  text,
  entity_id    uuid,
  channel      text NOT NULL DEFAULT 'IN_APP' CHECK (channel IN ('IN_APP','EMAIL','DIGEST')),
  read_at      timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_unread ON crm.notification (user_id, created_at DESC) WHERE read_at IS NULL;

-- CSV/XLSX imports and the one-time sheet migration (M1-1, Migration).
CREATE TABLE crm.import_job (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source         text NOT NULL,             -- 'APOLLO_CSV', 'ACCOUNTS_TRACKER', 'B2B_SHEET', 'MANUAL_UPLOAD'
  file_name      text,
  column_mapping jsonb,
  status         text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VALIDATING','READY','IMPORTING','DONE','FAILED')),
  total_rows     integer,
  created_rows   integer,
  merged_rows    integer,
  error_rows     integer,
  created_by     uuid NOT NULL REFERENCES crm.app_user(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  finished_at    timestamptz
);

CREATE TABLE crm.import_row (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  import_job_id      uuid NOT NULL REFERENCES crm.import_job(id) ON DELETE CASCADE,
  row_no             integer NOT NULL,
  raw                jsonb NOT NULL,
  result             text CHECK (result IN ('CREATED','MERGED','DUPLICATE','ERROR','SKIPPED','REVIEW')),
  matched_account_id uuid REFERENCES crm.account(id),
  matched_stakeholder_id uuid REFERENCES crm.stakeholder(id),
  message            text,
  UNIQUE (import_job_id, row_no)
);

-- =====================================================================
-- 9. TRIGGERS
-- =====================================================================
-- updated_at on mutable tables
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['app_user','account','stakeholder','opportunity','sql_submission','meeting','task']
  LOOP
    EXECUTE format('CREATE TRIGGER trg_%1$s_updated BEFORE UPDATE ON crm.%1$s
                    FOR EACH ROW EXECUTE FUNCTION crm.set_updated_at()', t);
  END LOOP;
END $$;

-- Size bucket from license_potential, using admin-editable bounds.
CREATE OR REPLACE FUNCTION crm.bucket_for(p_licenses integer) RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT code FROM crm.size_bucket
   WHERE p_licenses >= min_licenses AND (max_licenses IS NULL OR p_licenses <= max_licenses)
   ORDER BY sort_order LIMIT 1
$$;

CREATE OR REPLACE FUNCTION crm.account_set_size_bucket() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.size_bucket_code := CASE WHEN NEW.license_potential IS NULL THEN NULL
                               ELSE crm.bucket_for(NEW.license_potential) END;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_account_size_bucket
  BEFORE INSERT OR UPDATE OF license_potential ON crm.account
  FOR EACH ROW EXECUTE FUNCTION crm.account_set_size_bucket();

-- Run after an admin edits bucket bounds.
CREATE OR REPLACE FUNCTION crm.recompute_size_buckets() RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE n integer;
BEGIN
  UPDATE crm.account a SET size_bucket_code = crm.bucket_for(a.license_potential)
   WHERE a.license_potential IS NOT NULL
     AND a.size_bucket_code IS DISTINCT FROM crm.bucket_for(a.license_potential);
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- Opportunity.stage is the source of truth from SQL onwards; keep the account's
-- lifecycle_stage in step and write stage_history automatically.
-- AFTER for INSERT (FK in stage_history needs the row); BEFORE for UPDATE (to set stage_entered_at)
CREATE OR REPLACE FUNCTION crm.opportunity_stage_sync_after_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO crm.stage_history (opportunity_id, account_id, from_stage, to_stage, changed_at)
  VALUES (NEW.id, NEW.account_id, NULL, NEW.stage, NEW.stage_entered_at);
  IF NEW.is_primary THEN
    UPDATE crm.account SET lifecycle_stage = NEW.stage WHERE id = NEW.account_id;
  END IF;
  RETURN NULL;
END $$;
CREATE OR REPLACE FUNCTION crm.opportunity_stage_sync_before_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.stage IS DISTINCT FROM OLD.stage THEN
    NEW.stage_entered_at := now();
    INSERT INTO crm.stage_history (opportunity_id, account_id, from_stage, to_stage, changed_at)
    VALUES (NEW.id, NEW.account_id, OLD.stage, NEW.stage, NEW.stage_entered_at);
    IF NEW.is_primary THEN
      UPDATE crm.account SET lifecycle_stage = NEW.stage WHERE id = NEW.account_id;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_opp_stage_ins AFTER INSERT ON crm.opportunity
  FOR EACH ROW EXECUTE FUNCTION crm.opportunity_stage_sync_after_insert();
CREATE TRIGGER trg_opp_stage_upd BEFORE UPDATE OF stage ON crm.opportunity
  FOR EACH ROW EXECUTE FUNCTION crm.opportunity_stage_sync_before_update();

-- Any new activity bumps account / stakeholder recency and clears the "to be moved" flag.
CREATE OR REPLACE FUNCTION crm.activity_touch() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  UPDATE crm.account
     SET last_activity_at  = GREATEST(COALESCE(last_activity_at, NEW.occurred_at), NEW.occurred_at),
         review_flagged_at = NULL
   WHERE id = NEW.account_id;
  IF NEW.stakeholder_id IS NOT NULL THEN
    UPDATE crm.stakeholder
       SET last_interaction_at = GREATEST(COALESCE(last_interaction_at, NEW.occurred_at), NEW.occurred_at)
     WHERE id = NEW.stakeholder_id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_activity_touch AFTER INSERT ON crm.activity
  FOR EACH ROW EXECUTE FUNCTION crm.activity_touch();

-- =====================================================================
-- 10. VIEWS for queues & dashboards  (map as read-only @Immutable entities)
-- =====================================================================
-- AE capacity (BRD 7.1, M5-2). Weighted load: L2/L3/Negotiation count 1.5x (decision #8).
CREATE OR REPLACE VIEW crm.v_ae_capacity AS
SELECT u.id AS ae_id, u.full_name,
       count(a.id) FILTER (WHERE a.account_status = 'ACTIVE')                                   AS active_accounts,
       count(m.id)                                                                               AS l1_pending,
       count(a.id) FILTER (WHERE a.account_status = 'ACTIVE' AND a.lifecycle_stage = 'L2')        AS l2_pipeline,
       count(a.id) FILTER (WHERE a.account_status = 'ACTIVE' AND a.lifecycle_stage = 'L3')        AS l3_pipeline,
       count(a.id) FILTER (WHERE a.account_status = 'ACTIVE' AND a.lifecycle_stage = 'NEGOTIATION') AS negotiation,
       count(a.id) FILTER (WHERE a.account_status = 'INACTIVE')                                  AS inactive_accounts,
       count(a.id)                                                                               AS total_assigned,
       coalesce(sum(CASE WHEN a.id IS NULL OR a.account_status <> 'ACTIVE' THEN 0
                WHEN a.lifecycle_stage IN ('L2','L3','NEGOTIATION') THEN 1.5 ELSE 1 END), 0)    AS weighted_load
  FROM crm.app_user u
  LEFT JOIN crm.account a ON a.ae_owner_id = u.id
  LEFT JOIN LATERAL (
        SELECT mm.id FROM crm.meeting mm
         WHERE mm.account_id = a.id AND mm.level = 'L1' AND mm.status IN ('SCHEDULED','RESCHEDULED')
         LIMIT 1) m ON true
 WHERE u.role = 'AE' AND u.is_active
 GROUP BY u.id, u.full_name;

-- Designations with no map entry (M1-5 review list).
CREATE OR REPLACE VIEW crm.v_unmapped_designations AS
SELECT lower(regexp_replace(btrim(s.designation), '\s+', ' ', 'g')) AS designation_normalized,
       min(s.designation) AS example_title, count(*) AS stakeholders
  FROM crm.stakeholder s
 WHERE s.designation_map_id IS NULL AND s.designation IS NOT NULL
 GROUP BY 1;

-- BDR revival queue: inactive accounts whose revisit date has come (M8-5, M8-6).
CREATE OR REPLACE VIEW crm.v_revival_queue AS
SELECT a.id AS account_id, a.name, a.size_bucket_code, sb.sort_order AS size_rank,
       a.lifecycle_stage, a.inactive_reason_id, a.revisit_on, a.ae_owner_id
  FROM crm.account a
  LEFT JOIN crm.size_bucket sb ON sb.code = a.size_bucket_code
 WHERE a.account_status = 'INACTIVE' AND a.revisit_on <= current_date;

-- Key BRD report: accounts that finished L1 but never reached L2.
CREATE OR REPLACE VIEW crm.v_l1_not_progressed AS
SELECT a.id AS account_id, a.name, a.ae_owner_id, a.sql_credit_bdr_id, a.size_bucket_code,
       a.account_status, a.inactive_reason_id, o.stagnation_reason_id, a.last_activity_at,
       spoc.designation AS spoc_designation, spoc.engagement_status AS spoc_engagement,
       (SELECT count(*) FROM crm.stakeholder s WHERE s.account_id = a.id) > 1 AS multithreading_started,
       (SELECT min(s.next_follow_up_at) FROM crm.stakeholder s WHERE s.account_id = a.id AND s.next_follow_up_at >= now()) AS next_follow_up_at
  FROM crm.account a
  JOIN crm.opportunity o ON o.account_id = a.id AND o.is_primary
  LEFT JOIN crm.stakeholder spoc ON spoc.account_id = a.id AND spoc.is_primary_spoc
 WHERE EXISTS (SELECT 1 FROM crm.meeting m WHERE m.account_id = a.id AND m.level = 'L1' AND m.status = 'COMPLETED')
   AND NOT EXISTS (SELECT 1 FROM crm.stage_history h WHERE h.opportunity_id = o.id AND h.to_stage IN ('L2','L3','NEGOTIATION','WON','IMPLEMENTATION'));

-- =====================================================================
-- 11. SEED CONFIG (starting values from the PRD; admins edit later)
-- =====================================================================
INSERT INTO crm.size_bucket (code, label, min_licenses, max_licenses, sort_order) VALUES
 ('SMALL','Small',0,250,1), ('MEDIUM','Medium',251,500,2),
 ('LARGE','Large',501,1000,3), ('ENTERPRISE','Enterprise',1001,NULL,4);

INSERT INTO crm.city (name, region, icp_priority) VALUES
 ('Bengaluru','Bengaluru','P0'), ('Delhi','Delhi NCR','P0'), ('Gurugram','Delhi NCR','P0'),
 ('Noida','Delhi NCR','P0'), ('Faridabad','Delhi NCR','P0'), ('Ghaziabad','Delhi NCR','P0'),
 ('Chennai','Chennai','P1'), ('Mumbai','Mumbai','P1');

INSERT INTO crm.stage_threshold (stage, inactivity_flag_days, stagnation_days) VALUES
 ('PROSPECT',60,NULL), ('SQL',60,NULL), ('L1',60,30), ('MULTITHREADING',60,NULL),
 ('L2',60,NULL), ('L3',60,NULL), ('NEGOTIATION',60,NULL);

INSERT INTO crm.app_setting (key, value, description) VALUES
 ('send_window',          '{"days":[1,2,3,4,5],"start":"09:30","end":"18:30","tz":"Asia/Kolkata"}', 'Sequence sending window (M3-9)'),
 ('small_sql_share_limit','0.20', 'Max share of Small SQLs per BDR (M4-11)'),
 ('call_retry',           '{"every_working_days":2,"max_tries":6,"revisit_days":30}', 'Did Not Pick cadence (decision #7)'),
 ('capacity_weights',     '{"L2":1.5,"L3":1.5,"NEGOTIATION":1.5,"default":1}', 'AE capacity weighting (decision #8)'),
 ('require_inactive_approval', 'false', 'Manager approval to move to Inactive (M8-3, phase 2)'),
 ('daily_digest_time',    '"09:00"', 'Daily digest, IST');

INSERT INTO crm.icp_rule (code, name, config) VALUES
 ('RIGHT_STAKEHOLDER','Right stakeholder','{"direct_levels":[1,2],"referred_levels":[3,4,5],"referrer_levels":[1,2]}'),
 ('LICENSES','Licenses (taxable bracket)','{"min_licenses":251,"small_exception_share":0.20,"small_needs_approval":true}'),
 ('LOCATION','Location','{"pass":["P0"],"conditional":{"P1":"intent_rating = HIGH + reason + manager confirm"}}'),
 ('BANT','BANT','{"required":["budget","authority","need","timeline"]}');

INSERT INTO crm.picklist_value (picklist_key, code, label, sort_order, requires_revisit_date, requires_remark) VALUES
 ('INACTIVE_REASON','SPOC_UNRESPONSIVE','SPOC Unresponsive',1,true,false),
 ('INACTIVE_REASON','JUNIOR_NO_ACCESS','Junior SPOC – no access to decision maker',2,false,false),
 ('INACTIVE_REASON','DM_UNAVAILABLE','Decision maker unavailable',3,true,false),
 ('INACTIVE_REASON','NO_ALIGNMENT','No internal alignment / decision making',4,false,false),
 ('INACTIVE_REASON','NO_REQUIREMENT','No requirement',5,false,false),
 ('INACTIVE_REASON','BUDGET','Budget',6,false,false),
 ('INACTIVE_REASON','PRICING','Pricing',7,false,false),
 ('INACTIVE_REASON','PRODUCT_FIT','Product fit',8,false,false),
 ('INACTIVE_REASON','COMPETITOR','Competitor selected',9,false,false),
 ('INACTIVE_REASON','COMPLIANCE','Compliance / legal issue',10,false,false),
 ('INACTIVE_REASON','TIMELINE_DEFERRED','Timeline deferred',11,true,false),
 ('INACTIVE_REASON','ON_HOLD','On hold',12,true,false),
 ('INACTIVE_REASON','OTHER','Other',13,false,true),
 ('CALL_OUTCOME','DID_NOT_PICK','Did Not Pick',1,false,false),
 ('CALL_OUTCOME','SWITCH_OFF','Switch off',2,false,false),
 ('CALL_OUTCOME','INCOMING_BARRED','Incoming calls barred',3,false,false),
 ('CALL_OUTCOME','CALL_LATER','Call Later',4,false,false),
 ('CALL_OUTCOME','CALL_AFTER_MONTH','Call after a month',5,false,false),
 ('CALL_OUTCOME','ASKED_DETAILS','Asked Details',6,false,false),
 ('CALL_OUTCOME','EMAIL_SENT','Email Sent',7,false,false),
 ('CALL_OUTCOME','REMINDED','Reminded over the phone',8,false,false),
 ('CALL_OUTCOME','L1_BOOKED','Demo / L1 Booked',9,false,false),
 ('CALL_OUTCOME','REFERRED','Referred',10,false,false),
 ('CALL_OUTCOME','NOT_RIGHT_STAKEHOLDER','Not a right stake holder',11,false,false),
 ('CALL_OUTCOME','NOT_INTERESTED','Not Interested',12,false,true),
 ('CALL_OUTCOME','ALREADY_USING','Already Using Something',13,false,true),
 ('CALL_OUTCOME','NOT_POSSIBLE','Not Possible',14,false,false),
 ('CALL_OUTCOME','WRONG_ICP','Wrong ICP',15,false,false),
 ('CALL_OUTCOME','NOT_ASSOCIATED','Not Associated anymore',16,false,false),
 ('CALL_OUTCOME','WRONG_NUMBER','Wrong Number/Invalid',17,false,false),
 ('BANT_BUDGET','COST_NEUTRAL','Cost-neutral fit',1,false,false),
 ('BANT_BUDGET','BUDGET_AVAILABLE','Budget available',2,false,false),
 ('BANT_AUTHORITY','IDENTIFIED','Sign-off owner identified',1,false,false),
 ('BANT_NEED','FBP','Flexible benefits pain point',1,false,false),
 ('BANT_NEED','PAYROLL','Payroll pain point',2,false,false),
 ('BANT_TIMELINE','THIS_QUARTER','This quarter',1,false,false),
 ('BANT_TIMELINE','NEXT_QUARTER','Next quarter',2,false,false),
 ('BANT_TIMELINE','LATER','Later',3,false,false);

-- Call outcome rules (Reference lists table)
INSERT INTO crm.call_outcome_rule (outcome_id, sets_engagement_status, next_task_type, next_task_offset_wdays,
       requires_date, requires_reason, max_attempts, after_max_status, revisit_days, offer_sequence,
       marks_phone_invalid, sets_icp_fit_no, opens_l1_booking, creates_referral)
SELECT p.id, r.status, r.task, r.wdays, r.req_date, r.req_reason, r.max_att, r.after_max, r.revisit,
       r.offer_seq, r.invalid, r.icp_no, r.l1, r.referral
  FROM crm.picklist_value p
  JOIN (VALUES
   ('DID_NOT_PICK',      'CONTACTED',         'CALL',          2::smallint, false,false, 6::smallint,'UNRESPONSIVE',30::smallint, true, false,false,false,false),
   ('SWITCH_OFF',        'CONTACTED',         'CALL',          2, false,false, 6,'UNRESPONSIVE',30, true, false,false,false,false),
   ('INCOMING_BARRED',   'CONTACTED',         'CALL',          2, false,false, 6,'UNRESPONSIVE',30, true, false,false,false,false),
   ('CALL_LATER',        'FOLLOW_UP_PENDING', 'CALL',          NULL, true, false, NULL,NULL,NULL, false,false,false,false,false),
   ('CALL_AFTER_MONTH',  'FOLLOW_UP_PENDING', 'CALL',          NULL, true, false, NULL,NULL,NULL, false,false,false,false,false),
   ('ASKED_DETAILS',     'CONNECTED',         'SEND_TEMPLATE', 2, false,false, NULL,NULL,NULL, false,false,false,false,false),
   ('EMAIL_SENT',        'CONNECTED',         'CALL',          3, false,false, NULL,NULL,NULL, false,false,false,false,false),
   ('REMINDED',          NULL,                NULL,            NULL, false,false, NULL,NULL,NULL, false,false,false,false,false),
   ('L1_BOOKED',         'MEETING_SCHEDULED', NULL,            NULL, false,false, NULL,NULL,NULL, false,false,false,true, false),
   ('REFERRED',          'REFERRED',          'CALL',          1, false,false, NULL,NULL,NULL, false,false,false,false,true),
   ('NOT_RIGHT_STAKEHOLDER','REFERRED',       'CALL',          1, false,false, NULL,NULL,NULL, false,false,false,false,true),
   ('NOT_INTERESTED',    'NOT_RELEVANT',      NULL,            NULL, false,true, NULL,NULL,90, false,false,false,false,false),
   ('ALREADY_USING',     'NOT_RELEVANT',      NULL,            NULL, false,true, NULL,NULL,90, false,false,false,false,false),
   ('NOT_POSSIBLE',      'NOT_RELEVANT',      NULL,            NULL, false,false, NULL,NULL,NULL, false,false,false,false,false),
   ('WRONG_ICP',         'NOT_RELEVANT',      NULL,            NULL, false,false, NULL,NULL,NULL, false,false,true, false,false),
   ('NOT_ASSOCIATED',    'NOT_RELEVANT',      NULL,            NULL, false,false, NULL,NULL,NULL, false,false,false,false,false),
   ('WRONG_NUMBER',      NULL,                'FIND_NUMBER',   1, false,false, NULL,NULL,NULL, false,true, false,false,false)
  ) AS r(code,status,task,wdays,req_date,req_reason,max_att,after_max,revisit,offer_seq,invalid,icp_no,l1,referral)
    ON p.picklist_key = 'CALL_OUTCOME' AND p.code = r.code;

INSERT INTO crm.stage_checklist_item (stage, code, label, sort_order, is_exit_step, requires_attachment) VALUES
 ('L1','RIGHT_POCS','Connected with the right POCs',1,false,false),
 ('L1','DEMO','Product demo done',2,false,false),
 ('L1','SCOPING','Scoping done, BDR information verified',3,false,false),
 ('L1','PAIN_POINTS','Pain points identified',4,false,false),
 ('L1','DOCS_SHARED','All documents shared with the client',5,true,true),
 ('L2','CHAMPION','Champion identified',1,false,false),
 ('L2','EXT_DEMO','Extended demo to relevant stakeholders',2,false,false),
 ('L2','PAIN_RESOLVED','Pain points resolved',3,false,false),
 ('L2','BASIC_COMMERCIALS','Basic commercials discussed',4,false,false),
 ('L2','LEGAL_PROCUREMENT','Legal and procurement process noted',5,false,false),
 ('L2','COMPETITOR_TIMELINE','Competitor contracting timeline noted',6,false,false),
 ('L2','COMMERCIALS_SENT','Commercials sent',7,true,true),
 ('L3','PROCUREMENT','Procurement started',1,false,false),
 ('L3','NEGOTIATION','Commercial negotiation',2,false,false),
 ('L3','IMPL_PLAN','Implementation process and timeline agreed',3,true,false);

-- Designation map: a few rows from Appendix B. Load the full 56 (+ POC fit tab) via import.
INSERT INTO crm.designation_map (designation, level, department, priority_rank) VALUES
 ('CFO',1,'FINANCE',1), ('CHRO',1,'HR',2), ('Head of Finance',1,'FINANCE',3),
 ('Vice President Finance',1,'FINANCE',3), ('Vice President Human Resources',1,'HR',4),
 ('Finance Controller',2,'FINANCE',3), ('Director HR',2,'HR',4),
 ('Head of Compensation and Benefits',2,'HR',5), ('Director - Total Rewards',2,'HR',6),
 ('Head Of Payroll',3,'PAYROLL',7), ('Senior Payroll Manager',4,'PAYROLL',7),
 ('HR Manager',5,'HR',8), ('Assistant Manager Payroll',5,'PAYROLL',7);
