package com.tallybackend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentReminderRepository {

    private final JdbcTemplate jdbcTemplate;

    public PaymentReminderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PaymentReminder> findAll() {
        return jdbcTemplate.query(
                "SELECT id, party_name, reminder_type, contact_name, email, mobile, due_date, amount, currency_code, " +
                        "status, notes, created_by, updated_by, last_sent_at, created_at, updated_at " +
                        "FROM payment_reminders ORDER BY due_date ASC, created_at DESC",
                (rs, rowNum) -> mapRow(rs)
        );
    }

    public Optional<PaymentReminder> findById(Long id) {
        List<PaymentReminder> items = jdbcTemplate.query(
                "SELECT id, party_name, reminder_type, contact_name, email, mobile, due_date, amount, currency_code, " +
                        "status, notes, created_by, updated_by, last_sent_at, created_at, updated_at " +
                        "FROM payment_reminders WHERE id = ?",
                new Object[]{id},
                (rs, rowNum) -> mapRow(rs)
        );
        return items.isEmpty() ? Optional.<PaymentReminder>empty() : Optional.of(items.get(0));
    }

    public Long insert(PaymentReminder reminder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO payment_reminders (party_name, reminder_type, contact_name, email, mobile, due_date, amount, " +
                            "currency_code, status, notes, created_by, updated_by, last_sent_at, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, reminder.getPartyName());
            statement.setString(2, reminder.getReminderType());
            statement.setString(3, reminder.getContactName());
            statement.setString(4, reminder.getEmail());
            statement.setString(5, reminder.getMobile());
            statement.setDate(6, Date.valueOf(reminder.getDueDate()));
            statement.setBigDecimal(7, reminder.getAmount());
            statement.setString(8, reminder.getCurrencyCode());
            statement.setString(9, reminder.getStatus());
            statement.setString(10, reminder.getNotes());
            statement.setString(11, reminder.getCreatedBy());
            statement.setString(12, reminder.getUpdatedBy());
            if (reminder.getLastSentAt() != null) {
                statement.setTimestamp(13, Timestamp.valueOf(reminder.getLastSentAt()));
            } else {
                statement.setTimestamp(13, null);
            }
            statement.setTimestamp(14, Timestamp.valueOf(reminder.getCreatedAt()));
            statement.setTimestamp(15, Timestamp.valueOf(reminder.getUpdatedAt()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public void updateStatus(Long id, String status, String updatedBy, LocalDateTime lastSentAt) {
        jdbcTemplate.update(
                "UPDATE payment_reminders SET status = ?, updated_by = ?, last_sent_at = ?, updated_at = ? WHERE id = ?",
                status,
                updatedBy,
                lastSentAt == null ? null : Timestamp.valueOf(lastSentAt),
                Timestamp.valueOf(LocalDateTime.now()),
                id
        );
    }

    public boolean deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM payment_reminders WHERE id = ?", id) > 0;
    }

    private PaymentReminder mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        PaymentReminder reminder = new PaymentReminder();
        reminder.setId(rs.getLong("id"));
        reminder.setPartyName(rs.getString("party_name"));
        reminder.setReminderType(rs.getString("reminder_type"));
        reminder.setContactName(rs.getString("contact_name"));
        reminder.setEmail(rs.getString("email"));
        reminder.setMobile(rs.getString("mobile"));
        Date dueDate = rs.getDate("due_date");
        reminder.setDueDate(dueDate == null ? null : dueDate.toLocalDate());
        reminder.setAmount(rs.getBigDecimal("amount"));
        reminder.setCurrencyCode(rs.getString("currency_code"));
        reminder.setStatus(rs.getString("status"));
        reminder.setNotes(rs.getString("notes"));
        reminder.setCreatedBy(rs.getString("created_by"));
        reminder.setUpdatedBy(rs.getString("updated_by"));
        Timestamp lastSentAt = rs.getTimestamp("last_sent_at");
        reminder.setLastSentAt(lastSentAt == null ? null : lastSentAt.toLocalDateTime());
        Timestamp createdAt = rs.getTimestamp("created_at");
        reminder.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        reminder.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
        return reminder;
    }
}
