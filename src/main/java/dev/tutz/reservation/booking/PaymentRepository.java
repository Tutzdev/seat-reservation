package dev.tutz.reservation.booking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Payments are changed only while their booking row is locked, so they need no lock of their own. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
}
