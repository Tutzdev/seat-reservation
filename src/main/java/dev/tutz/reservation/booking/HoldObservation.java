package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.queue.NotAdmittedException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;

/**
 * Every hold attempt is one observation, {@code reservation.holds}: a timer per outcome in Prometheus
 * ({@code reservation_holds_seconds_count{outcome="lost"}}) and a span in the trace of the request.
 *
 * <p>{@code outcome} is {@code won}, {@code lost} (a seat was taken first), {@code replayed} (a retry answered
 * with its booking), {@code rejected} (a rule said no, with the error {@code code}) or {@code failed}. Under load,
 * won against lost is the number that matters: it shows how many buyers raced for the same seats.
 */
final class HoldObservation {

    static final String NAME = "reservation.holds";

    private HoldObservation() {
    }

    static HoldResult observe(ObservationRegistry registry, Supplier<HoldResult> hold) {
        Observation observation = Observation.createNotStarted(NAME, registry).contextualName("hold seats").start();
        try (Observation.Scope scope = observation.openScope()) {
            HoldResult result = hold.get();
            outcome(observation, result.replayed() ? "replayed" : "won", "none");
            return result;
        } catch (ConflictException conflict) {
            outcome(observation, "SEATS_UNAVAILABLE".equals(conflict.getCode()) ? "lost" : "rejected", conflict.getCode());
            throw conflict;
        } catch (BusinessRuleException rule) {
            outcome(observation, "rejected", rule.getCode());
            throw rule;
        } catch (NotAdmittedException notAdmitted) {
            outcome(observation, "rejected", "NOT_ADMITTED");
            throw notAdmitted;
        } catch (RuntimeException failure) {
            outcome(observation, "failed", "none");
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private static void outcome(Observation observation, String outcome, String code) {
        observation.lowCardinalityKeyValue("outcome", outcome).lowCardinalityKeyValue("code", code);
    }
}
