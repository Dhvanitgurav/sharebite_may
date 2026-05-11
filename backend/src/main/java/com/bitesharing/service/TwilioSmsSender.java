package com.bitesharing.service;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TwilioSmsSender {

    @Value("${twilio.account.sid:}")
    private String accountSid;

    @Value("${twilio.auth.token:}")
    private String authToken;

    @Value("${twilio.phone.number:}")
    private String fromNumber;

    private volatile boolean configured;

    @PostConstruct
    void init() {
        configured = accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank()
                && fromNumber != null && !fromNumber.isBlank();
        if (configured) {
            Twilio.init(accountSid.trim(), authToken.trim());
            log.info("Twilio SMS sender configured (from={})", fromNumber);
        } else {
            log.warn("Twilio SMS sender not configured. Set twilio.account.sid, twilio.auth.token, twilio.phone.number.");
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    public void sendSms(String toPhone, String message) {
        if (!configured) {
            log.info("Twilio not configured; SMS skipped to {}: {}", toPhone, message);
            return;
        }
        if (toPhone == null || toPhone.isBlank() || message == null || message.isBlank()) {
            return;
        }
        try {
            Message.creator(new PhoneNumber(toPhone.trim()), new PhoneNumber(fromNumber.trim()), message.trim())
                    .create();
        } catch (ApiException e) {
            log.warn("Twilio send failed to {}: {}", toPhone, e.getMessage());
        }
    }
}

