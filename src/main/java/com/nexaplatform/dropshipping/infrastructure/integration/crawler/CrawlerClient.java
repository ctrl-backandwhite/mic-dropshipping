package com.nexaplatform.dropshipping.infrastructure.integration.crawler;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CrawlerClient {

    @Value("${nexadrop.crawler.base-url}")
    private String baseUrl;

    @Value("${nexadrop.crawler.timeout-seconds:60}")
    private int timeoutSeconds;

    private WebClient webClient() {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Retry(name = "crawler")
    @CircuitBreaker(name = "crawler")
    public Mono<Map<String, Object>> requestProductScrape(String source, String offerId) {
        return webClient().post().uri("/scrape/product")
                .bodyValue(Map.of("source", source, "kind", "product", "target", offerId)).retrieve()
                .bodyToMono(Map.class).map(m -> (Map<String, Object>) m).timeout(Duration.ofSeconds(timeoutSeconds));
    }

    @Retry(name = "crawler")
    @CircuitBreaker(name = "crawler")
    public Mono<Map<String, Object>> requestBestsellerScrape(String listUrl) {
        return webClient().post().uri("/scrape/bestsellers")
                .bodyValue(Map.of("source", "1688", "kind", "bestseller", "target", listUrl)).retrieve()
                .bodyToMono(Map.class).map(m -> (Map<String, Object>) m).timeout(Duration.ofSeconds(timeoutSeconds));
    }
}
