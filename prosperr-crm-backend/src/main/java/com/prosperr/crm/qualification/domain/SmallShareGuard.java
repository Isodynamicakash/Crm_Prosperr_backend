package com.prosperr.crm.qualification.domain;

/** PRD M4 / M4-11: Small accounts may be at most 20% (configurable) of each BDR's SQLs. */
public final class SmallShareGuard {
    private final double limit;
    public SmallShareGuard(double limit) { this.limit = limit; }

    public double share(long smallSqls, long totalSqls) {
        return totalSqls == 0 ? 0 : (double) smallSqls / totalSqls;
    }

    /** Would approving one more Small SQL push this BDR above the limit? */
    public boolean wouldExceed(long smallApproved, long totalQualified) {
        return share(smallApproved + 1, totalQualified + 1) > limit + 1e-9;
    }
}
