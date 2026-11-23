-- Google connection per user (Gmail + Calendar) and which calendar holds each meeting's event.
ALTER TABLE crm.user_mailbox ADD COLUMN IF NOT EXISTS scopes text;
ALTER TABLE crm.user_mailbox ADD COLUMN IF NOT EXISTS last_error text;
ALTER TABLE crm.meeting ADD COLUMN IF NOT EXISTS calendar_owner_id uuid REFERENCES crm.app_user(id);
