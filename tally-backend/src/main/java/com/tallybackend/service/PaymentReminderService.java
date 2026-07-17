package com.tallybackend.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PaymentReminderService {

    private final PaymentReminderRepository repository;

    public PaymentReminderService(PaymentReminderRepository repository) {
        this.repository = repository;
    }

    public List<PaymentReminder> findAll() {
        return repository.findAll();
    }

    public PaymentReminder create(Map<String, Object> payload, String actor) {
        PaymentReminder reminder = new PaymentReminder();
        reminder.setPartyName(requiredText(payload, "partyName"));
        reminder.setReminderType(normalizeReminderType(optionalText(payload, "reminderType"), "RECEIVABLE"));
        reminder.setContactName(optionalText(payload, "contactName"));
        reminder.setEmail(optionalText(payload, "email"));
        reminder.setMobile(optionalText(payload, "mobile"));
        reminder.setDueDate(requiredDate(payload, "dueDate"));
        reminder.setAmount(requiredAmount(payload, "amount"));
        reminder.setCurrencyCode(normalizeCurrency(optionalText(payload, "currencyCode"), "INR"));
        reminder.setStatus(normalizeStatus(optionalText(payload, "status"), "PENDING"));
        reminder.setNotes(optionalText(payload, "notes"));
        reminder.setCreatedBy(actor);
        reminder.setUpdatedBy(actor);
        reminder.setCreatedAt(LocalDateTime.now());
        reminder.setUpdatedAt(reminder.getCreatedAt());
        Long id = repository.insert(reminder);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to create payment reminder");
        }
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Payment reminder created but not found"));
    }

    public PaymentReminder markSent(Long id, String actor) {
        PaymentReminder reminder = require(id);
        repository.updateStatus(reminder.getId(), "SENT", actor, LocalDateTime.now());
        return require(id);
    }

    public PaymentReminder markResolved(Long id, String actor) {
        PaymentReminder reminder = require(id);
        repository.updateStatus(reminder.getId(), "RESOLVED", actor, reminder.getLastSentAt());
        return require(id);
    }

    public boolean delete(Long id) {
        require(id);
        return repository.deleteById(id);
    }

    private PaymentReminder require(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment reminder not found"));
    }

    private String requiredText(Map<String, Object> payload, String key) {
        String value = optionalText(payload, key);
        if (value == null || value.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
        }
        return value;
    }

    private String optionalText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private LocalDate requiredDate(Map<String, Object> payload, String key) {
        String value = requiredText(payload, key);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be YYYY-MM-DD");
        }
    }

    private BigDecimal requiredAmount(Map<String, Object> payload, String key) {
        String value = requiredText(payload, key);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a valid amount");
        }
    }

    private String normalizeReminderType(String value, String fallback) {
        String normalized = (value == null ? fallback : value).trim().toUpperCase(Locale.ROOT);
        if (!"RECEIVABLE".equals(normalized) && !"PAYABLE".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reminderType must be RECEIVABLE or PAYABLE");
        }
        return normalized;
    }

    private String normalizeStatus(String value, String fallback) {
        String normalized = (value == null ? fallback : value).trim().toUpperCase(Locale.ROOT);
        if (!"PENDING".equals(normalized) && !"SENT".equals(normalized) && !"RESOLVED".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be PENDING, SENT, or RESOLVED");
        }
        return normalized;
    }

    private String normalizeCurrency(String value, String fallback) {
        String normalized = (value == null ? fallback : value).trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? fallback : normalized;
    }
}
