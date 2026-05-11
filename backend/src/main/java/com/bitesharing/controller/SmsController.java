package com.bitesharing.controller;

import com.bitesharing.service.SmsIntakeService;
import com.twilio.security.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsIntakeService smsIntakeService;

    @Value("${twilio.auth.token:}")
    private String twilioAuthToken;

    @Value("${twilio.webhook.validate-signature:true}")
    private boolean validateSignature;

    /** TwiML body text must be XML-escaped or Twilio rejects / mis-parses the response. */
    private static String escapeTwiMlText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> webhook(
            HttpServletRequest request,
            @RequestParam Map<String, String> form,
            @RequestParam("From") String from,
            @RequestParam("Body") String body) {
        if (validateSignature && twilioAuthToken != null && !twilioAuthToken.isBlank()) {
            String signature = request.getHeader("X-Twilio-Signature");
            if (signature == null || signature.isBlank()) {
                return ResponseEntity.status(403).body("<Response></Response>");
            }
            String url = request.getRequestURL().toString();
            if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
                url += "?" + request.getQueryString();
            }
            Map<String, String> params = new HashMap<>(form == null ? Map.of() : form);
            RequestValidator validator = new RequestValidator(twilioAuthToken);
            boolean ok = validator.validate(url, params, signature);
            if (!ok) {
                return ResponseEntity.status(403).body("<Response></Response>");
            }
        }
        String msg = smsIntakeService.processInboundSms(from, body);
        return ResponseEntity.ok("<Response><Message>" + escapeTwiMlText(msg) + "</Message></Response>");
    }

    @PostMapping("/freshness-alert")
    public ResponseEntity<Map<String, String>> sendFreshnessAlert(
            @RequestParam String phone,
            @RequestParam String message) {
        smsIntakeService.sendFreshnessRiskSms(phone, message);
        return ResponseEntity.ok(Map.of("status", "queued"));
    }
}
