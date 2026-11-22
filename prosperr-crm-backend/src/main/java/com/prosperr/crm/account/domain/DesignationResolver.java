package com.prosperr.crm.account.domain;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.SizeBucket;

import java.util.Map;
import java.util.Optional;

/**
 * PRD M1-5 / Appendix B: a designation maps to a level L1–L5, a department, a priority rank and a
 * right-POC flag. Some titles are right POC only for Large and Enterprise accounts.
 * Unmatched titles are "unmapped": they go to the admin review list and never qualify an SQL.
 */
public final class DesignationResolver {

    public record Entry(String designationNormalized, int level, Department department, int priorityRank,
                        boolean rightPoc, SizeBucket rightPocMinBucket) {}

    public record Resolution(boolean mapped, Integer level, Department department, int priorityRank, boolean rightPoc) {
        public static Resolution unmapped() { return new Resolution(false, null, Department.OTHER, 8, false); }
    }

    private final Map<String, Entry> byTitle;

    public DesignationResolver(Map<String, Entry> byNormalizedTitle) { this.byTitle = Map.copyOf(byNormalizedTitle); }

    public Resolution resolve(String rawDesignation, SizeBucket accountBucket) {
        Entry e = byTitle.get(Normalizer.designation(rawDesignation));
        if (e == null) return Resolution.unmapped();
        boolean rightPoc = e.rightPoc() && meetsMin(accountBucket, e.rightPocMinBucket());
        return new Resolution(true, e.level(), e.department(), e.priorityRank(), rightPoc);
    }

    public Optional<Entry> find(String rawDesignation) {
        return Optional.ofNullable(byTitle.get(Normalizer.designation(rawDesignation)));
    }

    private static boolean meetsMin(SizeBucket actual, SizeBucket min) {
        if (min == null) return true;
        if (actual == null) return false;
        return actual.ordinal() >= min.ordinal();
    }
}
