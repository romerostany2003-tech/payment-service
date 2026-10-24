package com.parity.payments.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

import com.parity.payments.enums.CurrencyCode;


import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;


@Getter 
@Setter
public class PaymentRequest {

    @NotNull(message = "Source account ID is required")
    private UUID sourceAccountId;

    @NotNull(message = "Destination account ID is required")
    private UUID destinationAccountId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", inclusive = true,
            message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotNull(message = "Currency is required")
    private CurrencyCode currency;

    @Size(max = 255, message = "Description cannot exceed 255 characters")
    private String description;

    @AssertTrue(message = "Source and destination accounts must differ")
    public boolean isAccountsDifferent() {
        if (sourceAccountId == null || destinationAccountId == null) {
            return true;
        }
        return !sourceAccountId.equals(destinationAccountId);
    }
}
