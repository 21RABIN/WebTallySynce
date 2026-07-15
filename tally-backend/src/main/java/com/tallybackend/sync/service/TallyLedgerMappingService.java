package com.tallybackend.sync.service;

import com.tallybackend.sync.dto.TallyLedgerMappingRequest;
import com.tallybackend.sync.entity.TallyLedgerMapping;
import com.tallybackend.sync.exception.SyncApiException;
import com.tallybackend.sync.repository.TallyLedgerMappingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TallyLedgerMappingService {
    private static final Map<String, String> DEFAULT_LEDGER_MAPPINGS = new LinkedHashMap<String, String>();

    static {
        DEFAULT_LEDGER_MAPPINGS.put("SALES", "Sales Account");
        DEFAULT_LEDGER_MAPPINGS.put("PURCHASE", "Purchase Account");
        DEFAULT_LEDGER_MAPPINGS.put("OUTPUT_CGST", "Output CGST");
        DEFAULT_LEDGER_MAPPINGS.put("OUTPUT_SGST", "Output SGST");
        DEFAULT_LEDGER_MAPPINGS.put("OUTPUT_IGST", "Output IGST");
        DEFAULT_LEDGER_MAPPINGS.put("INPUT_CGST", "Input CGST");
        DEFAULT_LEDGER_MAPPINGS.put("INPUT_SGST", "Input SGST");
        DEFAULT_LEDGER_MAPPINGS.put("INPUT_IGST", "Input IGST");
        DEFAULT_LEDGER_MAPPINGS.put("CASH", "Cash");
        DEFAULT_LEDGER_MAPPINGS.put("BANK", "Bank Account");
        DEFAULT_LEDGER_MAPPINGS.put("ROUND_OFF", "Round Off");
        DEFAULT_LEDGER_MAPPINGS.put("DISCOUNT_ALLOWED", "Discount Allowed");
        DEFAULT_LEDGER_MAPPINGS.put("DISCOUNT_RECEIVED", "Discount Received");
    }

    private final TallyLedgerMappingRepository repository;

    public TallyLedgerMappingService(TallyLedgerMappingRepository repository) {
        this.repository = repository;
    }

    public List<TallyLedgerMapping> findAll() {
        return repository.findAll();
    }

    public TallyLedgerMapping findById(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new SyncApiException(HttpStatus.NOT_FOUND, "Ledger mapping not found"));
    }

    public TallyLedgerMapping create(TallyLedgerMappingRequest request) {
        validateRequest(request);
        LocalDateTime now = LocalDateTime.now();
        TallyLedgerMapping mapping = new TallyLedgerMapping();
        mapping.setBusinessUnitId(request.getBusinessUnitId());
        mapping.setErpLedgerType(normalizeType(request.getErpLedgerType()));
        mapping.setErpLedgerName(request.getErpLedgerName());
        mapping.setTallyLedgerName(request.getTallyLedgerName());
        mapping.setTallyGroupName(request.getTallyGroupName());
        mapping.setActive(request.getIsActive() == null || request.getIsActive());
        mapping.setCreatedAt(now);
        mapping.setUpdatedAt(now);
        Long id = repository.insert(mapping);
        return findById(id);
    }

    public TallyLedgerMapping update(Long id, TallyLedgerMappingRequest request) {
        validateRequest(request);
        TallyLedgerMapping current = findById(id);
        current.setBusinessUnitId(request.getBusinessUnitId());
        current.setErpLedgerType(normalizeType(request.getErpLedgerType()));
        current.setErpLedgerName(request.getErpLedgerName());
        current.setTallyLedgerName(request.getTallyLedgerName());
        current.setTallyGroupName(request.getTallyGroupName());
        current.setActive(request.getIsActive() == null || request.getIsActive());
        current.setUpdatedAt(LocalDateTime.now());
        repository.update(current);
        return findById(id);
    }

    public boolean delete(Long id) {
        return repository.deleteById(id);
    }

    public List<TallyLedgerMapping> seedDefaults(Long businessUnitId) {
        if (businessUnitId == null) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "businessUnitId is required");
        }
        List<TallyLedgerMapping> created = new ArrayList<TallyLedgerMapping>();
        for (Map.Entry<String, String> entry : DEFAULT_LEDGER_MAPPINGS.entrySet()) {
            if (repository.findActiveByBusinessUnitIdAndErpLedgerType(businessUnitId, entry.getKey()).isPresent()) {
                continue;
            }
            TallyLedgerMappingRequest request = new TallyLedgerMappingRequest();
            request.setBusinessUnitId(businessUnitId);
            request.setErpLedgerType(entry.getKey());
            request.setErpLedgerName(entry.getKey());
            request.setTallyLedgerName(entry.getValue());
            request.setTallyGroupName("Primary");
            request.setIsActive(true);
            created.add(create(request));
        }
        return created;
    }

    public TallyLedgerMapping getRequiredMapping(Long businessUnitId, String erpLedgerType) {
        if (businessUnitId == null) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "businessUnitId is required for ledger mapping");
        }
        return repository.findActiveByBusinessUnitIdAndErpLedgerType(businessUnitId, normalizeType(erpLedgerType))
                .orElseThrow(() -> new SyncApiException(
                        HttpStatus.BAD_REQUEST,
                        "Ledger mapping not configured for type " + normalizeType(erpLedgerType)
                ));
    }

    public List<String> defaultMappingTypes() {
        return Arrays.asList(
                "SALES", "PURCHASE", "OUTPUT_CGST", "OUTPUT_SGST", "OUTPUT_IGST",
                "INPUT_CGST", "INPUT_SGST", "INPUT_IGST", "CASH", "BANK",
                "ROUND_OFF", "DISCOUNT_ALLOWED", "DISCOUNT_RECEIVED", "CUSTOMER",
                "SUPPLIER", "STOCK", "EXPENSE", "INCOME"
        );
    }

    private void validateRequest(TallyLedgerMappingRequest request) {
        if (request == null) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "Ledger mapping request is required");
        }
        if (request.getBusinessUnitId() == null) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "businessUnitId is required");
        }
        if (request.getErpLedgerType() == null || request.getErpLedgerType().trim().isEmpty()) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "erpLedgerType is required");
        }
        if (request.getTallyLedgerName() == null || request.getTallyLedgerName().trim().isEmpty()) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "tallyLedgerName is required");
        }
    }

    private String normalizeType(String type) {
        return type == null ? null : type.trim().toUpperCase();
    }
}
