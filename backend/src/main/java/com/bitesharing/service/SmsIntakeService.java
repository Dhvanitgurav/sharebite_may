package com.bitesharing.service;

import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import com.bitesharing.repository.RequestRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsIntakeService {

    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final TwilioSmsSender twilioSmsSender;

    @Transactional
    public String processInboundSms(String from, String body) {
        String normalizedPhone = normalizePhone(from);
        String msg = body == null ? "" : body.toUpperCase(Locale.ROOT);
        if (!(msg.contains("FOOD") || msg.contains("HELP") || msg.contains("HUNGRY"))) {
            return "No actionable keyword found. Please text FOOD, HELP or HUNGRY.";
        }

        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        long countToday = requestRepository.countByRequesterPhoneAndCreatedAtAfter(normalizedPhone, todayStart);
        if (countToday >= 2) {
            return "You have reached the daily request limit of 2. Please try again tomorrow.";
        }

        User smsUser = userRepository.findByPhone(normalizedPhone)
                .orElseGet(() -> createSmsNeedyUser(normalizedPhone));

        Request request = new Request();
        request.setRequester(smsUser);
        request.setRequesterType(Request.RequesterType.NEEDY);
        request.setRequesterPhone(normalizedPhone);
        request.setStatus(Request.RequestStatus.PENDING);
        request.setDeliveryAddress("SMS requester: " + normalizedPhone);
        Request saved = requestRepository.save(request);
        notificationService.sendNewRequestAvailableNotification(saved);

        return "Your request has been registered. We will assign a meal and volunteer soon.";
    }

    public Map<String, String> twilioResponse(String message) {
        String xml = "<Response><Message>" + message + "</Message></Response>";
        return Map.of("xml", xml);
    }

    /**
     * Outbound SMS hook placeholder for freshness risk alerts.
     */
    public void sendFreshnessRiskSms(String toPhone, String message) {
        if (toPhone == null || toPhone.isBlank()) {
            return;
        }
        twilioSmsSender.sendSms(normalizePhone(toPhone), message);
    }

    private String normalizePhone(String from) {
        if (from == null) {
            return "";
        }
        return from.replaceAll("[^0-9+]", "").trim();
    }

    private User createSmsNeedyUser(String phone) {
        User user = new User();
        user.setUsername("sms_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        user.setEmail("sms+" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@sharebite.local");
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setFullName("SMS Requester");
        user.setPhone(phone);
        user.setSource("SMS");
        user.setVerified(false);
        user.setUserType(User.UserType.NEEDY);
        user.setStatus(User.UserStatus.APPROVED);
        return userRepository.save(user);
    }
}

