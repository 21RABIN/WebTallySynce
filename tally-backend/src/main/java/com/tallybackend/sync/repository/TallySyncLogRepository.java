package com.tallybackend.sync.repository;

import com.tallybackend.sync.entity.TallySyncLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class TallySyncLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TallySyncLog> rowMapper = (rs, rowNum) -> {
        TallySyncLog log = new TallySyncLog();
        log.setId(rs.getLong("id"));
        log.setEntityType(rs.getString("entity_type"));
        log.setEntityId(rs.getLong("entity_id"));
        log.setTallyType(rs.getString("tally_type"));
        log.setRequestXml(rs.getString("request_xml"));
        log.setResponseXml(rs.getString("response_xml"));
        log.setStatus(rs.getString("status"));
        log.setErrorMessage(rs.getString("error_message"));
        log.setTallyGuid(rs.getString("tally_guid"));
        log.setRetryCount(rs.getInt("retry_count"));
        log.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        log.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return log;
    };

    public TallySyncLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByEntityTypeAndEntityIdAndStatus(String entityType, Long entityId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM tally_sync_logs WHERE entity_type = ? AND entity_id = ? AND status = ?",
                Integer.class,
                entityType,
                entityId,
                status
        );
        return count != null && count > 0;
    }

    public List<TallySyncLog> findByEntityTypeAndEntityId(String entityType, Long entityId) {
        return jdbcTemplate.query(
                "SELECT id, entity_type, entity_id, tally_type, request_xml, response_xml, status, error_message, tally_guid, retry_count, created_at, updated_at " +
                        "FROM tally_sync_logs WHERE entity_type = ? AND entity_id = ? ORDER BY created_at DESC, id DESC",
                rowMapper,
                entityType,
                entityId
        );
    }

    public List<TallySyncLog> findByStatus(String status) {
        return jdbcTemplate.query(
                "SELECT id, entity_type, entity_id, tally_type, request_xml, response_xml, status, error_message, tally_guid, retry_count, created_at, updated_at " +
                        "FROM tally_sync_logs WHERE status = ? ORDER BY created_at DESC, id DESC",
                rowMapper,
                status
        );
    }

    public Optional<TallySyncLog> findById(Long id) {
        List<TallySyncLog> items = jdbcTemplate.query(
                "SELECT id, entity_type, entity_id, tally_type, request_xml, response_xml, status, error_message, tally_guid, retry_count, created_at, updated_at " +
                        "FROM tally_sync_logs WHERE id = ?",
                rowMapper,
                id
        );
        return items.isEmpty() ? Optional.<TallySyncLog>empty() : Optional.of(items.get(0));
    }

    public List<TallySyncLog> findAll() {
        return jdbcTemplate.query(
                "SELECT id, entity_type, entity_id, tally_type, request_xml, response_xml, status, error_message, tally_guid, retry_count, created_at, updated_at " +
                        "FROM tally_sync_logs ORDER BY created_at DESC, id DESC",
                rowMapper
        );
    }

    public Long insert(TallySyncLog log) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tally_sync_logs (entity_type, entity_id, tally_type, request_xml, response_xml, status, error_message, tally_guid, retry_count, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, log.getEntityType());
            statement.setLong(2, log.getEntityId() == null ? 0L : log.getEntityId());
            statement.setString(3, log.getTallyType());
            statement.setString(4, log.getRequestXml());
            statement.setString(5, log.getResponseXml());
            statement.setString(6, log.getStatus());
            statement.setString(7, log.getErrorMessage());
            statement.setString(8, log.getTallyGuid());
            statement.setInt(9, log.getRetryCount() == null ? 0 : log.getRetryCount());
            statement.setTimestamp(10, toTimestamp(log.getCreatedAt()));
            statement.setTimestamp(11, toTimestamp(log.getUpdatedAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public void update(TallySyncLog log) {
        jdbcTemplate.update(
                "UPDATE tally_sync_logs SET entity_type = ?, entity_id = ?, tally_type = ?, request_xml = ?, response_xml = ?, status = ?, error_message = ?, tally_guid = ?, retry_count = ?, created_at = ?, updated_at = ? WHERE id = ?",
                log.getEntityType(),
                log.getEntityId(),
                log.getTallyType(),
                log.getRequestXml(),
                log.getResponseXml(),
                log.getStatus(),
                log.getErrorMessage(),
                log.getTallyGuid(),
                log.getRetryCount() == null ? 0 : log.getRetryCount(),
                toTimestamp(log.getCreatedAt()),
                toTimestamp(log.getUpdatedAt()),
                log.getId()
        );
    }

    public boolean deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM tally_sync_logs WHERE id = ?", id) > 0;
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
