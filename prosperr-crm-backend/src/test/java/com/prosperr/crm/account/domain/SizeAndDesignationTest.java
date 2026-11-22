package com.prosperr.crm.account.domain;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.SizeBucket;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SizeAndDesignationTest {

    @Test
    void M1_4_sizeBucketsFromLicensePotential() {
        SizeBucketClassifier c = SizeBucketClassifier.defaults();
        assertEquals(Optional.of(SizeBucket.SMALL), c.classify(250));
        assertEquals(Optional.of(SizeBucket.MEDIUM), c.classify(251));
        assertEquals(Optional.of(SizeBucket.LARGE), c.classify(1000));
        assertEquals(Optional.of(SizeBucket.ENTERPRISE), c.classify(1001));
        assertEquals(Optional.empty(), c.classify(null));
    }

    @Test
    void M1_5_designationMapGivesLevelAndRightPoc() {
        DesignationResolver r = new DesignationResolver(Map.of(
                "cfo", new DesignationResolver.Entry("cfo", 1, Department.FINANCE, 1, true, null),
                "hr manager", new DesignationResolver.Entry("hr manager", 5, Department.HR, 8, true, SizeBucket.LARGE)));
        var cfo = r.resolve("  CFO ", SizeBucket.SMALL);
        assertTrue(cfo.mapped());
        assertEquals(1, cfo.level());
        assertTrue(cfo.rightPoc());
        // right POC only for Large and Enterprise accounts
        assertFalse(r.resolve("HR Manager", SizeBucket.MEDIUM).rightPoc());
        assertTrue(r.resolve("HR Manager", SizeBucket.ENTERPRISE).rightPoc());
    }

    @Test
    void M1_5_unmappedDesignationGoesToReviewAndHasNoLevel() {
        DesignationResolver r = new DesignationResolver(Map.of());
        var res = r.resolve("Chief Happiness Officer", SizeBucket.LARGE);
        assertFalse(res.mapped());
        assertNull(res.level());
        assertFalse(res.rightPoc());
    }
}
