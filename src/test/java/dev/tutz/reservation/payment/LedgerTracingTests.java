package dev.tutz.reservation.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.tutz.reservation.PostgresTestSupport;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * A call to the ledger carries the W3C traceparent of the request that made it, so a payment shows up as one
 * trace across both services. The ledger is a plain HTTP server that records the headers it receives.
 */
@AutoConfigureTracing
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class LedgerTracingTests extends PostgresTestSupport {

    private static final List<String> TRACEPARENTS = new CopyOnWriteArrayList<>();
    private static final HttpServer LEDGER = startLedger();

    @Autowired
    @Qualifier("paymentGateway")
    private PaymentGateway gateway;

    @Autowired
    private Tracer tracer;

    @DynamicPropertySource
    static void pointAtTheLedger(DynamicPropertyRegistry registry) {
        registry.add("reservation.payments.ledger.base-url",
                () -> "http://localhost:" + LEDGER.getAddress().getPort());
    }

    @AfterAll
    static void stopLedger() {
        LEDGER.stop(0);
    }

    @Test
    void aChargeContinuesTheTraceOfTheRequestThatMadeIt() {
        Span payment = tracer.nextSpan().name("pay booking").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(payment)) {
            gateway.charge("booking-" + UUID.randomUUID(), UUID.randomUUID(), 10_000, "Booking");
        } finally {
            payment.end();
        }

        // The login and the transfer both belong to the payment's trace.
        assertThat(TRACEPARENTS).hasSize(2).allSatisfy(traceparent -> assertThat(traceparent)
                .matches("00-" + payment.context().traceId() + "-[0-9a-f]{16}-[0-9a-f]{2}"));
    }

    private static HttpServer startLedger() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/v1/auth/token", exchange -> answer(exchange, 200, """
                    {"accessToken": "ledger-token", "expiresIn": 3600}"""));
            server.createContext("/api/v1/transfers", exchange -> answer(exchange, 201, """
                    {"id": "%s"}""".formatted(UUID.randomUUID())));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the fake ledger", exception);
        }
    }

    private static void answer(HttpExchange exchange, int status, String json) throws IOException {
        TRACEPARENTS.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
