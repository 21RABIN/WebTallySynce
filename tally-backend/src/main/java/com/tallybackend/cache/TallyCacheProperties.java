package com.tallybackend.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class TallyCacheProperties {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    @Value("${tally.cache.sync.enabled:true}")
    private boolean enabled;

    @Value("${tally.cache.sync.interval-ms:60000}")
    private long intervalMs;

    @Value("${tally.cache.sync.stale-after-ms:300000}")
    private long staleAfterMs;

    @Value("${tally.cache.sync.company:}")
    private String company;

    public boolean isEnabled() {
        return enabled;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public long getStaleAfterMs() {
        return staleAfterMs;
    }

    public String getCompany() {
        return company == null ? "" : company.trim();
    }

    public String currentFinancialYearStart() {
        LocalDate today = LocalDate.now();
        int year = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return LocalDate.of(year, 4, 1).format(DATE_FORMATTER);
    }

    public String currentFinancialYearEnd() {
        LocalDate today = LocalDate.now();
        int year = today.getMonthValue() >= 4 ? today.getYear() + 1 : today.getYear();
        return LocalDate.of(year, 3, 31).format(DATE_FORMATTER);
    }

    public String today() {
        return LocalDate.now().format(DATE_FORMATTER);
    }
}
