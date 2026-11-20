package com.parity.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResponse;
import com.parity.payments.dto.response.PaymentResult;
import com.parity.payments.entity.IdempotencyRecord;
import com.parity.payments.entity.Payment;
import com.parity.payments.enums.PaymentStatus;
import com.parity.payments.mapper.PaymentMapper;
import com.parity.payments.repository.IdempotencyRecordRepository;
import com.parity.payments.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentCreatorTest {

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @InjectMocks
    private PaymentCreator paymentCreator;

    @Test
    void create_shouldSavePendingPaymentAndReturnPaymentResult() {
        // Arrange
        String key = "payment-key-123";
        String hash = "request-hash-123";

        PaymentRequest request = mock(PaymentRequest.class);
        Payment payment = new Payment();
        Payment savedPayment = new Payment();
        UUID savedPaymentId = UUID.fromString(
                "33333333-3333-3333-3333-333333333333");
        savedPayment.setId(savedPaymentId);
        PaymentResponse response = mock(PaymentResponse.class);

        when(paymentMapper.toEntity(request))
                .thenReturn(payment);

        when(paymentRepository.save(payment))
                .thenReturn(savedPayment);

        when(paymentMapper.toResponse(savedPayment))
                .thenReturn(response);

        // Act
        PaymentResult result =
                paymentCreator.create(key, hash, request);

        // Assert
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertSame(response, result.payment());
        assertFalse(result.replayed());

        verify(paymentMapper).toEntity(request);
        verify(paymentRepository).save(payment);

        ArgumentCaptor<IdempotencyRecord> recordCaptor =
                ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).saveAndFlush(recordCaptor.capture());

        IdempotencyRecord savedRecord = recordCaptor.getValue();
        assertEquals(key, savedRecord.getIdempotencyKey());
        assertEquals(hash, savedRecord.getRequestHash());
        assertEquals(savedPaymentId, savedRecord.getPaymentId());

        verify(paymentMapper).toResponse(savedPayment);
    }
}
