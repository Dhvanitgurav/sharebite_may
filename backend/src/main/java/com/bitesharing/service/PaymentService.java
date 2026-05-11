package com.bitesharing.service;

import com.bitesharing.dto.PaymentSessionRequest;
import com.bitesharing.model.NgoDonationPayment;
import com.bitesharing.model.User;
import com.bitesharing.repository.NgoDonationPaymentRepository;
import com.bitesharing.repository.UserRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final NgoDonationPaymentRepository ngoDonationPaymentRepository;
    private final UserRepository userRepository;

    @Value("${stripe.checkout.success-url:http://localhost:3000}")
    private String successUrl;

    @Value("${stripe.checkout.cancel-url:http://localhost:3000}")
    private String cancelUrl;

    @Value("${stripe.secret.key:}")
    private String stripeSecretKey;

    @Value("${stripe.webhook.secret:}")
    private String stripeWebhookSecret;

    public Map<String, Object> createSession(PaymentSessionRequest request) {
        if (request.getAmount() == null || request.getAmount().doubleValue() <= 0) {
            throw new RuntimeException("Amount must be greater than zero");
        }
        Long ngoId = request.getNgoId();
        if (ngoId == null) {
            ngoId = userRepository.findByUserTypeAndStatus(User.UserType.NGO, User.UserStatus.APPROVED).stream()
                    .findFirst()
                    .map(User::getId)
                    .orElseThrow(() -> new RuntimeException("No NGO found to receive donation"));
        }
        if (stripeSecretKey == null || stripeSecretKey.isBlank() || stripeSecretKey.contains("xxx")) {
            throw new RuntimeException("Stripe secret key is not configured");
        }
        Stripe.apiKey = stripeSecretKey;

        Session session;
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl + "?payment=success&session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(cancelUrl + "?payment=cancelled")
                    .setClientReferenceId(String.valueOf(request.getUserId() == null ? 0L : request.getUserId()))
                    .putMetadata("ngoId", String.valueOf(ngoId))
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(1L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency("inr")
                                                    .setUnitAmount(request.getAmount().movePointRight(2).longValue())
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                    .setName("NGO Donation")
                                                                    .setDescription("Donation to NGO #" + ngoId)
                                                                    .build()
                                                    )
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();
            session = Session.create(params);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe session creation failed: " + e.getMessage());
        }

        NgoDonationPayment payment = new NgoDonationPayment();
        payment.setUserId(request.getUserId() == null ? 0L : request.getUserId());
        payment.setNgoId(ngoId);
        payment.setAmount(request.getAmount());
        payment.setStripeSessionId(session.getId());
        payment.setPaymentStatus("CREATED");
        ngoDonationPaymentRepository.save(payment);

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", session.getId());
        response.put("checkoutUrl", session.getUrl());
        response.put("mode", "stripe-live-test");
        return response;
    }

    public void handleWebhook(String payload, String signatureHeader) {
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank() || stripeWebhookSecret.contains("xxx")) {
            throw new RuntimeException("Stripe webhook secret is not configured");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, stripeWebhookSecret);
        } catch (SignatureVerificationException e) {
            throw new RuntimeException("Invalid Stripe signature");
        }

        if ("checkout.session.completed".equals(event.getType())) {
            StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
            if (stripeObject instanceof Session session) {
                ngoDonationPaymentRepository.findByStripeSessionId(session.getId()).ifPresent(payment -> {
                    payment.setPaymentStatus("COMPLETED");
                    payment.setStripePaymentIntentId(session.getPaymentIntent());
                    ngoDonationPaymentRepository.save(payment);
                });
            }
        }
    }
}
