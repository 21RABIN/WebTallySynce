package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.TallyLedgerMappingRequest;
import com.tallybackend.sync.entity.TallyLedgerMapping;
import com.tallybackend.sync.service.TallyLedgerMappingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tally-mappings/ledgers")
@Tag(name = "Tally Ledger Mapping")
public class TallyLedgerMappingController {

    private final TallyLedgerMappingService tallyLedgerMappingService;

    public TallyLedgerMappingController(TallyLedgerMappingService tallyLedgerMappingService) {
        this.tallyLedgerMappingService = tallyLedgerMappingService;
    }

    @GetMapping
    public List<TallyLedgerMapping> list() {
        return tallyLedgerMappingService.findAll();
    }

    @GetMapping("/{id}")
    public TallyLedgerMapping get(@PathVariable Long id) {
        return tallyLedgerMappingService.findById(id);
    }

    @PostMapping
    public TallyLedgerMapping create(@RequestBody TallyLedgerMappingRequest request) {
        return tallyLedgerMappingService.create(request);
    }

    @PutMapping("/{id}")
    public TallyLedgerMapping update(@PathVariable Long id, @RequestBody TallyLedgerMappingRequest request) {
        return tallyLedgerMappingService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        tallyLedgerMappingService.delete(id);
    }

    @PostMapping("/defaults/{businessUnitId}")
    public List<TallyLedgerMapping> seedDefaults(@PathVariable Long businessUnitId) {
        return tallyLedgerMappingService.seedDefaults(businessUnitId);
    }
}
