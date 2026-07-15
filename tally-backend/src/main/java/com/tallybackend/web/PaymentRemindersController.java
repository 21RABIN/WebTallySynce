package com.tallybackend.web;

import com.tallybackend.service.PaymentReminder;
import com.tallybackend.service.PaymentReminderService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payment-reminders")
public class PaymentRemindersController {

    private final PaymentReminderService paymentReminderService;

    public PaymentRemindersController(PaymentReminderService paymentReminderService) {
        this.paymentReminderService = paymentReminderService;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<PaymentReminder> reminders = paymentReminderService.findAll();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("reminders", reminders);
        return response;
    }

    @PostMapping
    public PaymentReminder create(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        return paymentReminderService.create(payload, actor(request));
    }

    @PostMapping("/{id}/send")
    public PaymentReminder send(@PathVariable Long id, HttpServletRequest request) {
        return paymentReminderService.markSent(id, actor(request));
    }

    @PostMapping("/{id}/resolve")
    public PaymentReminder resolve(@PathVariable Long id, HttpServletRequest request) {
        return paymentReminderService.markResolved(id, actor(request));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("deleted", paymentReminderService.delete(id));
        response.put("id", id);
        return response;
    }

    private String actor(HttpServletRequest request) {
        Object subject = request.getAttribute("auth.subject");
        return subject == null ? "unknown" : String.valueOf(subject);
    }
}
