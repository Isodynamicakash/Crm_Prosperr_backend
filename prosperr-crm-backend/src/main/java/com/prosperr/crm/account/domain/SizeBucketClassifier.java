package com.prosperr.crm.account.domain;

import com.prosperr.crm.shared.domain.SizeBucket;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** PRD M1-4: size bucket from license potential, with admin-editable bounds. Mirrors crm.bucket_for(). */
public final class SizeBucketClassifier {
    public record Bounds(SizeBucket bucket, int min, Integer max, int sortOrder) {}

    private final List<Bounds> bounds;

    public SizeBucketClassifier(List<Bounds> bounds) {
        this.bounds = bounds.stream().sorted(Comparator.comparingInt(Bounds::sortOrder)).toList();
    }

    public static SizeBucketClassifier defaults() {
        return new SizeBucketClassifier(List.of(
                new Bounds(SizeBucket.SMALL, 0, 250, 1),
                new Bounds(SizeBucket.MEDIUM, 251, 500, 2),
                new Bounds(SizeBucket.LARGE, 501, 1000, 3),
                new Bounds(SizeBucket.ENTERPRISE, 1001, null, 4)));
    }

    public Optional<SizeBucket> classify(Integer licenses) {
        if (licenses == null) return Optional.empty();
        return bounds.stream()
                .filter(b -> licenses >= b.min() && (b.max() == null || licenses <= b.max()))
                .map(Bounds::bucket)
                .findFirst();
    }
}
