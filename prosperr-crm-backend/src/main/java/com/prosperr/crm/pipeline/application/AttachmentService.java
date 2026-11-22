package com.prosperr.crm.pipeline.application;

import com.prosperr.crm.account.api.AccountGuard;
import com.prosperr.crm.shared.domain.AttachmentKind;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * File references for "documents shared" and "commercials sent" (M6-10). The frontend uploads the file to
 * Supabase Storage (or shares a link) and registers it here.
 */
@Service
public class AttachmentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountGuard guard;

    public AttachmentService(NamedParameterJdbcTemplate jdbc, AccountGuard guard) { this.jdbc = jdbc; this.guard = guard; }

    public record NewAttachment(UUID opportunityId, UUID meetingId, AttachmentKind kind, String fileName, String storagePath,
                                String externalUrl, String mimeType, Long sizeBytes) {}

    @Transactional
    public UUID add(UUID accountId, NewAttachment a, Actor actor) {
        guard.require(actor, Action.LOG_ACTIVITY, accountId);
        if (a.storagePath() == null && a.externalUrl() == null) throw new IllegalArgumentException("storagePath or externalUrl is required");
        return jdbc.queryForObject("""
                INSERT INTO crm.attachment (account_id, opportunity_id, meeting_id, kind, file_name, storage_path, external_url,
                                            mime_type, size_bytes, uploaded_by)
                VALUES (:a, :o, :m, :k, :f, :sp, :url, :mime, :size, :by) RETURNING id""",
                new MapSqlParameterSource().addValue("a", accountId).addValue("o", a.opportunityId()).addValue("m", a.meetingId())
                        .addValue("k", a.kind().name()).addValue("f", a.fileName()).addValue("sp", a.storagePath())
                        .addValue("url", a.externalUrl()).addValue("mime", a.mimeType()).addValue("size", a.sizeBytes())
                        .addValue("by", actor.userId()), UUID.class);
    }

    public List<Map<String, Object>> list(UUID accountId, Actor actor) {
        guard.require(actor, Action.VIEW_PIPELINE, accountId);
        return jdbc.queryForList("""
                SELECT id, kind, file_name AS "fileName", storage_path AS "storagePath", external_url AS "externalUrl",
                       uploaded_at AS "uploadedAt" FROM crm.attachment WHERE account_id = :a ORDER BY uploaded_at DESC""",
                Map.of("a", accountId));
    }
}
