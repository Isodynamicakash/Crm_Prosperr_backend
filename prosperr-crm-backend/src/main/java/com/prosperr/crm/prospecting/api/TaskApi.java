package com.prosperr.crm.prospecting.api;

import com.prosperr.crm.shared.domain.TaskSource;
import com.prosperr.crm.shared.domain.TaskType;

import java.time.Instant;
import java.util.UUID;

/** Tasks ("next steps") for any module: call rules, sequences, meetings, revival. */
public interface TaskApi {
    record NewTask(UUID accountId, UUID stakeholderId, UUID opportunityId, UUID meetingId, TaskType type, String title,
                   Instant dueAt, UUID assigneeId, TaskSource source, UUID sourceActivityId, UUID createdBy) {}

    UUID create(NewTask task);
    /** Reassignment moves open tasks with the account (M5-4). Returns tasks moved. */
    int moveOpen(UUID accountId, UUID fromUser, UUID toUser);
    /** Close open tasks of these types for a stakeholder (a new outcome replaces the old reminder). */
    int closeOpen(UUID stakeholderId, java.util.Set<TaskType> types, UUID by);
    boolean hasOpen(UUID accountId, TaskType type);
}
