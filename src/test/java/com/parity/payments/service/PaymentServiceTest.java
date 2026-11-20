package com.parity.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.dao.DataIntegrityViolationException;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResponse;
import com.parity.payments.dto.response.PaymentResult;
import com.parity.payments.entity.IdempotencyRecord;
import com.parity.payments.entity.Payment;
import com.parity.payments.enums.CurrencyCode;
import com.parity.payments.exception.IdempotencyConflictException;
import com.parity.payments.mapper.PaymentMapper;
import com.parity.payments.repository.IdempotencyRecordRepository;
import com.parity.payments.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String KEY = "key-123";
    private static final String REQUEST_HASH =
            "2d6eb6723574c696d9d86eb730b87ee730ea5fe27b2af87a23707fb22676a8b8";
    private static final UUID PAYMENT_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");

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
        PaymentRequest request = validRequest();
        PaymentResult expectedResult = new PaymentResult(null, false);

        when(idempotencyRecordRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty());

        when(paymentCreator.create(
                eq(KEY),
                eq(REQUEST_HASH),
                same(request)))
                .thenReturn(expectedResult);

        PaymentResult actualResult = paymentService.processPayment(KEY, request);

        assertSame(expectedResult, actualResult);

        verify(idempotencyRecordRepository).findByIdempotencyKey(KEY);
        verify(paymentCreator).create(
                eq(KEY),
                eq(REQUEST_HASH),
                same(request));
    }

    @Test
    void processPayment_withExistingKeyAndSameRequest_returnsExistingPayment() {
        PaymentRequest request = validRequest();
        IdempotencyRecord record = existingRecord(REQUEST_HASH);
        Payment payment = new Payment();
        PaymentResponse response = org.mockito.Mockito.mock(PaymentResponse.class);

        when(idempotencyRecordRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(record));
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));
        when(paymentMapper.toResponse(payment)).thenReturn(response);

        PaymentResult result = paymentService.processPayment(KEY, request);

        assertSame(response, result.payment());
        assertTrue(result.replayed());
        verify(paymentCreator, never()).create(any(), any(), any());
    }

    @Test
    void processPayment_withExistingKeyAndDifferentRequest_throwsConflict() {
        PaymentRequest request = validRequest();
        IdempotencyRecord record = existingRecord("different-request-hash");

        when(idempotencyRecordRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(record));

        IdempotencyConflictException exception = assertThrows(
                IdempotencyConflictException.class,
                () -> paymentService.processPayment(KEY, request));

        assertEquals(
                "Idempotency key: " + KEY + " already used with different request",
                exception.getMessage());
        verify(paymentRepository, never()).findById(any());
        verify(paymentCreator, never()).create(any(), any(), any());
    }

    @Test
    void processPayment_withExistingRecordButMissingPayment_throwsException() {
        PaymentRequest request = validRequest();
        IdempotencyRecord record = existingRecord(REQUEST_HASH);

        when(idempotencyRecordRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(record));
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> paymentService.processPayment(KEY, request));

        assertEquals(
                "Payment missing for idempotency key " + KEY,
                exception.getMessage());
        verify(paymentCreator, never()).create(any(), any(), any());
    }

    @Test
    void processPayment_whenConcurrentRequestCreatesKey_replaysWinningPayment() {
        PaymentRequest request = validRequest();
        IdempotencyRecord winningRecord = existingRecord(REQUEST_HASH);
        Payment winningPayment = new Payment();
        PaymentResponse response = org.mockito.Mockito.mock(PaymentResponse.class);

        when(idempotencyRecordRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty(), Optional.of(winningRecord));
        when(paymentCreator.create(KEY, REQUEST_HASH, request))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(winningPayment));
        when(paymentMapper.toResponse(winningPayment)).thenReturn(response);

        PaymentResult result = paymentService.processPayment(KEY, request);

        assertSame(response, result.payment());
        assertTrue(result.replayed());
        verify(idempotencyRecordRepository, times(2)).findByIdempotencyKey(KEY);
    }

    @Test
    void processPayment_whenCreateFailsAndKeyIsStillMissing_rethrowsOriginalException() {
        PaymentRequest request = validRequest();
        DataIntegrityViolationException original =
                new DataIntegrityViolationException("duplicate key");

        when(idempotencyRecordRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty());
        when(paymentCreator.create(KEY, REQUEST_HASH, request))
                .thenThrow(original);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> paymentService.processPayment(KEY, request));

        assertSame(original, thrown);
        verify(idempotencyRecordRepository, times(2)).findByIdempotencyKey(KEY);
    }

    private static PaymentRequest validRequest() {
        PaymentRequest request = new PaymentRequest();
        request.setSourceAccountId(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));
        request.setDestinationAccountId(
                UUID.fromString("22222222-2222-2222-2222-222222222222"));
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency(CurrencyCode.INR);
        request.setDescription("Test payment");
        return request;
    }

    private static IdempotencyRecord existingRecord(String requestHash) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(KEY);
        record.setRequestHash(requestHash);
        record.setPaymentId(PAYMENT_ID);
        return record;
    }
}
