package com.tallybackend.web;

import com.tallybackend.service.TallyReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reconciliation")
public class TallyReconciliationController {

    private final TallyReconciliationService reconciliationService;

    public TallyReconciliationController(TallyReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(value = "company", required = false) String company) {
        return reconciliationService.status(company);
    }

    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam(value = "company", required = false) String company) {
        return reconciliationService.preview(company);
    }

    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        return reconciliationService.resolve(
                text(body == null ? null : body.get("company")),
                listOfMaps(body == null ? null : body.get("items")),
                request.getAttribute("auth.subject") == null ? null : String.valueOf(request.getAttribute("auth.subject"))
        );
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        return reconciliationService.sync(
                text(body == null ? null : body.get("company")),
                listOfMaps(body == null ? null : body.get("items")),
                request.getAttribute("auth.subject") == null ? null : String.valueOf(request.getAttribute("auth.subject"))
        );
    }

    @PostMapping("/dismiss")
    public Map<String, Object> dismiss(@RequestBody(required = false) Map<String, Object> body) {
        return reconciliationService.dismiss(text(body == null ? null : body.get("company")));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        return (List<Map<String, Object>>) value;
    }
}
