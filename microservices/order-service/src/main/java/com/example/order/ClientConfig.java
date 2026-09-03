package com.example.order;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;

@Configuration
public class ClientConfig {
    @Bean
    RequestInterceptor traceIdRequestInterceptor() {
        return template -> {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                template.header("X-Trace-Id", traceId);
            }
        };
    }
}
