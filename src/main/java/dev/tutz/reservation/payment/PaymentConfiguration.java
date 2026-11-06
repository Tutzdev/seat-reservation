package dev.tutz.reservation.payment;

import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentConfiguration {

    /**
     * Explicit timeouts: without them a hung ledger would hold a request thread forever. The observation registry
     * times every ledger call and sends the W3C {@code traceparent} header, so a payment is one trace across this
     * service and the ledger.
     */
    @Bean
    PaymentGateway paymentGateway(LedgerProperties properties, ObjectProvider<ObservationRegistry> observations,
            Clock clock) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(properties.readTimeout());
        RestClient.Builder http = RestClient.builder()
                .requestFactory(requests)
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        return new LedgerPaymentGateway(http, properties, clock);
    }
}
