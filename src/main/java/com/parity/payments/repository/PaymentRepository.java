package com.parity.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.parity.payments.entity.Payment;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> { }