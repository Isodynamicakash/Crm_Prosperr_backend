-- An SQL passed on high intent (with a written reason) doesn't need BANT; every other submitted SQL still does.
ALTER TABLE crm.sql_submission DROP CONSTRAINT IF EXISTS bant_complete_on_submit;
ALTER TABLE crm.sql_submission ADD CONSTRAINT bant_complete_on_submit CHECK (
    submitted_at IS NULL OR high_intent_override
        OR (bant_budget_id IS NOT NULL AND bant_authority_id IS NOT NULL AND bant_need_id IS NOT NULL AND bant_timeline_id IS NOT NULL));