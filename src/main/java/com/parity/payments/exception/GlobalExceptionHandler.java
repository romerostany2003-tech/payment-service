package com.parity.payments.exception;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.parity.payments.dto.response.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMethodArguments(MethodArgumentNotValidException exception){
        
        String message = exception.getBindingResult()
                    .getFieldErrors()
                    .stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining(", "));

        ErrorResponse response = new ErrorResponse(message, 400, LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(response);
    }
    
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflictException(IdempotencyConflictException exception){
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage(), 409, LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFoundException(PaymentNotFoundException exception){
        ErrorResponse errorResponse = new ErrorResponse(exception.getMessage(), 404, LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
}
