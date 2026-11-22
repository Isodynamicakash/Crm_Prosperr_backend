package com.prosperr.crm.shared.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Small helpers for JdbcTemplate code: pgjdbc takes OffsetDateTime, not Instant. */
public final class Db {
    private Db() {}

    public static OffsetDateTime ts(Instant i) { return i == null ? null : i.atOffset(ZoneOffset.UTC); }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException { return rs.getObject(col, UUID.class); }

    public static LocalDate date(ResultSet rs, String col) throws SQLException { return rs.getObject(col, LocalDate.class); }

    public static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static <E extends Enum<E>> E enumOf(Class<E> type, String v) { return v == null ? null : Enum.valueOf(type, v); }

    public static String name(Enum<?> e) { return e == null ? null : e.name(); }
}
