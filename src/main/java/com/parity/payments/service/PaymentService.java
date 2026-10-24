package com.parity.payments.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

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

@Service
@RequiredArgsConstructor 
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final PaymentRepository paymentRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    
    @Transactional
    public PaymentResult createPayment(String key, PaymentRequest paymentRequest) {

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByIdempotencyKey(key);

        if (existing.isPresent()) {
            Payment payment = paymentRepository.findById(existing.get().getPaymentId())
                    .orElseThrow(() -> new IllegalStateException("Payment missing for idempotency key " + key));
            
            return new PaymentResult(paymentMapper.toResponse(payment), true);
        }
        //String hash = fingerprint(paymentRequest);
        
        Payment payment = paymentMapper.toEntity(paymentRequest);
        payment.setStatus(PaymentStatus.PENDING);
        Payment saved = paymentRepository.save(payment);

        IdempotencyRecord idempotencyRecord = new IdempotencyRecord();
        idempotencyRecord.setIdempotencyKey(key);
        idempotencyRecord.setRequestHash("TODO");   // replaced in stage 3
        idempotencyRecord.setPaymentId(saved.getId());
        idempotencyRecordRepository.saveAndFlush(idempotencyRecord);

        return new PaymentResult(paymentMapper.toResponse(payment), false);
    }

    /** Canonical form so 10.5 and 10.50 hash identically. Description is last, so '|' can't cause ambiguity. */
    // private static String fingerprint(PaymentRequest r) {
    //     String canonical = String.join("|",
    //             r.getSourceAccountId().toString(),
    //             r.getDestinationAccountId().toString(),
    //             r.getAmount().stripTrailingZeros().toPlainString(),
    //             r.getCurrency().name(),
    //             r.getDescription() == null ? "" : r.getDescription());
    //     try {
    //         byte[] digest = MessageDigest.getInstance("SHA-256")
    //                 .digest(canonical.getBytes(StandardCharsets.UTF_8));
    //         return HexFormat.of().formatHex(digest);
    //     } catch (NoSuchAlgorithmException e) {
    //         throw new IllegalStateException(e);
    //     }
    // }
}
