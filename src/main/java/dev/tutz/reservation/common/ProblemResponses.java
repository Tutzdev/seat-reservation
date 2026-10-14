package dev.tutz.reservation.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemResponses {

    private final Clock clock;

    public ProblemResponses(Clock clock) {
        this.clock = clock;
    }

    public ProblemDetail create(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail response = ProblemDetail.forStatusAndDetail(status, detail);
        response.setTitle(status.getReasonPhrase());
        response.setInstance(URI.create(request.getRequestURI()));
        response.setProperty("code", code);
        response.setProperty("timestamp", clock.instant());
        return response;
    }
}
