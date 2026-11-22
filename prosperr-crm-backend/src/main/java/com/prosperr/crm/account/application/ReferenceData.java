package com.prosperr.crm.account.application;

import com.prosperr.crm.account.domain.DesignationResolver;
import com.prosperr.crm.account.domain.SizeBucketClassifier;
import com.prosperr.crm.config.api.ConfigApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/** Builds the account domain rules from admin configuration. */
@Component
class ReferenceData {
    private final ConfigApi config;
    ReferenceData(ConfigApi config) { this.config = config; }

    SizeBucketClassifier sizes() {
        return new SizeBucketClassifier(config.sizeBuckets().stream()
                .map(b -> new SizeBucketClassifier.Bounds(b.bucket(), b.min(), b.max(), b.sortOrder())).toList());
    }

    DesignationResolver designations() {
        Map<String, DesignationResolver.Entry> m = config.designations().stream().collect(Collectors.toMap(
                ConfigApi.DesignationRow::normalized,
                d -> new DesignationResolver.Entry(d.normalized(), d.level(), d.department(), d.priorityRank(),
                        d.rightPoc(), d.rightPocMinBucket()), (a, b) -> a));
        return new DesignationResolver(m);
    }

    int priorityRank(java.util.UUID designationMapId) {
        if (designationMapId == null) return 8;
        return config.designation(designationMapId).map(ConfigApi.DesignationRow::priorityRank).orElse(8);
    }
}
