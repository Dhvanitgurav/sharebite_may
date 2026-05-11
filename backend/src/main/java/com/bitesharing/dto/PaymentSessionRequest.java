package com.bitesharing.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentSessionRequest {
    private Long userId;
    private Long ngoId;
    private BigDecimal amount;
}
