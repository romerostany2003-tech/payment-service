package com.parity.payments.integration;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResult;
import com.parity.payments.entity.IdempotencyRecord;
import com.parity.payments.enums.CurrencyCode;
import com.parity.payments.enums.PaymentStatus;
import com.parity.payments.exception.IdempotencyConflictException;
import com.parity.payments.repository.IdempotencyRecordRepository;
import com.parity.payments.repository.PaymentRepository;
import com.parity.payments.service.PaymentService;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PaymentServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @BeforeEach
    void cleanDatabase() {
        idempotencyRecordRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void newRequest_persistsPaymentAndIdempotencyRecord() {
        PaymentRequest request = validRequest();

        PaymentResult result =
                paymentService.processPayment("key-123", request);

        assertFalse(result.replayed());
        assertEquals(PaymentStatus.PENDING, result.payment().status());
        assertEquals(1, paymentRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());

        IdempotencyRecord record = idempotencyRecordRepository
                .findByIdempotencyKey("key-123")
                .orElseThrow();

        assertEquals(
                result.payment().paymentId(),
                record.getPaymentId());
    }

    @Test
    void sameKeyAndSameRequest_returnsOriginalPaymentAsReplay() {
        PaymentRequest request = validRequest();

        PaymentResult first =
                paymentService.processPayment("key-123", request);

        PaymentResult second =
                paymentService.processPayment("key-123", request);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(
                first.payment().paymentId(),
                second.payment().paymentId());

        assertEquals(1, paymentRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
    }

    @Test
    void sameKeyAndDifferentRequest_throwsConflict() {
        paymentService.processPayment("key-123", validRequest());

        PaymentRequest changedRequest = validRequest();
        changedRequest.setAmount(new BigDecimal("200.00"));

        assertThrows(
                IdempotencyConflictException.class,
                () -> paymentService.processPayment(
                        "key-123",
                        changedRequest));

        assertEquals(1, paymentRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
    }

    @Test
    void duplicateIdempotencyKey_isRejectedByDatabase() {
        PaymentResult result =
                paymentService.processPayment("key-123", validRequest());

        IdempotencyRecord duplicate = new IdempotencyRecord();
        duplicate.setIdempotencyKey("key-123");
        duplicate.setRequestHash("another-hash");
        duplicate.setPaymentId(result.payment().paymentId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> idempotencyRecordRepository.saveAndFlush(duplicate));
    }

    private static PaymentRequest validRequest() {
        PaymentRequest request = new PaymentRequest();
        request.setSourceAccountId(UUID.fromString(
                "11111111-1111-1111-1111-111111111111"));
        request.setDestinationAccountId(UUID.fromString(
                "22222222-2222-2222-2222-222222222222"));
        request.setAmount(new BigDecimal("100.00"));
        request.setCurrency(CurrencyCode.INR);
        request.setDescription("Test payment");
        return request;
    }
}