package com.parity.payments.service;

import org.springframework.stereotype.Component;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResult;
import com.parity.payments.entity.IdempotencyRecord;
import com.parity.payments.entity.Payment;
import com.parity.payments.enums.PaymentStatus;
import com.parity.payments.mapper.PaymentMapper;
import com.parity.payments.repository.IdempotencyRecordRepository;
import com.parity.payments.repository.PaymentRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentCreator {

    private final PaymentMapper paymentMapper;
    private final PaymentRepository paymentRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Transactional
    public PaymentResult create(String key, String hash, PaymentRequest request) {
        Payment payment = paymentMapper.toEntity(request);
        payment.setStatus(PaymentStatus.PENDING);
        Payment saved = paymentRepository.save(payment);

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setRequestHash(hash);
        record.setPaymentId(saved.getId());
        idempotencyRecordRepository.saveAndFlush(record);

        return new PaymentResult(paymentMapper.toResponse(saved), false);
    }
}