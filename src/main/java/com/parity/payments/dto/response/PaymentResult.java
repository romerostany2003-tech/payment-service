package com.parity.payments.dto.response;

public record PaymentResult(
    PaymentResponse payment, 
    boolean replayed
) {}