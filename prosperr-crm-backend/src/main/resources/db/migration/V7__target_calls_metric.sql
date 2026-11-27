-- BDRs are measured only on calls and L1 booked: allow a CALLS target.
DO $$
DECLARE c record;
BEGIN
FOR c IN SELECT conname FROM pg_constraint
         WHERE conrelid = 'crm.target'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%metric%'
  LOOP
    EXECUTE format('ALTER TABLE crm.target DROP CONSTRAINT %I', c.conname);
END LOOP;
END $$;
ALTER TABLE crm.target ADD CONSTRAINT target_metric_check
    CHECK (metric IN ('CALLS','L1_BOOKED','L1_DONE','L2_DONE','L3_DONE','WON','MRR','SQL_APPROVED'));