-- =====================================================================
-- V2: Google login columns, saved views, trigger audit user, seed data
-- (Appendix A templates, default sequence, picklists, Appendix B designations)
-- =====================================================================
SET search_path = crm, public;

-- Google sign-in (PRD non-functional: Google sign-in on the company domain).
-- Admin creates the user and sets the role; first Google login links google_sub.
ALTER TABLE crm.app_user ADD COLUMN google_sub text UNIQUE;
ALTER TABLE crm.app_user ADD COLUMN last_login_at timestamptz;

-- Saved list filters (PRD M1-6)
CREATE TABLE crm.saved_view (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES crm.app_user(id),
  name        text NOT NULL,
  entity      text NOT NULL CHECK (entity IN ('ACCOUNT','STAKEHOLDER','TASK','MEETING')),
  filters     jsonb NOT NULL DEFAULT '{}'::jsonb,
  is_shared   boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, entity, name)
);

-- Stage history records who changed the stage: the app sets crm.current_user per transaction.
CREATE OR REPLACE FUNCTION crm.current_app_user() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('crm.current_user', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION crm.opportunity_stage_sync_after_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO crm.stage_history (opportunity_id, account_id, from_stage, to_stage, changed_by, changed_at)
  VALUES (NEW.id, NEW.account_id, NULL, NEW.stage, crm.current_app_user(), NEW.stage_entered_at);
  IF NEW.is_primary THEN
    UPDATE crm.account SET lifecycle_stage = NEW.stage WHERE id = NEW.account_id;
  END IF;
  RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION crm.opportunity_stage_sync_before_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.stage IS DISTINCT FROM OLD.stage THEN
    NEW.stage_entered_at := clock_timestamp();
    INSERT INTO crm.stage_history (opportunity_id, account_id, from_stage, to_stage, changed_by, changed_at)
    VALUES (NEW.id, NEW.account_id, OLD.stage, NEW.stage, crm.current_app_user(), NEW.stage_entered_at);
    IF NEW.is_primary THEN
      UPDATE crm.account SET lifecycle_stage = NEW.stage WHERE id = NEW.account_id;
    END IF;
  END IF;
  RETURN NEW;
END $$;

-- Settings used by the application
INSERT INTO crm.app_setting (key, value, description) VALUES
 ('allowed_login_domain', '"prosperr.io"', 'Google accounts must belong to this domain'),
 ('product_link', '"https://prosperr.io/flexi-benefit-plan"', 'Fills {{Product Link}} in templates'),
 ('prospecting_history_visible_after_l1', 'true', 'Decision #13: prospecting history becomes visible when L1 is booked'),
 ('overdue_manager_alert_days', '2', 'Tell the manager when a follow-up is overdue by this many days'),
 ('sequence_max_send_failures', '3', 'Pause an enrollment after this many failed sends'),
 ('same_company_same_day_warning', 'true', 'M3-11: warn before emailing two people at one company on the same day');

-- Picklists not seeded in V1
INSERT INTO crm.picklist_value (picklist_key, code, label, sort_order, requires_remark) VALUES
 ('REACTIVATION_REASON','NEW_STAKEHOLDER','New stakeholder engaged',1,false),
 ('REACTIVATION_REASON','REVISIT_DUE','Revisit date reached',2,false),
 ('REACTIVATION_REASON','PROSPECT_REACHED_OUT','Prospect reached out',3,false),
 ('REACTIVATION_REASON','OTHER','Other',4,true),
 ('STAGNATION_REASON','DM_UNAVAILABLE','Decision maker unavailable',1,false),
 ('STAGNATION_REASON','INTERNAL_ALIGNMENT','Waiting for internal alignment',2,false),
 ('STAGNATION_REASON','BUDGET_CYCLE','Waiting for budget cycle',3,false),
 ('STAGNATION_REASON','SPOC_UNRESPONSIVE','SPOC unresponsive',4,false),
 ('STAGNATION_REASON','OTHER','Other',5,true),
 ('LOST_REASON','NO_REQUIREMENT','No requirement',1,false),
 ('LOST_REASON','BUDGET','Budget',2,false),
 ('LOST_REASON','PRICING','Pricing',3,false),
 ('LOST_REASON','PRODUCT_FIT','Product fit',4,false),
 ('LOST_REASON','COMPETITOR','Competitor selected',5,false),
 ('LOST_REASON','COMPLIANCE','Compliance / legal issue',6,false),
 ('LOST_REASON','OTHER','Other',7,true),
 ('SQL_REJECTION_REASON','SIZE','Below license threshold',1,false),
 ('SQL_REJECTION_REASON','LOCATION','Outside ICP cities',2,false),
 ('SQL_REJECTION_REASON','STAKEHOLDER','Not the right stakeholder',3,false),
 ('SQL_REJECTION_REASON','BANT','BANT not convincing',4,false),
 ('SQL_REJECTION_REASON','DUPLICATE','Duplicate / already in pipeline',5,false),
 ('SQL_REJECTION_REASON','OTHER','Other',6,true),
 ('REASSIGNMENT_REASON','BANDWIDTH','AE bandwidth',1,false),
 ('REASSIGNMENT_REASON','USER_LEFT','User left or deactivated',2,false),
 ('REASSIGNMENT_REASON','EXPERTISE','Industry or account fit',3,false),
 ('REASSIGNMENT_REASON','CUSTOMER_REQUEST','Customer request',4,false),
 ('REASSIGNMENT_REASON','OTHER','Other',5,true),
 ('LEAD_SOURCE','APOLLO','Apollo',1,false),
 ('LEAD_SOURCE','LINKEDIN','LinkedIn',2,false),
 ('LEAD_SOURCE','REFERRAL','Referral',3,false),
 ('LEAD_SOURCE','INBOUND','Inbound',4,false),
 ('LEAD_SOURCE','EVENT','Event',5,false),
 ('LEAD_SOURCE','OTHER','Other',6,false),
 ('INDUSTRY','IT_SERVICES','IT services',1,false),
 ('INDUSTRY','SAAS','SaaS / software',2,false),
 ('INDUSTRY','BFSI','BFSI',3,false),
 ('INDUSTRY','MANUFACTURING','Manufacturing',4,false),
 ('INDUSTRY','RETAIL','Retail / e-commerce',5,false),
 ('INDUSTRY','HEALTHCARE','Healthcare',6,false),
 ('INDUSTRY','EDTECH','Education',7,false),
 ('INDUSTRY','LOGISTICS','Logistics',8,false),
 ('INDUSTRY','OTHER','Other',9,false),
 ('BANT_AUTHORITY','PENDING','Sign-off owner not yet known',2,false);

-- Appendix B: full seed designation map (56 titles). V1 seeded a few; keep those.
INSERT INTO crm.designation_map (designation, level, department, priority_rank) VALUES
 ('CFO',1,'FINANCE',1),
 ('CHRO',1,'HR',2),
 ('Vice President Finance',1,'FINANCE',3),
 ('Senior Vice President - Business CFO & Head Corporate FP&A',1,'FINANCE',1),
 ('Head of Finance',1,'FINANCE',3),
 ('Global Finance Director',1,'FINANCE',3),
 ('Vice President Human Resources',1,'HR',4),
 ('National Head HR',1,'HR',4),
 ('Human Resources Director & Head',1,'HR',4),
 ('Finance Controller',2,'FINANCE',3),
 ('AVP-HR',2,'HR',4),
 ('AVP Finance',2,'FINANCE',3),
 ('Director HR',2,'HR',4),
 ('Director Finance',2,'FINANCE',3),
 ('Senior Director Finance',2,'FINANCE',3),
 ('Senior Director - C&B and HR Operations',2,'HR',5),
 ('Director Human Resources Operations',2,'HR',4),
 ('Global C & B Leader',2,'HR',5),
 ('Director - Total Rewards',2,'HR',6),
 ('Director - Compensation & Benefits',2,'HR',5),
 ('Head - Compensation, Benefits & Operations',2,'HR',5),
 ('Head of Compensation and Benefits',2,'HR',5),
 ('Head Total Rewards & HR Ops',2,'HR',6),
 ('GM HR',2,'HR',4),
 ('Associate Director - HR',2,'HR',4),
 ('Associate Director - Head of Payroll, Benefits and Compliance',2,'PAYROLL',7),
 ('Head Of Payroll',3,'PAYROLL',7),
 ('Payroll Lead',3,'PAYROLL',7),
 ('Lead - Payroll and Compensation & Benefits',3,'PAYROLL',7),
 ('HR Payroll Lead',3,'PAYROLL',7),
 ('Finance Lead',3,'FINANCE',8),
 ('Lead C&B',3,'HR',5),
 ('Head comp and ops',3,'HR',5),
 ('People Ops Lead',3,'HR',8),
 ('Senior Manager C&B and Payroll',3,'PAYROLL',7),
 ('Senior Manager - Finance',4,'FINANCE',8),
 ('Senior Manager C&B',4,'HR',5),
 ('Senior Manager - Compensation & Benefits',4,'HR',5),
 ('Senior Manager Payroll/ Compliances',4,'PAYROLL',7),
 ('Senior Manager - Payroll Benefit Specialist',4,'PAYROLL',7),
 ('Senior Payroll Manager',4,'PAYROLL',7),
 ('Senior Manager - Global Total Rewards, HR Transformation, Analytics & Excellence',4,'HR',6),
 ('Manager Payroll / Head of Payroll',5,'PAYROLL',7),
 ('Payroll Manager, C&B',5,'PAYROLL',7),
 ('Sr. Manager Rewards',5,'HR',6),
 ('Total Rewards Manager',5,'HR',6),
 ('Manager Total Rewards',5,'HR',6),
 ('Manager Talent Excellence - C&B',5,'HR',5),
 ('HR Operations & Payroll',5,'PAYROLL',7),
 ('HR Manager',5,'HR',8),
 ('Senior Manager People & Culture',5,'HR',8),
 ('Assistant Manager HR Ops and Payroll',5,'PAYROLL',7),
 ('Assistant Manager Payroll',5,'PAYROLL',7),
 ('Assistant Manager - Accounts (referred by VP Finance)',5,'FINANCE',8),
 ('Associate Manager II - HRBP',5,'HR',8),
 ('Sr HR - Payroll & Operations',5,'PAYROLL',7)
ON CONFLICT (designation_normalized) DO NOTHING;

-- Appendix A: snippets and templates
INSERT INTO crm.email_snippet (code, variant, content) VALUES
 ('CALL_LINE','DEFAULT',$t$I tried reaching you by phone just now but couldn't connect, so here are the key points.$t$),
 ('PERSONA_PITCH','FINANCE',$t$Here's what it means for you as a finance leader:
• Cost-neutral: no change to CTC and no additional cash outflow
• Wage code ready: can offset the 2–3% take-home dip many employees see from the new labour codes and the EPFO ceiling
• Fully compliant: TDS-compliant under both tax regimes, with 12+ benefit categories
• No finance workload: we handle bill verification and compliance, so Finance and Payroll stay hands-off$t$),
 ('PERSONA_PITCH','HR',$t$Here's what it means for your people:
• 5–8% more take-home every month, with no change to CTC
• Wage code ready: can offset the 2–3% take-home dip from the new labour codes and the EPFO ceiling
• 12+ benefit categories, TDS-compliant under both tax regimes
• No HR workload: we handle bill verification and compliance end to end$t$),
 ('PERSONA_PITCH','PAYROLL',$t$Here's what it means for payroll:
• No change to CTC structure or payroll cost
• TDS-compliant under both tax regimes, with 12+ benefit categories
• We handle bill verification and compliance, so there is no extra processing for your team$t$),
 ('CUSTOMER_PROOF','DEFAULT',$t$Proven: live with Livspace, Piaggio Automobiles, Acko Insurance, Druva and PhysicsWallah$t$),
 ('SIGNATURE','DEFAULT',$t${{Sender Full Name}}
Prosperr.io | {{Sender Phone}}$t$);

INSERT INTO crm.email_template (code, name, subject, body, is_internal) VALUES
 ('INTRO','Intro',$t$Prosperr <> {{Company}} | Increase employee take-home salary by 5–8% with no extra CTC$t$,$t$Hi {{First Name}},

{{Call Line}}

Prosperr's Flexible Benefits Plan lets {{Company}} employees take home 5-8% more every month.

{{Persona Pitch}}

{{Customer Proof}}

More details here: {{Product Link}}

Would a quick 20-minute call this week work, to see if it's a fit for {{Company}}?

Best,
{{Signature}}$t$,false),
 ('FOLLOWUP1','Followup1','Following up on my previous email',$t$Hi {{First Name}},

Following up on my note about raising take-home pay at {{Company}} by 5-8% without changing CTC.

Prosperr takes care of compliance, legality and implementation end to end, so the change needs very little from your team.

Is there a good time to connect this week?

Best regards,
{{Signature}}$t$,false),
 ('FOLLOWUP2','Followup2','Final follow-up',$t$Hi {{First Name}},

One last follow-up from me. Prosperr helps companies like {{Company}} run a compliant Flexible Benefits Plan that increases employees' in-hand salary without increasing payroll cost, while keeping administration simple for HR and Finance.

Would you be open to a brief discussion this week or next?

Best regards,
{{Signature}}$t$,false),
 ('REMINDER','Reminder (internal)',$t$Call {{First Name}} at {{Company}}: sequence ended with no reply$t$,$t$Hi {{Sender First Name}},

The email sequence for {{First Name}} ({{Designation}}) at {{Company}} has finished with no reply.

A call task is due on {{Call Task Due}}. Call them and log the outcome in the CRM:
{{Stakeholder Link}}$t$,true);

INSERT INTO crm.email_sequence (name, is_default) VALUES ('Default outbound', true);
INSERT INTO crm.sequence_step (sequence_id, step_no, template_id, day_offset, in_same_thread, recipient, creates_task_type)
SELECT s.id, v.step_no, t.id, v.day_offset, v.same_thread, v.recipient, v.task
  FROM crm.email_sequence s
  JOIN (VALUES (1,'INTRO',0,false,'STAKEHOLDER',NULL),
               (2,'FOLLOWUP1',2,true,'STAKEHOLDER',NULL),
               (3,'FOLLOWUP2',5,true,'STAKEHOLDER',NULL),
               (4,'REMINDER',7,false,'BDR','CALL')) AS v(step_no, code, day_offset, same_thread, recipient, task)
    ON true
  JOIN crm.email_template t ON t.code = v.code
 WHERE s.is_default;
