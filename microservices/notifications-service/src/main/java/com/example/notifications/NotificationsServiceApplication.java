package com.example.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NotificationsServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(NotificationsServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NotificationsServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner startupLog() {
        return args -> log.info("notifications-service started with kafka notification subscribers");
    }
}
