package com.tallybackend.web;

import com.tallybackend.service.ConnectorGatewayService;
import com.tallybackend.service.ResolvedConnectorTarget;
import com.tallybackend.service.TallyVoucherWriteQueueService;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/voucher-queue")
public class TallyVoucherQueueController {

    private final TallyVoucherWriteQueueService tallyVoucherWriteQueueService;
    private final ConnectorGatewayService connectorGatewayService;

    public TallyVoucherQueueController(TallyVoucherWriteQueueService tallyVoucherWriteQueueService,
                                       ConnectorGatewayService connectorGatewayService) {
        this.tallyVoucherWriteQueueService = tallyVoucherWriteQueueService;
        this.connectorGatewayService = connectorGatewayService;
    }

    @GetMapping
    public Map<String, Object> status(@RequestParam(value = "limit", defaultValue = "25") int limit,
                                      @RequestParam(value = "company", required = false) String company,
                                      @RequestParam(value = "connectorPath", required = false) String connectorPath,
                                      @RequestParam(value = "status", required = false) String status) {
        return tallyVoucherWriteQueueService.status(limit, company, connectorPath, status);
    }

    @PostMapping("/process")
    public Map<String, Object> processNow() {
        return tallyVoucherWriteQueueService.processNow();
    }

    @PostMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestParam(value = "company", required = false) String company,
                                       @RequestParam(value = "connectorPath", required = false) String connectorPath,
                                       @RequestParam(value = "connectorBaseUrl", required = false) String connectorBaseUrl,
                                       @RequestParam(value = "status", required = false) String status,
                                       @RequestParam(value = "olderThanMinutes", required = false) Integer olderThanMinutes) {
        return tallyVoucherWriteQueueService.cleanup(company, connectorPath, connectorBaseUrl, status, olderThanMinutes);
    }

    @PostMapping("/store")
    public ResponseEntity<String> storeOffline(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String connectorPath = asText(payload.get("path"));
        String action = asText(payload.get("action"));
        String company = asText(payload.get("company"));
        String contentType = asText(payload.get("contentType"));
        String requestBody = asText(payload.get("requestBody"));
        String reason = asText(payload.get("reason"));
        if (connectorPath.isEmpty() || requestBody.isEmpty()) {
          throw new IllegalArgumentException("path and requestBody are required.");
        }
        ResolvedConnectorTarget target = connectorGatewayService.resolveTargetForRequest(request);
        String requestedBy = request.getAttribute("auth.subject") == null ? null : String.valueOf(request.getAttribute("auth.subject"));
        return tallyVoucherWriteQueueService.queueManual(
                HttpMethod.POST,
                connectorPath,
                action,
                company,
                requestedBy,
                contentType.isEmpty() ? "application/json" : contentType,
                requestBody,
                target,
                reason.isEmpty() ? "Voucher saved offline from UI fallback." : reason,
                HttpStatus.BAD_GATEWAY,
                null
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("error", ex.getMessage());
        return response;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
