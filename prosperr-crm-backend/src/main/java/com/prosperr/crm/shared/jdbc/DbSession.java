package com.prosperr.crm.shared.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Tells database triggers who is acting, for the current transaction only (used by stage_history). */
@Component
public class DbSession {
    private final JdbcTemplate jdbc;
    public DbSession(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void actAs(UUID userId) {
        jdbc.queryForObject("SELECT set_config('crm.current_user', ?, true)", String.class,
                userId == null ? "" : userId.toString());
    }
}
