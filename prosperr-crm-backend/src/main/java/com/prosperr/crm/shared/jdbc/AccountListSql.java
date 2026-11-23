package com.prosperr.crm.shared.jdbc;

/** Columns of an account list row, shared by the account list and dashboard drill-downs. */
public final class AccountListSql {
    private AccountListSql() {}

    public static final String COLUMNS = """
            SELECT a.id, a.name, c.name AS "city", a.size_bucket_code AS "sizeBucket", a.license_potential AS "licensePotential",
                   a.lifecycle_stage AS "stage", a.account_status AS "status", a.visibility_zone AS "zone",
                   ae.full_name AS "aeOwner", (SELECT string_agg(u.full_name, ', ' ORDER BY u.full_name)
                     FROM crm.account_bdr ab JOIN crm.app_user u ON u.id = ab.bdr_id WHERE ab.account_id = a.id) AS "bdrs",
                   o.temperature, o.expected_mrr AS "expectedMrr", a.next_step AS "nextStep", a.next_step_due AS "nextStepDue",
                   a.last_activity_at AS "lastActivityAt", ir.label AS "inactiveReason", a.revisit_on AS "revisitOn",
                   a.review_flagged_at IS NOT NULL AS "flaggedForReview", a.created_at AS "createdAt",
                   (SELECT max(x.occurred_at) FROM crm.activity x WHERE x.account_id = a.id AND x.logged_by = :viewer) AS "myLastTouchAt",
                   (SELECT count(*) FROM crm.activity x WHERE x.account_id = a.id AND x.logged_by = :viewer AND x.type = 'CALL') AS "myCalls",
                   (SELECT string_agg(DISTINCT u.full_name, ', ') FROM crm.activity x JOIN crm.app_user u ON u.id = x.logged_by
                     WHERE x.account_id = a.id AND u.role = 'BDR' AND x.logged_by <> :viewer) AS "workedByOthers"
              FROM crm.account a
              LEFT JOIN crm.city c ON c.id = a.city_id
              LEFT JOIN crm.app_user ae ON ae.id = a.ae_owner_id
              LEFT JOIN crm.opportunity o ON o.account_id = a.id AND o.is_primary
              LEFT JOIN crm.picklist_value ir ON ir.id = a.inactive_reason_id""";
}
