package com.prosperr.crm.audit.api;

import java.util.UUID;

/** PRD M10-2: audit trail for owner, stage, status, temperature, reason and SQL changes. */
public interface AuditApi {
    enum Action { CREATE, UPDATE, MERGE, REASSIGN, STATUS, IMPORT }

    void record(String entityType, UUID entityId, Action action, String field, Object oldValue, Object newValue, UUID by);

    /** Records only when the value actually changed. */
    default void change(String entityType, UUID entityId, String field, Object oldValue, Object newValue, UUID by) {
        if (oldValue == null ? newValue == null : oldValue.equals(newValue)) return;
        record(entityType, entityId, Action.UPDATE, field, oldValue, newValue, by);
    }
}
