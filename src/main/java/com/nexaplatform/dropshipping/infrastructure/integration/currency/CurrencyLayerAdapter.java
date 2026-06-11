package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches latest FX rates from CurrencyLayer (https://api.currencylayer.com/live).
 * When the API key is missing, the scheduler is a no-op — we keep the seeded rates.
 *
 * The free tier returns quotes against USD only (e.g. USDEUR=0.92), which is exactly
 * what we store as {@code rate_vs_usd}.
 */
@Slf4j
@Service
public class CurrencyLayerAdapter {

    @Value("${nexadrop.currency.access-key:}")
    private String accessKey;

    @Value("${nexadrop.currency.api-url:https://api.currencylayer.com/live}")
    private String apiUrl;

    private final WebClient.Builder webClientBuilder;
    private final CurrencyRateService currencyRateService;

    public CurrencyLayerAdapter(WebClient.Builder builder, CurrencyRateService currencyRateService) {
        this.webClientBuilder = builder;
        this.currencyRateService = currencyRateService;
    }

    /** Daily at 04:30 UTC. No-op if API key missing. */
    @Scheduled(cron = "${nexadrop.currency.sync-cron:0 30 4 * * *}")
    public void scheduledSync() {
        if (accessKey == null || accessKey.isBlank()) {
            log.debug("CurrencyLayer sync skipped — no NEXADROP_CURRENCY_ACCESS_KEY set");
            return;
        }
        try {
            Map<String, BigDecimal> rates = fetchLive();
            currencyRateService.applyBulkSync(rates);
        } catch (Exception e) {
            log.warn("CurrencyLayer sync failed: {}", e.getMessage());
        }
    }

    public Map<String, BigDecimal> fetchLive() {
        if (accessKey == null || accessKey.isBlank()) return Collections.emptyMap();
        String url = apiUrl + "?access_key=" + accessKey;

        @SuppressWarnings("unchecked")
        Map<String, Object> body = webClientBuilder.build().get().uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(15))
                .block();

        if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
            log.warn("CurrencyLayer returned non-success body");
            return Collections.emptyMap();
        }
        @SuppressWarnings("unchecked")
        Map<String, Number> quotes = (Map<String, Number>) body.get("quotes");
        if (quotes == null) return Collections.emptyMap();

        Map<String, BigDecimal> out = new HashMap<>();
        for (var entry : quotes.entrySet()) {
            String k = entry.getKey().toUpperCase(Locale.ROOT);
            if (k.length() == 6 && k.startsWith("USD")) {
                out.put(k.substring(3), new BigDecimal(entry.getValue().toString()));
            }
        }
        log.info("CurrencyLayer fetched {} rates", out.size());
        return out;
    }
}
