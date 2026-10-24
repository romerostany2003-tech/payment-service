package com.parity.payments.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.parity.payments.enums.CurrencyCode;
import com.parity.payments.enums.PaymentStatus;

public record PaymentResponse(
        UUID paymentId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        CurrencyCode currency,
        PaymentStatus status,
        String description,
        Instant createdAt
) {}