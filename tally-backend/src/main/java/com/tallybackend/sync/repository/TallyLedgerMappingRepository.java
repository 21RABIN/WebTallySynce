package com.tallybackend.sync.repository;

import com.tallybackend.sync.entity.TallyLedgerMapping;
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
public class TallyLedgerMappingRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TallyLedgerMapping> rowMapper = (rs, rowNum) -> {
        TallyLedgerMapping mapping = new TallyLedgerMapping();
        mapping.setId(rs.getLong("id"));
        mapping.setBusinessUnitId(rs.getLong("business_unit_id"));
        mapping.setErpLedgerType(rs.getString("erp_ledger_type"));
        mapping.setErpLedgerName(rs.getString("erp_ledger_name"));
        mapping.setTallyLedgerName(rs.getString("tally_ledger_name"));
        mapping.setTallyGroupName(rs.getString("tally_group_name"));
        mapping.setActive(rs.getBoolean("is_active"));
        mapping.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        mapping.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return mapping;
    };

    public TallyLedgerMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TallyLedgerMapping> findAll() {
        return jdbcTemplate.query(
                "SELECT id, business_unit_id, erp_ledger_type, erp_ledger_name, tally_ledger_name, tally_group_name, is_active, created_at, updated_at " +
                        "FROM tally_ledger_mappings ORDER BY business_unit_id, erp_ledger_type, id",
                rowMapper
        );
    }

    public Optional<TallyLedgerMapping> findById(Long id) {
        List<TallyLedgerMapping> items = jdbcTemplate.query(
                "SELECT id, business_unit_id, erp_ledger_type, erp_ledger_name, tally_ledger_name, tally_group_name, is_active, created_at, updated_at " +
                        "FROM tally_ledger_mappings WHERE id = ?",
                rowMapper,
                id
        );
        return items.isEmpty() ? Optional.<TallyLedgerMapping>empty() : Optional.of(items.get(0));
    }

    public List<TallyLedgerMapping> findByBusinessUnitId(Long businessUnitId) {
        return jdbcTemplate.query(
                "SELECT id, business_unit_id, erp_ledger_type, erp_ledger_name, tally_ledger_name, tally_group_name, is_active, created_at, updated_at " +
                        "FROM tally_ledger_mappings WHERE business_unit_id = ? ORDER BY erp_ledger_type, id",
                rowMapper,
                businessUnitId
        );
    }

    public Optional<TallyLedgerMapping> findActiveByBusinessUnitIdAndErpLedgerType(Long businessUnitId, String erpLedgerType) {
        List<TallyLedgerMapping> items = jdbcTemplate.query(
                "SELECT id, business_unit_id, erp_ledger_type, erp_ledger_name, tally_ledger_name, tally_group_name, is_active, created_at, updated_at " +
                        "FROM tally_ledger_mappings WHERE business_unit_id = ? AND erp_ledger_type = ? AND is_active = true ORDER BY id LIMIT 1",
                rowMapper,
                businessUnitId,
                erpLedgerType
        );
        return items.isEmpty() ? Optional.<TallyLedgerMapping>empty() : Optional.of(items.get(0));
    }

    public Long insert(TallyLedgerMapping mapping) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tally_ledger_mappings (business_unit_id, erp_ledger_type, erp_ledger_name, tally_ledger_name, tally_group_name, is_active, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, mapping.getBusinessUnitId() == null ? 0L : mapping.getBusinessUnitId());
            statement.setString(2, mapping.getErpLedgerType());
            statement.setString(3, mapping.getErpLedgerName());
            statement.setString(4, mapping.getTallyLedgerName());
            statement.setString(5, mapping.getTallyGroupName());
            statement.setBoolean(6, mapping.getActive() == null || mapping.getActive());
            statement.setTimestamp(7, toTimestamp(mapping.getCreatedAt()));
            statement.setTimestamp(8, toTimestamp(mapping.getUpdatedAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public void update(TallyLedgerMapping mapping) {
        jdbcTemplate.update(
                "UPDATE tally_ledger_mappings SET business_unit_id = ?, erp_ledger_type = ?, erp_ledger_name = ?, tally_ledger_name = ?, tally_group_name = ?, is_active = ?, created_at = ?, updated_at = ? WHERE id = ?",
                mapping.getBusinessUnitId(),
                mapping.getErpLedgerType(),
                mapping.getErpLedgerName(),
                mapping.getTallyLedgerName(),
                mapping.getTallyGroupName(),
                mapping.getActive() == null || mapping.getActive(),
                toTimestamp(mapping.getCreatedAt()),
                toTimestamp(mapping.getUpdatedAt()),
                mapping.getId()
        );
    }

    public boolean deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM tally_ledger_mappings WHERE id = ?", id) > 0;
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
