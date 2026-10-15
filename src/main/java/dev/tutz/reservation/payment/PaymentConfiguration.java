package dev.tutz.reservation.payment;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentConfiguration {

    /** Explicit timeouts: without them a hung ledger would hold a request thread forever. */
    @Bean
    PaymentGateway paymentGateway(LedgerProperties properties, Clock clock) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(properties.readTimeout());
        return new LedgerPaymentGateway(RestClient.builder().requestFactory(requests), properties, clock);
    }
}
