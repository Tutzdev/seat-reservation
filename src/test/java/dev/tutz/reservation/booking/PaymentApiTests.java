package dev.tutz.reservation.booking;

import static dev.tutz.reservation.FakePaymentGateway.Answer.DECLINE;
import static dev.tutz.reservation.FakePaymentGateway.Answer.UNAVAILABLE;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.customer.Customer;
import dev.tutz.reservation.event.EventResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The HTTP contract of paying for a booking. */
@AutoConfigureMockMvc
class PaymentApiTests extends PostgresTestSupport {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    private Customer customer;

    @BeforeEach
    void logIn() {
        customer = newCustomer();
    }

    @Test
    void payingAnswers200WithTheConfirmedBookingAndItsTransfer() throws Exception {
        BookingResponse booking = holdOneSeat();

        pay(booking.id(), CUSTOMER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payment.status").value("CAPTURED"))
                .andExpect(jsonPath("$.payment.customerAccountId").value(CUSTOMER.toString()))
                .andExpect(jsonPath("$.payment.transferId")
                        .value(ledger.chargeOf(booking.id()).orElseThrow().transferId().toString()));

        pay(booking.id(), CUSTOMER).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void aDeclineAnswers422WithTheLedgerReason() throws Exception {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(DECLINE);

        pay(booking.id(), CUSTOMER)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void anUnknownOutcomeAnswers503AndAsksForTheSameRequestAgain() throws Exception {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(UNAVAILABLE);

        pay(booking.id(), CUSTOMER)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("PAYMENT_UNAVAILABLE"));

        pay(booking.id(), CUSTOMER).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void aBookingThatEndedMidPaymentAnswers409WithTheRefund() throws Exception {
        BookingResponse booking = holdOneSeat();
        ledger.beforeCharge(() -> bookings.cancel(booking.customerId(), booking.id()));

        pay(booking.id(), CUSTOMER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUNDED"))
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
    }

    @Test
    void aCancelledBookingAnswers409() throws Exception {
        BookingResponse booking = holdOneSeat();
        bookings.cancel(booking.customerId(), booking.id());

        pay(booking.id(), CUSTOMER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_ACTIVE"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void aMissingAccountAnswers400AndAnUnknownBooking404() throws Exception {
        BookingResponse booking = holdOneSeat();

        mvc.perform(post("/api/v1/bookings/{id}/payment", booking.id()).header(AUTHORIZATION, bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("customerAccountId")));

        pay(UUID.randomUUID(), CUSTOMER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aRefundAnswers200WithTheRefundedBookingAndAHoldCannotBeRefunded() throws Exception {
        BookingResponse booking = holdOneSeat();

        refund(booking.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.bookingStatus").value("HELD"));

        pay(booking.id(), CUSTOMER).andExpect(status().isOk());
        refund(booking.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"))
                .andExpect(jsonPath("$.payment.refundTransferId")
                        .value(ledger.refundOf(booking.id()).orElseThrow().toString()));
    }

    @Test
    void aRefundTooCloseToTheEventAnswers422() throws Exception {
        BookingResponse booking = holdOneSeat();
        pay(booking.id(), CUSTOMER).andExpect(status().isOk());
        clock.advance(Duration.ofDays(6).plusHours(1));

        refund(booking.id())
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REFUND_WINDOW_CLOSED"));
    }

    private ResultActions refund(UUID bookingId) throws Exception {
        return mvc.perform(post("/api/v1/bookings/{id}/refund", bookingId).header(AUTHORIZATION, bearer(customer)));
    }

    private ResultActions pay(UUID bookingId, UUID customerAccountId) throws Exception {
        return mvc.perform(post("/api/v1/bookings/{id}/payment", bookingId).header(AUTHORIZATION, bearer(customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerAccountId\": \"%s\"}".formatted(customerAccountId)));
    }

    private BookingResponse holdOneSeat() {
        EventResponse event = eventWithSeats(1);
        return hold(customer.getId(), new HoldRequest(event.id(), seatIdsOf(event)));
    }
}
