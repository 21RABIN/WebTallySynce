package com.tallybackend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserAccountRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<UserAccount> rowMapper = (rs, rowNum) -> {
        UserAccount account = new UserAccount();
        account.setUsername(rs.getString("username"));
        account.setDisplayName(rs.getString("display_name"));
        account.setPasswordHash(rs.getString("password_hash"));
        account.setRoles(UserAccountSql.toRoles(rs.getString("roles")));
        account.setActive(rs.getBoolean("active"));
        account.setFirstSeenAt(UserAccountSql.toInstant(rs.getTimestamp("first_seen_at")));
        account.setLastSeenAt(UserAccountSql.toInstant(rs.getTimestamp("last_seen_at")));
        return account;
    };

    public UserAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> items = jdbcTemplate.query(
                "SELECT username, display_name, password_hash, roles, active, first_seen_at, last_seen_at " +
                        "FROM auth_users WHERE username = ?",
                rowMapper,
                username
        );
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(items.get(0));
    }

    public List<UserAccount> findAll() {
        return jdbcTemplate.query(
                "SELECT username, display_name, password_hash, roles, active, first_seen_at, last_seen_at " +
                        "FROM auth_users ORDER BY username",
                rowMapper
        );
    }

    public boolean exists(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM auth_users WHERE username = ?",
                Integer.class,
                username
        );
        return count != null && count > 0;
    }

    public void insert(UserAccount account) {
        jdbcTemplate.update(
                "INSERT INTO auth_users (username, display_name, password_hash, roles, active, first_seen_at, last_seen_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                account.getUsername(),
                account.getDisplayName(),
                account.getPasswordHash(),
                UserAccountSql.toRolesCsv(account.getRoles()),
                account.isActive(),
                UserAccountSql.toTimestamp(account.getFirstSeenAt()),
                UserAccountSql.toTimestamp(account.getLastSeenAt())
        );
    }

    public void update(UserAccount account) {
        jdbcTemplate.update(
                "UPDATE auth_users SET display_name = ?, password_hash = ?, roles = ?, active = ?, first_seen_at = ?, last_seen_at = ? " +
                        "WHERE username = ?",
                account.getDisplayName(),
                account.getPasswordHash(),
                UserAccountSql.toRolesCsv(account.getRoles()),
                account.isActive(),
                UserAccountSql.toTimestamp(account.getFirstSeenAt()),
                UserAccountSql.toTimestamp(account.getLastSeenAt()),
                account.getUsername()
        );
    }

    public boolean touchLogin(String username, Instant now) {
        return jdbcTemplate.update(
                "UPDATE auth_users SET first_seen_at = COALESCE(first_seen_at, ?), last_seen_at = ? WHERE username = ?",
                UserAccountSql.toTimestamp(now),
                UserAccountSql.toTimestamp(now),
                username
        ) > 0;
    }

    public boolean deactivate(String username, Instant now) {
        return jdbcTemplate.update(
                "UPDATE auth_users SET active = false, last_seen_at = ? WHERE username = ?",
                UserAccountSql.toTimestamp(now),
                username
        ) > 0;
    }

    static final class UserAccountSql {
        private UserAccountSql() {
        }

        static List<String> toRoles(String csv) {
            return UserDirectorySupport.parseRolesCsv(csv);
        }

        static String toRolesCsv(List<String> roles) {
            return UserDirectorySupport.rolesToCsv(roles);
        }

        static Instant toInstant(Timestamp timestamp) {
            return timestamp == null ? null : timestamp.toInstant();
        }

        static Timestamp toTimestamp(Instant instant) {
            return instant == null ? null : Timestamp.from(instant);
        }
    }
}
