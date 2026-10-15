package dev.tutz.reservation.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Moves money through <a href="https://github.com/Tutzdev/wallet-ledger">wallet-ledger</a> over HTTP.
 *
 * <p>Errors are sorted by what the caller can do about them: a 4xx with a ledger code means the charge
 * was refused and nothing moved ({@link PaymentDeclinedException}); anything else, including a timeout,
 * means the outcome is unknown ({@link PaymentUnavailableException}).
 */
public class LedgerPaymentGateway implements PaymentGateway {

    /** A token is renewed this long before it expires, so a request never leaves with one about to die. */
    private static final Duration TOKEN_RENEWAL_MARGIN = Duration.ofSeconds(30);

    private final RestClient http;
    private final LedgerProperties properties;
    private final Clock clock;
    // Not synchronized: a virtual thread blocked on I/O inside a synchronized block pins its carrier thread.
    private final ReentrantLock login = new ReentrantLock();
    private volatile AccessToken token;

    public LedgerPaymentGateway(RestClient.Builder http, LedgerProperties properties, Clock clock) {
        this.http = http.baseUrl(properties.baseUrl().toString()).build();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public UUID charge(String idempotencyKey, UUID customerAccountId, long amountCents, String description) {
        TransferRequest body = new TransferRequest(customerAccountId, properties.merchantAccountId(),
                BigDecimal.valueOf(amountCents, 2), description);
        return post("/api/v1/transfers", idempotencyKey, body).id();
    }

    @Override
    public UUID refund(UUID transferId, String idempotencyKey, String reason) {
        return post("/api/v1/transfers/{id}/reversal", idempotencyKey, new ReversalRequest(reason), transferId).id();
    }

    private Transfer post(String path, String idempotencyKey, Object body, Object... uriVariables) {
        try {
            try {
                return send(path, idempotencyKey, body, uriVariables);
            } catch (HttpClientErrorException.Unauthorized rejected) {
                // The token was revoked or the ledger restarted with a new signing key: log in again, once.
                token = null;
                return send(path, idempotencyKey, body, uriVariables);
            }
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED || exception.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new PaymentUnavailableException("The ledger rejected this service's credentials.", exception);
            }
            throw declined(exception);
        } catch (RestClientException exception) {
            throw new PaymentUnavailableException("The ledger did not answer the payment request.", exception);
        }
    }

    private Transfer send(String path, String idempotencyKey, Object body, Object... uriVariables) {
        return http.post()
                .uri(path, uriVariables)
                .headers(headers -> {
                    headers.setBearerAuth(accessToken());
                    headers.set("Idempotency-Key", idempotencyKey);
                })
                .body(body)
                .retrieve()
                .body(Transfer.class);
    }

    private static PaymentDeclinedException declined(HttpClientErrorException exception) {
        LedgerProblem problem = null;
        try {
            problem = exception.getResponseBodyAs(LedgerProblem.class);
        } catch (RuntimeException unreadable) {
            // Not a Problem Details body: fall back to the status code below.
        }
        String reason = problem != null && problem.code() != null
                ? problem.code()
                : HttpStatus.valueOf(exception.getStatusCode().value()).name();
        String detail = problem != null && problem.detail() != null ? problem.detail() : "The ledger declined the payment.";
        return new PaymentDeclinedException(reason, detail);
    }

    private String accessToken() {
        AccessToken current = token;
        if (current != null && current.isUsableAt(clock.instant())) {
            return current.value();
        }
        login.lock();
        try {
            // Another thread may have logged in while this one waited for the lock.
            current = token;
            if (current == null || !current.isUsableAt(clock.instant())) {
                current = logIn();
                token = current;
            }
            return current.value();
        } finally {
            login.unlock();
        }
    }

    private AccessToken logIn() {
        try {
            TokenResponse response = http.post()
                    .uri("/api/v1/auth/token")
                    .body(new TokenRequest(properties.email(), properties.password()))
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new PaymentUnavailableException("The ledger answered the login without a token.", null);
            }
            Instant renewAt = clock.instant().plusSeconds(response.expiresIn()).minus(TOKEN_RENEWAL_MARGIN);
            return new AccessToken(response.accessToken(), renewAt);
        } catch (RestClientException exception) {
            throw new PaymentUnavailableException("Could not log in to the ledger.", exception);
        }
    }

    private record AccessToken(String value, Instant renewAt) {

        boolean isUsableAt(Instant now) {
            return now.isBefore(renewAt);
        }
    }

    record TransferRequest(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String description) {
    }

    record ReversalRequest(String reason) {
    }

    record TokenRequest(String email, String password) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(String accessToken, long expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Transfer(UUID id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LedgerProblem(String code, String detail) {
    }
}
