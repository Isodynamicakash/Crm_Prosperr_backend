package com.prosperr.crm.prospecting.domain;

import java.time.Instant;
import java.util.UUID;

/** One row of the BDR "My Day" queue (PRD M2-1). */
public record MyDayItem(Section section, UUID taskId, UUID accountId, String accountName, UUID stakeholderId,
                        String stakeholderName, String designation, String phone, String title, Instant dueAt,
                        int priorityRank) {
    /** Order of sections: overdue, due today, untouched stakeholders, revival accounts due. */
    public enum Section { OVERDUE, DUE_TODAY, UNTOUCHED, REVIVAL }
}
