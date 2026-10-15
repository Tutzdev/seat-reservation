package dev.tutz.reservation.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import dev.tutz.reservation.MutableClock;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/** The HTTP conversation with wallet-ledger, against a mocked server: requests sent and how answers are read. */
class LedgerPaymentGatewayTests {

    private static final String LEDGER = "http://ledger.test";
    private static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private static final UUID CUSTOMER = UUID.fromString("5736b138-4e0a-4d4f-9a52-3f1f0c7a9e10");
    private static final UUID TRANSFER = UUID.fromString("9f2d6c1e-8b7a-4c55-a1d3-6e0f2b4c8a71");
    private static final String KEY = "booking-6f1c2b7e";

    private final MutableClock clock = new MutableClock();
    private MockRestServiceServer ledger;
    private LedgerPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder http = RestClient.builder();
        ledger = MockRestServiceServer.bindTo(http).build();
        LedgerProperties properties = new LedgerProperties(URI.create(LEDGER), "tickets@ledger.test", "secret-password",
                MERCHANT, Duration.ofSeconds(1), Duration.ofSeconds(1));
        gateway = new LedgerPaymentGateway(http, properties, clock);
    }

    @Test
    void chargeLogsInAndPostsATransferFromTheCustomerToTheVenue() {
        expectLogin("token-1", 3600);
        ledger.expect(requestTo(LEDGER + "/api/v1/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andExpect(header("Idempotency-Key", KEY))
                .andExpect(jsonPath("$.sourceAccountId").value(CUSTOMER.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(MERCHANT.toString()))
                .andExpect(jsonPath("$.amount").value(450.5))
                .andExpect(jsonPath("$.description").value("Booking 42"))
                .andRespond(transfer(TRANSFER));

        UUID transfer = gateway.charge(KEY, CUSTOMER, 45_050, "Booking 42");

        assertThat(transfer).isEqualTo(TRANSFER);
        ledger.verify();
    }

    @Test
    void theTokenIsReusedUntilShortlyBeforeItExpires() {
        expectLogin("token-1", 3600);
        expectTransfer("token-1");
        expectTransfer("token-1");
        expectLogin("token-2", 3600);
        expectTransfer("token-2");

        gateway.charge(KEY, CUSTOMER, 100, "first");
        clock.advance(Duration.ofMinutes(50));
        gateway.charge(KEY, CUSTOMER, 100, "same token");
        clock.advance(Duration.ofMinutes(9).plusSeconds(31)); // 29 seconds before expiry
        gateway.charge(KEY, CUSTOMER, 100, "renewed token");

        ledger.verify();
    }

    @Test
    void aTokenTheLedgerNoLongerAcceptsIsRenewedOnce() {
        expectLogin("token-1", 3600);
        ledger.expect(requestTo(LEDGER + "/api/v1/transfers")).andRespond(withUnauthorizedRequest());
        expectLogin("token-2", 3600);
        expectTransfer("token-2");

        assertThat(gateway.charge(KEY, CUSTOMER, 100, "retry")).isEqualTo(TRANSFER);
        ledger.verify();
    }

    @Test
    void aRefusedChargeIsADeclineCarryingTheLedgerCode() {
        expectLogin("token-1", 3600);
        ledger.expect(requestTo(LEDGER + "/api/v1/transfers"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"status": 422, "code": "INSUFFICIENT_FUNDS",
                                 "detail": "Account 5736b138 has insufficient funds."}
                                """));

        assertThatThrownBy(() -> gateway.charge(KEY, CUSTOMER, 100, "no funds"))
                .isInstanceOfSatisfying(PaymentDeclinedException.class,
                        declined -> assertThat(declined.getReason()).isEqualTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    void aServerErrorLeavesTheOutcomeUnknown() {
        expectLogin("token-1", 3600);
        ledger.expect(requestTo(LEDGER + "/api/v1/transfers")).andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> gateway.charge(KEY, CUSTOMER, 100, "ledger down"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void aTimeoutLeavesTheOutcomeUnknown() {
        expectLogin("token-1", 3600);
        ledger.expect(requestTo(LEDGER + "/api/v1/transfers")).andRespond(withException(new SocketTimeoutException()));

        assertThatThrownBy(() -> gateway.charge(KEY, CUSTOMER, 100, "too slow"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void rejectedServiceCredentialsAreNotBlamedOnTheCustomer() {
        ledger.expect(requestTo(LEDGER + "/api/v1/auth/token")).andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> gateway.charge(KEY, CUSTOMER, 100, "bad credentials"))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    @Test
    void refundReversesTheOriginalTransfer() {
        UUID reversal = UUID.randomUUID();
        expectLogin("token-1", 3600);
        ledger.expect(requestTo(LEDGER + "/api/v1/transfers/" + TRANSFER + "/reversal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-6f1c2b7e"))
                .andExpect(jsonPath("$.reason").value("Seats were gone"))
                .andRespond(transfer(reversal));

        assertThat(gateway.refund(TRANSFER, "refund-6f1c2b7e", "Seats were gone")).isEqualTo(reversal);
        ledger.verify();
    }

    private void expectLogin(String token, long expiresInSeconds) {
        ledger.expect(once(), requestTo(LEDGER + "/api/v1/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"email": "tickets@ledger.test", "password": "secret-password"}
                        """))
                .andRespond(withSuccess("""
                        {"accessToken": "%s", "tokenType": "Bearer", "expiresIn": %d}
                        """.formatted(token, expiresInSeconds), MediaType.APPLICATION_JSON));
    }

    private void expectTransfer(String token) {
        ledger.expect(requestTo(LEDGER + "/api/v1/transfers"))
                .andExpect(header("Authorization", "Bearer " + token))
                .andRespond(transfer(TRANSFER));
    }

    private static ResponseCreator transfer(UUID id) {
        return withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                {"id": "%s", "type": "TRANSFER", "amount": 450.50, "currency": "BRL"}
                """.formatted(id));
    }
}
