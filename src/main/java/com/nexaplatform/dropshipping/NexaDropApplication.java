package com.nexaplatform.dropshipping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EntityScan(basePackages = {
        "com.nexaplatform.dropshipping.infrastructure.persistence.entity",
        "com.nexaplatform.dropshipping.infrastructure.messaging.outbox"
})
@EnableJpaRepositories(basePackages = {
        "com.nexaplatform.dropshipping.infrastructure.persistence.repository",
        "com.nexaplatform.dropshipping.infrastructure.messaging.outbox"
})
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableScheduling
@EnableAsync
@EnableKafka
public class NexaDropApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexaDropApplication.class, args);
    }
}
