package com.parity.payments.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.parity.payments.dto.request.PaymentRequest;
import com.parity.payments.dto.response.PaymentResult;
import com.parity.payments.service.PaymentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResult> processPayment(
        @Size(max = 255) 
        @RequestHeader("Idempotency-Key") 
        @NotBlank(message = "Idempotency-Key header must not be blank")
        String idempotencyKey,
        @Valid @RequestBody PaymentRequest request
    ) {
        PaymentResult result = paymentService.processPayment(idempotencyKey, request);

        HttpStatus status = result.replayed()
                ? HttpStatus.OK
                : HttpStatus.CREATED;

        return ResponseEntity.status(status).body(result);
    }
    
}
