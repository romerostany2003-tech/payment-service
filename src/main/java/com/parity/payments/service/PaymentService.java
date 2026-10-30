package com.parity.payments.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResult;
import com.parity.payments.entity.IdempotencyRecord;
import com.parity.payments.entity.Payment;
import com.parity.payments.exception.IdempotencyConflictException;
import com.parity.payments.mapper.PaymentMapper;
import com.parity.payments.repository.IdempotencyRecordRepository;
import com.parity.payments.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final PaymentRepository paymentRepository;
    private final PaymentCreator paymentCreator;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public PaymentResult processPayment(String key, PaymentRequest paymentRequest) {

        String hash = fingerprint(paymentRequest);

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(key);

        if (existing.isPresent()) {
            return replay(existing.get(), key, hash);
        }
        try {
            return paymentCreator.create(key, hash, paymentRequest);
        } catch (DataIntegrityViolationException exception) {
            IdempotencyRecord record = idempotencyRecordRepository.findByIdempotencyKey(key).orElseThrow(() -> exception);
            return replay(record, key, hash);
        }
    }
    
    private PaymentResult replay(IdempotencyRecord record, String key, String hash) {
        if (!record.getRequestHash().equals(hash)) {
            throw new IdempotencyConflictException("Idempotency key: "+ key +" already used with different request");
        }

        Payment payment = paymentRepository.findById(record.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Payment missing for idempotency key " + key));

        return new PaymentResult(paymentMapper.toResponse(payment), true);
    }

    /** Canonical form so 10.5 and 10.50 hash identically. Description is last, so '|' can't cause ambiguity. */
    private static String fingerprint(PaymentRequest r) {
        String canonical = String.join("|",
                r.getSourceAccountId().toString(),
                r.getDestinationAccountId().toString(),
                r.getAmount().stripTrailingZeros().toPlainString(),
                r.getCurrency().name(),
                r.getDescription() == null ? "" : r.getDescription());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}