package com.parity.payments.mapper;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PaymentMapperTest {

    @Test
    void toEntity_withNullRequest_returnsNull() {
        PaymentMapper mapper = new PaymentMapper();

        var result = mapper.toEntity(null);

        assertNull(result);
    }
}