package com.example.cart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableCaching
public class CartServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(CartServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner startupLog() {
        return args -> log.info("cart-service started with customer cart APIs");
    }
}
