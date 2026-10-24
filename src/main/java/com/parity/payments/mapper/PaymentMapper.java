package com.parity.payments.mapper;

import org.springframework.stereotype.Component;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResponse;
import com.parity.payments.entity.Payment;
import com.parity.payments.enums.PaymentStatus;

@Component
public class PaymentMapper {

    public Payment toEntity(PaymentRequest request) {
        if (request == null) {
            return null;
        }

        return Payment.builder()
                .sourceAccountId(request.getSourceAccountId())
                .destinationAccountId(request.getDestinationAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .description(request.getDescription())
                .status(PaymentStatus.PENDING)
                .build();
    }
    
    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getSourceAccountId(),
                payment.getDestinationAccountId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getDescription(),
                payment.getCreatedAt()
        );
    }
}