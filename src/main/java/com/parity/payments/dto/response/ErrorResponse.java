package com.parity.payments.dto.response;

import java.time.LocalDateTime;

public record ErrorResponse(    
    String message,
    int statusCode,
    LocalDateTime timestamp
) {}