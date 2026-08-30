package com.example.order;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientConfig.class);
    private static final String TRACE_HEADER = "X-Trace-Id";

    @Bean
    InventoryHttpClient inventoryHttpClient(
            @Value("${app.services.inventory-url}") String baseUrl) {
        return proxyFactory(baseUrl).createClient(InventoryHttpClient.class);
    }

    @Bean
    PaymentHttpClient paymentHttpClient(
            @Value("${app.services.payment-url}") String baseUrl) {
        return proxyFactory(baseUrl).createClient(PaymentHttpClient.class);
    }

    @Bean
    CartHttpClient cartHttpClient(
            @Value("${app.services.cart-url}") String baseUrl) {
        return proxyFactory(baseUrl).createClient(CartHttpClient.class);
    }

    private HttpServiceProxyFactory proxyFactory(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(4));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(traceAndLoggingInterceptor())
                .build();

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build();
    }

    private ClientHttpRequestInterceptor traceAndLoggingInterceptor() {
        return (request, body, execution) -> {
            long start = System.currentTimeMillis();
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                request.getHeaders().set(TRACE_HEADER, traceId);
            }
            log.info("HTTP CALL START traceId={} method={} uri={}", traceId, request.getMethod(), request.getURI());
            var response = execution.execute(request, body);
            log.info("HTTP CALL COMPLETE traceId={} method={} uri={} status={} durationMs={}",
                    traceId, request.getMethod(), request.getURI(), response.getStatusCode().value(),
                    System.currentTimeMillis() - start);
            return response;
        };
    }
}
