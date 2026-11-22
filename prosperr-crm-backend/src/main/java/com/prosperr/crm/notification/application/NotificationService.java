package com.prosperr.crm.notification.application;

import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.jdbc.Db;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class NotificationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final UserDirectory users;
    private final Clock clock;

    public NotificationService(NamedParameterJdbcTemplate jdbc, UserDirectory users, Clock clock) {
        this.jdbc = jdbc; this.users = users; this.clock = clock;
    }

    @Transactional
    public void notify(Collection<UUID> userIds, String type, String title, String body, String entityType, UUID entityId) {
        new LinkedHashSet<>(userIds).stream().filter(Objects::nonNull).forEach(u -> jdbc.update("""
                INSERT INTO crm.notification (user_id, type, title, body, entity_type, entity_id) VALUES (:u, :t, :ti, :b, :et, :eid)""",
                new MapSqlParameterSource().addValue("u", u).addValue("t", type).addValue("ti", title).addValue("b", body)
                        .addValue("et", entityType).addValue("eid", entityId)));
    }

    /** Avoid repeating the same reminder (e.g. hourly "no outcome" checks). */
    boolean alreadySent(UUID userId, String type, UUID entityId, Instant since) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM crm.notification WHERE user_id = :u AND type = :t AND entity_id = :e AND created_at >= :since""",
                new MapSqlParameterSource().addValue("u", userId).addValue("t", type).addValue("e", entityId).addValue("since", Db.ts(since)),
                Integer.class);
        return n != null && n > 0;
    }

    public List<UUID> managers() {
        List<UUID> ids = new ArrayList<>();
        users.activeByRole(Role.MANAGER).forEach(u -> ids.add(u.id()));
        return ids;
    }

    public String accountName(UUID accountId) {
        return jdbc.queryForList("SELECT name FROM crm.account WHERE id = :a", Map.of("a", accountId), String.class)
                .stream().findFirst().orElse("account");
    }

    public List<Map<String, Object>> list(UUID userId, boolean unreadOnly) {
        return jdbc.queryForList("""
                SELECT id, type, title, body, entity_type AS "entityType", entity_id AS "entityId", channel, read_at AS "readAt",
                       created_at AS "createdAt" FROM crm.notification
                 WHERE user_id = :u AND (NOT :unread OR read_at IS NULL) ORDER BY created_at DESC LIMIT 200""",
                Map.of("u", userId, "unread", unreadOnly));
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        jdbc.update("UPDATE crm.notification SET read_at = now() WHERE id = :id AND user_id = :u", Map.of("id", id, "u", userId));
    }

    @Transactional
    public void markAllRead(UUID userId) {
        jdbc.update("UPDATE crm.notification SET read_at = now() WHERE user_id = :u AND read_at IS NULL", Map.of("u", userId));
    }

    Instant now() { return Instant.now(clock); }
}
