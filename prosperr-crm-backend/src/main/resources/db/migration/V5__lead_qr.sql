-- Event lead capture: each BDR gets QR codes; a prospect scans one and fills a short form on their own phone.
CREATE TABLE crm.lead_qr (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  token         text NOT NULL UNIQUE,
  owner_id      uuid NOT NULL REFERENCES crm.app_user(id),
  event_name    text NOT NULL,
  send_welcome  boolean NOT NULL DEFAULT false,
  active        boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX lead_qr_owner_idx ON crm.lead_qr (owner_id);

CREATE TABLE crm.lead_capture (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  qr_id          uuid NOT NULL REFERENCES crm.lead_qr(id) ON DELETE CASCADE,
  account_id     uuid REFERENCES crm.account(id) ON DELETE SET NULL,
  stakeholder_id uuid REFERENCES crm.stakeholder(id) ON DELETE SET NULL,
  full_name      text NOT NULL,
  company        text NOT NULL,
  designation    text,
  email          text NOT NULL,
  phone          text,
  outcome        text NOT NULL CHECK (outcome IN ('CREATED', 'EXISTING', 'REVIEW')),
  review_reason  text,
  welcome_sent   boolean NOT NULL DEFAULT false,
  consent_at     timestamptz NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX lead_capture_qr_idx ON crm.lead_capture (qr_id, created_at DESC);
