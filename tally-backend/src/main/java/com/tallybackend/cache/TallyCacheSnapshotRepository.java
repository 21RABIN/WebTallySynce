package com.tallybackend.cache;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Repository
public class TallyCacheSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<TallySyncRun> runRowMapper = (rs, rowNum) -> {
        TallySyncRun run = new TallySyncRun();
        run.setId(rs.getLong("id"));
        run.setStatus(rs.getString("status"));
        run.setConnectorId(rs.getString("connector_id"));
        run.setCompany(rs.getString("company"));
        run.setStartedAt(readInstant(rs, "started_at"));
        run.setCompletedAt(readInstant(rs, "completed_at"));
        run.setErrorMessage(rs.getString("error_message"));
        return run;
    };

    private final RowMapper<TallyDatasetSnapshot> snapshotRowMapper = (rs, rowNum) -> {
        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(rs.getLong("id"));
        snapshot.setRunId(rs.getObject("run_id") == null ? null : rs.getLong("run_id"));
        snapshot.setDatasetKey(rs.getString("dataset_key"));
        snapshot.setSnapshotKey(rs.getString("snapshot_key"));
        snapshot.setStatus(rs.getString("status"));
        snapshot.setCurrent(rs.getBoolean("is_current"));
        snapshot.setConnectorId(rs.getString("connector_id"));
        snapshot.setCompany(rs.getString("company"));
        snapshot.setRequestParamsJson(rs.getString("request_params_json"));
        snapshot.setContentHash(rs.getString("content_hash"));
        snapshot.setRangeStart(rs.getString("range_start"));
        snapshot.setRangeEnd(rs.getString("range_end"));
        snapshot.setRowCount(rs.getInt("row_count"));
        snapshot.setStaleAfterMs(rs.getLong("stale_after_ms"));
        snapshot.setErrorMessage(rs.getString("error_message"));
        snapshot.setFetchedAt(readInstant(rs, "fetched_at"));
        snapshot.setCompletedAt(readInstant(rs, "completed_at"));
        return snapshot;
    };

    public TallyCacheSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long createRun(String status, String connectorId, String company, Instant startedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tally_sync_runs (status, connector_id, company, started_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, status);
            statement.setString(2, connectorId);
            statement.setString(3, company);
            statement.setTimestamp(4, Timestamp.from(startedAt));
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public void completeRun(Long runId, String status, String errorMessage, Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE tally_sync_runs SET status = ?, error_message = ?, completed_at = ? WHERE id = ?",
                status,
                errorMessage,
                Timestamp.from(completedAt),
                runId
        );
    }

    public Long createSnapshot(Long runId,
                               TallyCacheDataset dataset,
                               String snapshotKey,
                               String connectorId,
                               String company,
                               String requestParamsJson,
                               String contentHash,
                               String rangeStart,
                               String rangeEnd,
                               long staleAfterMs,
                               Instant fetchedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tally_dataset_snapshots (run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, stale_after_ms, fetched_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            if (runId == null) {
                statement.setObject(1, null);
            } else {
                statement.setLong(1, runId);
            }
            statement.setString(2, dataset.getKey());
            statement.setString(3, snapshotKey);
            statement.setString(4, "RUNNING");
            statement.setBoolean(5, false);
            statement.setString(6, connectorId);
            statement.setString(7, company);
            statement.setString(8, requestParamsJson);
            statement.setString(9, contentHash);
            statement.setString(10, rangeStart);
            statement.setString(11, rangeEnd);
            statement.setLong(12, staleAfterMs);
            statement.setTimestamp(13, Timestamp.from(fetchedAt));
            return statement;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    @Transactional
    public void markSnapshotSuccess(Long snapshotId, TallyCacheDataset dataset, String snapshotKey, int rowCount, String contentHash, Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE tally_dataset_snapshots SET is_current = false WHERE dataset_key = ? AND snapshot_key = ?",
                dataset.getKey(),
                snapshotKey
        );
        jdbcTemplate.update(
                "UPDATE tally_dataset_snapshots SET status = ?, is_current = ?, row_count = ?, content_hash = ?, completed_at = ?, error_message = null WHERE id = ?",
                "SUCCESS",
                true,
                rowCount,
                contentHash,
                Timestamp.from(completedAt),
                snapshotId
        );
    }

    public void refreshCurrentSnapshot(Long snapshotId,
                                       Long runId,
                                       String connectorId,
                                       String company,
                                       String requestParamsJson,
                                       String contentHash,
                                       String rangeStart,
                                       String rangeEnd,
                                       int rowCount,
                                       long staleAfterMs,
                                       Instant fetchedAt,
                                       Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE tally_dataset_snapshots " +
                        "SET run_id = ?, status = ?, is_current = ?, connector_id = ?, company = ?, request_params_json = ?, content_hash = ?, " +
                        "range_start = ?, range_end = ?, row_count = ?, stale_after_ms = ?, fetched_at = ?, completed_at = ?, error_message = null " +
                        "WHERE id = ?",
                runId,
                "SUCCESS",
                true,
                connectorId,
                company,
                requestParamsJson,
                contentHash,
                rangeStart,
                rangeEnd,
                rowCount,
                staleAfterMs,
                Timestamp.from(fetchedAt),
                Timestamp.from(completedAt),
                snapshotId
        );
    }

    public void markSnapshotFailed(Long snapshotId, String errorMessage, Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE tally_dataset_snapshots SET status = ?, error_message = ?, completed_at = ? WHERE id = ?",
                "FAILED",
                errorMessage,
                Timestamp.from(completedAt),
                snapshotId
        );
    }

    @Transactional
    public void replaceGenericRows(Long snapshotId,
                                   TallyCacheDataset dataset,
                                   String snapshotKey,
                                   List<String> payloadRowsJson,
                                   String contentHash,
                                   Instant completedAt) {
        jdbcTemplate.update(
                "UPDATE tally_dataset_snapshots SET is_current = false WHERE dataset_key = ? AND snapshot_key = ?",
                dataset.getKey(),
                snapshotKey
        );
        jdbcTemplate.update(
                "DELETE FROM tally_generic_snapshot_rows WHERE snapshot_id = ?",
                snapshotId
        );
        List<String> rows = payloadRowsJson == null ? Collections.<String>emptyList() : payloadRowsJson;
        for (int i = 0; i < rows.size(); i++) {
            jdbcTemplate.update(
                    "INSERT INTO tally_generic_snapshot_rows (snapshot_id, row_index, payload_json) VALUES (?, ?, ?)",
                    snapshotId,
                    i,
                    rows.get(i)
            );
        }
        jdbcTemplate.update(
                "UPDATE tally_dataset_snapshots SET status = ?, is_current = ?, row_count = ?, content_hash = ?, completed_at = ?, error_message = null WHERE id = ?",
                "SUCCESS",
                true,
                rows.size(),
                contentHash,
                Timestamp.from(completedAt),
                snapshotId
        );
    }

    public TallyDatasetSnapshot findCurrent(TallyCacheDataset dataset, String snapshotKey) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots WHERE dataset_key = ? AND snapshot_key = ? AND is_current = true ORDER BY fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                snapshotKey
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findLatestSuccessfulNonEmpty(TallyCacheDataset dataset, String snapshotKey) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots " +
                        "WHERE dataset_key = ? AND snapshot_key = ? AND status = 'SUCCESS' AND row_count > 0 " +
                        "ORDER BY is_current DESC, fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                snapshotKey
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findCurrentCoveringRange(TallyCacheDataset dataset, String fromDate, String toDate) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots " +
                        "WHERE dataset_key = ? AND is_current = true " +
                        "AND (range_start IS NULL OR range_start <= ?) " +
                        "AND (range_end IS NULL OR range_end >= ?) " +
                        "ORDER BY fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                fromDate,
                toDate
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findCurrentCoveringRange(TallyCacheDataset dataset, String fromDate, String toDate, String company) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots " +
                        "WHERE dataset_key = ? AND is_current = true " +
                        "AND (? IS NULL OR company = ?) " +
                        "AND (range_start IS NULL OR range_start <= ?) " +
                        "AND (range_end IS NULL OR range_end >= ?) " +
                        "ORDER BY fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                company,
                company,
                fromDate,
                toDate
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findLatestSuccessfulNonEmptyCoveringRange(TallyCacheDataset dataset, String fromDate, String toDate) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots " +
                        "WHERE dataset_key = ? AND status = 'SUCCESS' AND row_count > 0 " +
                        "AND (range_start IS NULL OR range_start <= ?) " +
                        "AND (range_end IS NULL OR range_end >= ?) " +
                        "ORDER BY is_current DESC, fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                fromDate,
                toDate
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findLatestSuccessfulNonEmptyCoveringRange(TallyCacheDataset dataset, String fromDate, String toDate, String company) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots " +
                        "WHERE dataset_key = ? AND status = 'SUCCESS' AND row_count > 0 " +
                        "AND (? IS NULL OR company = ?) " +
                        "AND (range_start IS NULL OR range_start <= ?) " +
                        "AND (range_end IS NULL OR range_end >= ?) " +
                        "ORDER BY is_current DESC, fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                company,
                company,
                fromDate,
                toDate
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findLatestSuccessfulNonEmptyForDataset(TallyCacheDataset dataset) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots " +
                        "WHERE dataset_key = ? AND status = 'SUCCESS' AND row_count > 0 " +
                        "ORDER BY is_current DESC, fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey()
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findLatestSuccessfulNonEmptyForDataset(TallyCacheDataset dataset, String company) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots " +
                        "WHERE dataset_key = ? AND status = 'SUCCESS' AND row_count > 0 " +
                        "AND (? IS NULL OR company = ?) " +
                        "ORDER BY is_current DESC, fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                company,
                company
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public List<TallyDatasetSnapshot> listCurrentSnapshots() {
        return jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots WHERE is_current = true ORDER BY dataset_key, fetched_at DESC",
                snapshotRowMapper
        );
    }

    public List<TallySyncRun> listRecentRuns(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT id, status, connector_id, company, started_at, completed_at, error_message FROM tally_sync_runs ORDER BY started_at DESC, id DESC LIMIT ?",
                runRowMapper,
                limit
        );
    }

    public List<Long> listDuplicateSnapshotIds(TallyCacheDataset dataset, String snapshotKey, String contentHash, Long keepSnapshotId) {
        if (contentHash == null || contentHash.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT id FROM tally_dataset_snapshots WHERE dataset_key = ? AND snapshot_key = ? AND content_hash = ? AND id <> ?",
                (rs, rowNum) -> rs.getLong("id"),
                dataset.getKey(),
                snapshotKey,
                contentHash,
                keepSnapshotId
        );
    }

    public List<Long> listObsoleteSnapshotIds(TallyCacheDataset dataset, String snapshotKey, Long keepSnapshotId) {
        if (keepSnapshotId == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.query(
                "SELECT id FROM tally_dataset_snapshots WHERE dataset_key = ? AND snapshot_key = ? AND id <> ?",
                (rs, rowNum) -> rs.getLong("id"),
                dataset.getKey(),
                snapshotKey,
                keepSnapshotId
        );
    }

    public void deleteSnapshots(List<Long> snapshotIds) {
        if (snapshotIds == null || snapshotIds.isEmpty()) {
            return;
        }
        for (Long snapshotId : snapshotIds) {
            jdbcTemplate.update("DELETE FROM tally_dataset_snapshots WHERE id = ?", snapshotId);
        }
    }

    public TallyDatasetSnapshot findPreviousSuccessfulSnapshot(TallyCacheDataset dataset, String snapshotKey, Long excludeSnapshotId, String company) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots WHERE dataset_key = ? AND snapshot_key = ? AND status = 'SUCCESS' " +
                        "AND (? IS NULL OR id <> ?) AND (? IS NULL OR company = ?) " +
                        "ORDER BY fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                snapshotKey,
                excludeSnapshotId,
                excludeSnapshotId,
                company,
                company
        );
        return items.isEmpty() ? null : items.get(0);
    }

    public TallyDatasetSnapshot findBestRecentSuccessfulRangeSnapshot(TallyCacheDataset dataset,
                                                                      String rangeStart,
                                                                      String rangeEnd,
                                                                      Long excludeSnapshotId,
                                                                      String company) {
        List<TallyDatasetSnapshot> items = jdbcTemplate.query(
                "SELECT id, run_id, dataset_key, snapshot_key, status, is_current, connector_id, company, request_params_json, content_hash, range_start, range_end, row_count, stale_after_ms, error_message, fetched_at, completed_at " +
                        "FROM tally_dataset_snapshots WHERE dataset_key = ? AND status = 'SUCCESS' " +
                        "AND range_start = ? AND range_end <= ? " +
                        "AND (? IS NULL OR id <> ?) AND (? IS NULL OR company = ?) " +
                        "ORDER BY range_end DESC, row_count DESC, fetched_at DESC, id DESC LIMIT 1",
                snapshotRowMapper,
                dataset.getKey(),
                rangeStart,
                rangeEnd,
                excludeSnapshotId,
                excludeSnapshotId,
                company,
                company
        );
        return items.isEmpty() ? null : items.get(0);
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
