package com.prosperr.crm.qualification.domain;

import com.prosperr.crm.shared.domain.IntentRating;
import com.prosperr.crm.shared.domain.LocationPriority;
import com.prosperr.crm.shared.domain.SizeBucket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** PRD M4 ICP criteria: an account is an SQL only when all four pass. */
class QualificationEngineTest {
    final QualificationEngine engine = QualificationEngine.standard();
    final List<QualificationEngine.ActiveRule> rules = List.of(
            new QualificationEngine.ActiveRule("RIGHT_STAKEHOLDER", Map.of("direct_levels", List.of(1, 2),
                    "referred_levels", List.of(3, 4, 5), "referrer_levels", List.of(1, 2))),
            new QualificationEngine.ActiveRule("LICENSES", Map.of("min_licenses", 251)),
            new QualificationEngine.ActiveRule("LOCATION", Map.of()),
            new QualificationEngine.ActiveRule("BANT", Map.of()));

    QualificationInput good() {
        return new QualificationInput(1, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P0, null, null, false, true, true, true, true);
    }

    CriterionResult result(QualificationInput in, String code) {
        return engine.evaluate(in, rules).results().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void M4_allFourPass_isSql() {
        var r = engine.evaluate(good(), rules);
        assertTrue(r.canSubmit());
        assertTrue(r.fullyQualified());
        assertEquals(4, r.results().size());
    }

    @Test
    void M4_rightStakeholder_L1L2Direct_L3to5OnlyWithReferral() {
        var l2 = new QualificationInput(2, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.PASS, result(l2, "RIGHT_STAKEHOLDER").status());
        var l4Direct = new QualificationInput(4, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(l4Direct, "RIGHT_STAKEHOLDER").status());
        var l4Referred = new QualificationInput(4, true, 1, true, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.PASS, result(l4Referred, "RIGHT_STAKEHOLDER").status());
        var referredByOtherCompany = new QualificationInput(4, true, 1, false, 620, SizeBucket.LARGE, false, null,
                false, LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(referredByOtherCompany, "RIGHT_STAKEHOLDER").status());
    }

    @Test
    void M4_unmappedDesignationFails() {
        var in = new QualificationInput(null, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(in, "RIGHT_STAKEHOLDER").status());
    }

    @Test
    void M4_11_smallNeedsReasonThenManagerApproval() {
        var noException = new QualificationInput(1, true, null, false, 200, SizeBucket.SMALL, false, null, false,
                LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(noException, "LICENSES").status());
        var noReason = new QualificationInput(1, true, null, false, 200, SizeBucket.SMALL, true, " ", false,
                LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(noReason, "LICENSES").status());
        var pending = new QualificationInput(1, true, null, false, 200, SizeBucket.SMALL, true, "Strategic logo", false,
                LocationPriority.P0, null, null, false, true, true, true, true);
        assertEquals(CriterionStatus.NEEDS_APPROVAL, result(pending, "LICENSES").status());
        assertTrue(engine.evaluate(pending, rules).canSubmit(), "can be submitted, manager approves later");
        assertFalse(engine.evaluate(pending, rules).fullyQualified());
    }

    @Test
    void M4_7_p1CityNeedsHighIntentReasonAndConfirmation() {
        var medium = new QualificationInput(1, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P1, IntentRating.MEDIUM, "x", false, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(medium, "LOCATION").status());
        var highNoReason = new QualificationInput(1, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P1, IntentRating.HIGH, "", false, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(highNoReason, "LOCATION").status());
        var high = new QualificationInput(1, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P1, IntentRating.HIGH, "Budget approved this quarter", false, true, true, true, true);
        assertEquals(CriterionStatus.NEEDS_APPROVAL, result(high, "LOCATION").status());
        var confirmed = new QualificationInput(1, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P1, IntentRating.HIGH, "Budget approved", true, true, true, true, true);
        assertEquals(CriterionStatus.PASS, result(confirmed, "LOCATION").status());
        var other = new QualificationInput(1, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.NONE, IntentRating.HIGH, "x", true, true, true, true, true);
        assertEquals(CriterionStatus.FAIL, result(other, "LOCATION").status());
    }

    @Test
    void M4_10_bantAllFourRequired() {
        var noTimeline = new QualificationInput(1, true, null, false, 620, SizeBucket.LARGE, false, null, false,
                LocationPriority.P0, null, null, false, true, true, true, false);
        CriterionResult r = result(noTimeline, "BANT");
        assertEquals(CriterionStatus.FAIL, r.status());
        assertTrue(r.detail().contains("Timeline"));
        assertFalse(engine.evaluate(noTimeline, rules).canSubmit());
    }

    @Test
    void M4_2_rulesConfigurable_minLicensesAndDisabledRule() {
        var cfg = List.of(new QualificationEngine.ActiveRule("LICENSES", Map.of("min_licenses", 100)));
        var in = new QualificationInput(1, true, null, false, 150, SizeBucket.SMALL, false, null, false,
                LocationPriority.NONE, null, null, false, false, false, false, false);
        var r = engine.evaluate(in, cfg);
        assertEquals(1, r.results().size(), "only active rules are evaluated");
        assertTrue(r.canSubmit());
    }

    @Test
    void M4_11_smallShareLimitTwentyPercent() {
        SmallShareGuard g = new SmallShareGuard(0.20);
        assertFalse(g.wouldExceed(0, 4), "1 of 5 = 20% is allowed");
        assertTrue(g.wouldExceed(1, 4), "2 of 5 = 40% is not");
        assertEquals(0.25, g.share(1, 4), 1e-9);
    }
}
