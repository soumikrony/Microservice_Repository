package com.example.gateway;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import reactor.core.publisher.Mono;

@SpringBootApplication
@EnableWebFluxSecurity
public class ApiGatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(ApiGatewayApplication.class);
    private static final String TRACE_HEADER = "X-Trace-Id";

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    @Bean
    CommandLineRunner startupLog() {
        return args -> log.info("api-gateway started with role-aware routing, JWT validation, and trace propagation");
    }

    @Bean
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.just("ip:" + clientIp(exchange)));
    }

    @Bean
    GlobalFilter rateLimitLogFilter() {
        return (exchange, chain) -> {
            long start = System.currentTimeMillis();
            String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            return chain.filter(exchange).doFinally(signal -> {
                var headers = exchange.getResponse().getHeaders();
                String remaining = headers.getFirst("X-RateLimit-Remaining");
                String replenish = headers.getFirst("X-RateLimit-Replenish-Rate");
                String burst = headers.getFirst("X-RateLimit-Burst-Capacity");
                int status = exchange.getResponse().getStatusCode() == null
                        ? 0 : exchange.getResponse().getStatusCode().value();
                log.info("RATE_LIMIT decision={} path={} status={} remaining={} replenishRate={} burstCapacity={} traceId={} durationMs={}",
                        status == 429 ? "REJECTED" : "ALLOWED", path, status,
                        remaining, replenish, burst, traceId,
                        System.currentTimeMillis() - start);
            });
        };
    }

    private String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        if (exchange.getRequest().getRemoteAddress() != null
                && exchange.getRequest().getRemoteAddress().getAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    @Bean
    WebFilter requestLogFilter() {
        return (exchange, chain) -> {
            String incomingTraceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
            String traceId = (incomingTraceId == null || incomingTraceId.isBlank())
                    ? UUID.randomUUID().toString()
                    : incomingTraceId;

            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(builder -> builder.headers(headers -> headers.set(TRACE_HEADER, traceId)))
                    .build();

            long start = System.currentTimeMillis();
            String method = mutatedExchange.getRequest().getMethod() == null
                    ? "UNKNOWN"
                    : mutatedExchange.getRequest().getMethod().name();
            String path = mutatedExchange.getRequest().getPath().pathWithinApplication().value();

            mutatedExchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);

            return chain.filter(mutatedExchange)
                    .doFinally(signal -> {
                        long durationMs = System.currentTimeMillis() - start;
                        int statusCode = mutatedExchange.getResponse().getStatusCode() == null
                                ? 0
                                : mutatedExchange.getResponse().getStatusCode().value();
                        log.info("Gateway request traceId={} method={} path={} status={} durationMs={}",
                                traceId, method, path, statusCode, durationMs);
                    });
        };
    }

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        log.info("Configuring gateway security and route authorization rules");
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/", "/index.html", "/styles.css", "/app.js", "/favicon.ico").permitAll()
                        .pathMatchers("/auth/**", "/actuator/health", "/actuator/prometheus", "/health/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers("/catalog/admin/**", "/inventory/admin/**", "/orders/admin/**",
                                "/payments/admin/**", "/notifications/admin/**").hasRole("ADMIN")
                        .pathMatchers("/catalog/**", "/inventory/**", "/cart/**", "/orders/**",
                                "/payments/**", "/notifications/**").hasAnyRole("USER", "ADMIN")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(spec -> spec.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${security.jwt.secret}") String secret) {
        log.info("Creating gateway JWT decoder (secretLength={})", secret.length());
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusReactiveJwtDecoder.withSecretKey(key).build();
    }

    @Bean
    Converter<Jwt, reactor.core.publisher.Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter defaultScopeConverter = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> scopeAuthorities = defaultScopeConverter.convert(jwt);
            List<String> roles = jwt.getClaimAsStringList("roles");
            List<GrantedAuthority> roleAuthorities = new ArrayList<>();
            if (roles != null) {
                for (String role : roles) {
                    if (role != null && !role.isBlank()) {
                        roleAuthorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase()));
                    }
                }
            }
            log.info("JWT mapped for sub={} roles={} scopeAuthorityCount={} roleAuthorityCount={}",
                    jwt.getSubject(), roles, scopeAuthorities.size(), roleAuthorities.size());
            return Stream.concat(scopeAuthorities.stream(), roleAuthorities.stream()).toList();
        });

        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
