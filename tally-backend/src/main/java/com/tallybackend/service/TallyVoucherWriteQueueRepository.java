package com.tallybackend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class TallyVoucherWriteQueueRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<TallyVoucherWriteQueueEntry> rowMapper = (rs, rowNum) -> {
        TallyVoucherWriteQueueEntry entry = new TallyVoucherWriteQueueEntry();
        entry.setId(rs.getLong("id"));
        entry.setConnectorPath(rs.getString("connector_path"));
        entry.setHttpMethod(rs.getString("http_method"));
        entry.setActionName(rs.getString("action_name"));
        entry.setQueryString(rs.getString("query_string"));
        entry.setRequestBody(rs.getString("request_body"));
        entry.setContentType(rs.getString("content_type"));
        entry.setConnectorId(rs.getString("connector_id"));
        entry.setConnectorBaseUrl(rs.getString("connector_base_url"));
        entry.setAgentKey(rs.getString("agent_key"));
        entry.setCompany(rs.getString("company"));
        entry.setRequestedBy(rs.getString("requested_by"));
        entry.setStatus(rs.getString("status"));
        entry.setAttempts(rs.getInt("attempts"));
        entry.setMaxAttempts(rs.getInt("max_attempts"));
        entry.setLastError(rs.getString("last_error"));
        entry.setResponseBody(rs.getString("response_body"));
        entry.setCreatedAt(readInstant(rs, "created_at"));
        entry.setUpdatedAt(readInstant(rs, "updated_at"));
        entry.setLastAttemptAt(readInstant(rs, "last_attempt_at"));
        entry.setNextAttemptAt(readInstant(rs, "next_attempt_at"));
        entry.setCompletedAt(readInstant(rs, "completed_at"));
        entry.setEntityType(rs.getString("entity_type"));
        entry.setEntityName(rs.getString("entity_name"));
        entry.setSyncDirection(rs.getString("sync_direction"));
        entry.setConflictState(rs.getString("conflict_state"));
        entry.setConflictPayload(rs.getString("conflict_payload"));
        entry.setReviewState(rs.getString("review_state"));
        entry.setReviewedBy(rs.getString("reviewed_by"));
        entry.setReviewedAt(readInstant(rs, "reviewed_at"));
        entry.setOriginalVoucherNumber(rs.getString("original_voucher_number"));
        entry.setOfflineVoucherNumber(rs.getString("offline_voucher_number"));
        if ("QUEUED".equalsIgnoreCase(entry.getStatus()) && entry.getAttempts() <= 0) {
            entry.setLastAttemptAt(null);
            entry.setCompletedAt(null);
        }
        return entry;
    };

    public TallyVoucherWriteQueueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(TallyVoucherWriteQueueEntry entry) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tally_voucher_write_queue (" +
                            "connector_path, http_method, action_name, query_string, request_body, content_type, connector_id, connector_base_url, agent_key, company, requested_by, status, attempts, max_attempts, last_error, response_body, created_at, updated_at, last_attempt_at, next_attempt_at, completed_at, entity_type, entity_name, sync_direction, conflict_state, conflict_payload, review_state, reviewed_by, reviewed_at, original_voucher_number, offline_voucher_number" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, entry.getConnectorPath());
            statement.setString(2, entry.getHttpMethod());
            statement.setString(3, entry.getActionName());
            statement.setString(4, entry.getQueryString());
            statement.setString(5, entry.getRequestBody());
            statement.setString(6, entry.getContentType());
            statement.setString(7, entry.getConnectorId());
            statement.setString(8, entry.getConnectorBaseUrl());
            statement.setString(9, entry.getAgentKey());
            statement.setString(10, entry.getCompany());
            statement.setString(11, entry.getRequestedBy());
            statement.setString(12, entry.getStatus());
            statement.setInt(13, entry.getAttempts());
            statement.setInt(14, entry.getMaxAttempts());
            statement.setString(15, entry.getLastError());
            statement.setString(16, entry.getResponseBody());
            setTimestamp(statement, 17, entry.getCreatedAt());
            setTimestamp(statement, 18, entry.getUpdatedAt());
            setTimestamp(statement, 19, entry.getLastAttemptAt());
            setTimestamp(statement, 20, entry.getNextAttemptAt());
            setTimestamp(statement, 21, entry.getCompletedAt());
            statement.setString(22, entry.getEntityType());
            statement.setString(23, entry.getEntityName());
            statement.setString(24, entry.getSyncDirection());
            statement.setString(25, entry.getConflictState());
            statement.setString(26, entry.getConflictPayload());
            statement.setString(27, entry.getReviewState());
            statement.setString(28, entry.getReviewedBy());
            setTimestamp(statement, 29, entry.getReviewedAt());
            statement.setString(30, entry.getOriginalVoucherNumber());
            statement.setString(31, entry.getOfflineVoucherNumber());
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public TallyVoucherWriteQueueEntry findVoucherNumberDuplicate(String company,
                                                                  String connectorPath,
                                                                  String originalVoucherNumber) {
        String normalizedCompany = trimToNull(company);
        String normalizedPath = trimToNull(connectorPath);
        String normalizedVoucherNumber = trimToNull(originalVoucherNumber);
        if (normalizedPath == null || normalizedVoucherNumber == null) {
            return null;
        }
        List<TallyVoucherWriteQueueEntry> matches;
        if (normalizedCompany == null) {
            matches = jdbcTemplate.query(
                    "SELECT * FROM tally_voucher_write_queue " +
                            "WHERE company IS NULL AND connector_path = ? AND original_voucher_number = ? " +
                            "ORDER BY id DESC LIMIT 1",
                    rowMapper,
                    normalizedPath,
                    normalizedVoucherNumber
            );
        } else {
            matches = jdbcTemplate.query(
                    "SELECT * FROM tally_voucher_write_queue " +
                            "WHERE company = ? AND connector_path = ? AND original_voucher_number = ? " +
                            "ORDER BY id DESC LIMIT 1",
                    rowMapper,
                    normalizedCompany,
                    normalizedPath,
                    normalizedVoucherNumber
            );
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    public List<TallyVoucherWriteQueueEntry> findDueEntries(int limit, Instant now) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT * FROM tally_voucher_write_queue " +
                        "WHERE status IN ('QUEUED', 'RETRY') AND (next_attempt_at IS NULL OR next_attempt_at <= ?) " +
                        "AND COALESCE(review_state, 'PENDING_REVIEW') <> 'SKIPPED' " +
                        "AND COALESCE(review_state, 'PENDING_REVIEW') <> 'APPROVED_MARK_SYNCED' " +
                        "AND COALESCE(conflict_state, 'NONE') <> 'CONFLICT' " +
                        "ORDER BY created_at ASC, id ASC LIMIT ?",
                rowMapper,
                timestamp(now),
                limit
        );
    }

    public int resetStaleProcessing(Instant staleBefore, Instant nextAttemptAt) {
        return jdbcTemplate.update(
                "UPDATE tally_voucher_write_queue SET status = ?, last_error = ?, updated_at = ?, next_attempt_at = ? " +
                        "WHERE status = ? AND (last_attempt_at IS NULL OR last_attempt_at <= ?)",
                "RETRY",
                "Recovered stale PROCESSING entry for automatic retry.",
                timestamp(nextAttemptAt),
                timestamp(nextAttemptAt),
                "PROCESSING",
                timestamp(staleBefore)
        );
    }

    public List<TallyVoucherWriteQueueEntry> listRecent(int limit, String company, String connectorPath, String status) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM tally_voucher_write_queue");
        List<Object> params = new ArrayList<Object>();
        appendEqualsFilter(sql, params, "company", company);
        appendEqualsFilter(sql, params, "connector_path", connectorPath);
        appendEqualsFilter(sql, params, "status", status);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
    }

    public List<TallyVoucherWriteQueueEntry> listPendingByConnectorPath(String connectorPath, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT * FROM tally_voucher_write_queue " +
                        "WHERE connector_path = ? AND status IN ('QUEUED', 'RETRY', 'PROCESSING') " +
                        "ORDER BY created_at ASC, id ASC LIMIT ?",
                rowMapper,
                connectorPath,
                limit
        );
    }

    public List<TallyVoucherWriteQueueEntry> listVisibleByConnectorPath(String connectorPath, int limit, Instant appliedSince) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT * FROM tally_voucher_write_queue " +
                        "WHERE connector_path = ? AND (" +
                        "status IN ('QUEUED', 'RETRY', 'PROCESSING') " +
                        "OR (status = 'APPLIED' AND completed_at IS NOT NULL AND completed_at >= ?)" +
                        ") ORDER BY created_at ASC, id ASC LIMIT ?",
                rowMapper,
                connectorPath,
                timestamp(appliedSince),
                limit
        );
    }

    public List<TallyVoucherWriteQueueEntry> listReviewableByCompany(String company, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        if (trimToNull(company) == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM tally_voucher_write_queue " +
                            "WHERE status IN ('QUEUED', 'RETRY', 'PROCESSING', 'FAILED', 'APPLIED') " +
                            "AND COALESCE(review_state, 'PENDING_REVIEW') NOT IN ('SYNCED', 'SKIPPED', 'DISMISSED') " +
                            "ORDER BY created_at DESC, id DESC LIMIT ?",
                    rowMapper,
                    limit
            );
        }
        return jdbcTemplate.query(
                "SELECT * FROM tally_voucher_write_queue " +
                        "WHERE company = ? AND status IN ('QUEUED', 'RETRY', 'PROCESSING', 'FAILED', 'APPLIED') " +
                        "AND COALESCE(review_state, 'PENDING_REVIEW') NOT IN ('SYNCED', 'SKIPPED', 'DISMISSED') " +
                        "ORDER BY created_at DESC, id DESC LIMIT ?",
                rowMapper,
                trimToNull(company),
                limit
        );
    }

    public List<TallyVoucherWriteQueueEntry> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM tally_voucher_write_queue WHERE id IN (");
        Object[] params = new Object[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
            params[i] = ids.get(i);
        }
        sql.append(") ORDER BY created_at ASC, id ASC");
        return jdbcTemplate.query(sql.toString(), rowMapper, params);
    }

    public int markProcessing(Long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE tally_voucher_write_queue SET status = ?, updated_at = ?, last_attempt_at = ? WHERE id = ? AND status IN ('QUEUED', 'RETRY')",
                "PROCESSING",
                timestamp(now),
                timestamp(now),
                id
        );
    }

    public void markApplied(Long id, int attempts, String responseBody, Instant now) {
        jdbcTemplate.update(
                "UPDATE tally_voucher_write_queue SET status = ?, attempts = ?, response_body = ?, updated_at = ?, completed_at = ?, next_attempt_at = NULL, last_error = NULL, conflict_state = ?, review_state = ? WHERE id = ?",
                "APPLIED",
                attempts,
                responseBody,
                timestamp(now),
                timestamp(now),
                "NONE",
                "SYNCED",
                id
        );
    }

    public void markRetry(Long id, int attempts, String error, String responseBody, Instant now, Instant nextAttemptAt) {
        jdbcTemplate.update(
                "UPDATE tally_voucher_write_queue SET status = ?, attempts = ?, last_error = ?, response_body = ?, updated_at = ?, next_attempt_at = ?, completed_at = NULL WHERE id = ?",
                "RETRY",
                attempts,
                error,
                responseBody,
                timestamp(now),
                timestamp(nextAttemptAt),
                id
        );
    }

    public void markFailed(Long id, int attempts, String error, String responseBody, Instant now) {
        jdbcTemplate.update(
                "UPDATE tally_voucher_write_queue SET status = ?, attempts = ?, last_error = ?, response_body = ?, updated_at = ?, completed_at = ?, next_attempt_at = NULL WHERE id = ?",
                "FAILED",
                attempts,
                error,
                responseBody,
                timestamp(now),
                timestamp(now),
                id
        );
    }

    public int deleteMatching(List<String> statuses,
                              String company,
                              String connectorPath,
                              String connectorBaseUrl,
                              Instant updatedBefore) {
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM tally_voucher_write_queue WHERE status IN (");
        List<Object> params = new ArrayList<Object>();
        for (int i = 0; i < statuses.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
            params.add(statuses.get(i));
        }
        sql.append(')');
        appendEqualsFilter(sql, params, "company", company);
        appendEqualsFilter(sql, params, "connector_path", connectorPath);
        appendEqualsFilter(sql, params, "connector_base_url", connectorBaseUrl);
        if (updatedBefore != null) {
            sql.append(" AND updated_at <= ?");
            params.add(timestamp(updatedBefore));
        }
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    public int deleteTerminalHistory(String company, Instant updatedBefore) {
        StringBuilder sql = new StringBuilder(
                "DELETE FROM tally_voucher_write_queue WHERE (" +
                        "(status = 'APPLIED' AND COALESCE(review_state, 'SYNCED') IN ('SYNCED', 'APPROVED_MARK_SYNCED')) " +
                        "OR COALESCE(review_state, 'PENDING_REVIEW') IN ('SKIPPED', 'DISMISSED')" +
                        ")"
        );
        List<Object> params = new ArrayList<Object>();
        appendEqualsFilter(sql, params, "company", company);
        if (updatedBefore != null) {
            sql.append(" AND updated_at <= ?");
            params.add(timestamp(updatedBefore));
        }
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    public void updateMetadata(Long id,
                               String entityType,
                               String entityName,
                               String syncDirection,
                               String conflictState,
                               String conflictPayload,
                               String reviewState,
                               String reviewedBy,
                               Instant reviewedAt) {
        jdbcTemplate.update(
                "UPDATE tally_voucher_write_queue SET entity_type = ?, entity_name = ?, sync_direction = ?, conflict_state = ?, conflict_payload = ?, review_state = ?, reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                entityType,
                entityName,
                syncDirection,
                conflictState,
                conflictPayload,
                reviewState,
                reviewedBy,
                timestamp(reviewedAt),
                id
        );
    }

    public void updateReconciliationState(Long id,
                                          String conflictState,
                                          String conflictPayload,
                                          String reviewState,
                                          String reviewedBy,
                                          Instant reviewedAt) {
        jdbcTemplate.update(
                "UPDATE tally_voucher_write_queue SET conflict_state = ?, conflict_payload = ?, review_state = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ? WHERE id = ?",
                conflictState,
                conflictPayload,
                reviewState,
                reviewedBy,
                timestamp(reviewedAt),
                timestamp(reviewedAt == null ? Instant.now() : reviewedAt),
                id
        );
    }

    private void appendEqualsFilter(StringBuilder sql, List<Object> params, String column, String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return;
        }
        sql.append(params.isEmpty() && sql.indexOf(" WHERE ") < 0 ? " WHERE " : " AND ");
        sql.append(column).append(" = ?");
        params.add(normalized);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private void setTimestamp(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP);
            return;
        }
        statement.setTimestamp(index, Timestamp.from(instant));
    }

    private Instant readInstant(ResultSet rs, String columnName) throws SQLException {
        String raw = rs.getString(columnName);
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty() || normalized.startsWith("0000-00-00")) {
            return null;
        }
        Timestamp timestamp = rs.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
