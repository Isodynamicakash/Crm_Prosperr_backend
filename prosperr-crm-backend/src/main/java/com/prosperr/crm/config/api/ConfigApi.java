package com.prosperr.crm.config.api;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.LifecycleStage;
import com.prosperr.crm.shared.domain.LocationPriority;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.domain.SizeBucket;
import com.prosperr.crm.shared.domain.Zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Admin-editable configuration, read by every module. */
public interface ConfigApi {
    record PicklistItem(UUID id, String key, String code, String label, int sortOrder, boolean active,
                        boolean requiresRevisitDate, boolean requiresRemark) {}
    record CityView(UUID id, String name, String region, LocationPriority priority) {}
    record BucketRow(SizeBucket bucket, int min, Integer max, int sortOrder) {}
    record DesignationRow(UUID id, String designation, String normalized, int level, Department department,
                          int priorityRank, boolean rightPoc, SizeBucket rightPocMinBucket) {}
    record StageThreshold(LifecycleStage stage, int inactivityFlagDays, Integer stagnationDays, int followUpIntervalDays) {}
    record ChecklistItem(UUID id, String stage, String code, String label, int sortOrder, boolean exitStep,
                         boolean requiresAttachment) {}

    Optional<PicklistItem> picklistItem(UUID id);
    PicklistItem picklistItem(String key, String code);
    List<PicklistItem> picklist(String key);

    Optional<CityView> city(UUID id);

    List<BucketRow> sizeBuckets();
    List<DesignationRow> designations();
    Optional<DesignationRow> designation(UUID id);

    List<StageThreshold> stageThresholds();
    StageThreshold stageThreshold(LifecycleStage stage);
    List<ChecklistItem> checklistItems();

    String text(String key, String fallback);
    boolean flag(String key, boolean fallback);
    int number(String key, int fallback);
    double decimal(String key, double fallback);
    /** Raw JSON of a setting. */
    Optional<String> json(String key);

    /** Admin override of a default permission: NONE, OWN or ALL (PRD M10-1 field-level edit rights). */
    Optional<String> permissionOverride(Role role, String action, Zone zone);
}
