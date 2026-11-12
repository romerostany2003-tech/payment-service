package com.parity.payments.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResult;
import com.parity.payments.enums.CurrencyCode;
import com.parity.payments.mapper.PaymentMapper;
import com.parity.payments.repository.IdempotencyRecordRepository;
import com.parity.payments.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCreator paymentCreator;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void processPayment_withNewIdempotencyKey_createsPayment() {
        // Arrange: create a valid payment request
        PaymentRequest request = new PaymentRequest();
        request.setSourceAccountId(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));
        request.setDestinationAccountId(
                UUID.fromString("22222222-2222-2222-2222-222222222222"));
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency(CurrencyCode.INR);
        request.setDescription("Test payment");
        
        PaymentResult expectedResult = new PaymentResult(null, false);
        
        // Pretend that the idempotency key does not exist
        when(idempotencyRecordRepository
                .findByIdempotencyKey("key-123"))
                .thenReturn(Optional.empty());
        
        // Pretend that PaymentCreator creates this result
        when(paymentCreator.create(
                eq("key-123"),
                anyString(),
                same(request)))
                .thenReturn(expectedResult);
        
        // Act: call the real PaymentService
        PaymentResult actualResult =
                paymentService.processPayment("key-123", request);
        
        // Assert: check the result
        assertSame(expectedResult, actualResult);
        
        // Verify that the service asked PaymentCreator to create the payment
        verify(paymentCreator).create(
                eq("key-123"),
                anyString(),
                same(request));
    }
}