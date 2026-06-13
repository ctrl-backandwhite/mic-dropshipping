package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Sincroniza las tasas de cambio a diario desde un proveedor FX gratuito (base USD). El feed devuelve
 * "1 USD = X CCY", que es exactamente la convención {@code rate_vs_usd} (unidades por USD) que usan
 * {@link CurrencyRateService} y el frontend. Best-effort: si el proveedor no responde, se conservan las
 * tasas actuales (no se rompe el pricing). Así los precios en EUR/USD quedan sujetos a la tasa del día.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyRateSyncScheduler {

    private final CurrencyRateService currencyRateService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Value("${nexadrop.currency.sync-enabled:true}")
    private boolean enabled;

    @Value("${nexadrop.currency.sync-url:https://open.er-api.com/v6/latest/USD}")
    private String url;

    /** Arranca ~30 s tras el boot y se repite cada 24 h. */
    @Scheduled(initialDelay = 30_000L, fixedRate = 86_400_000L)
    public void sync() {
        if (!enabled) {
            return;
        }
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Currency sync: HTTP {} from {}", res.statusCode(), url);
                return;
            }
            JsonNode rates = objectMapper.readTree(res.body()).get("rates");
            if (rates == null || !rates.isObject()) {
                log.warn("Currency sync: unexpected payload");
                return;
            }
            Map<String, BigDecimal> map = new HashMap<>();
            rates.fields().forEachRemaining(e -> {
                try {
                    map.put(e.getKey().toUpperCase(), new BigDecimal(e.getValue().asText()));
                } catch (RuntimeException ignore) {
                    // saltar entradas no numéricas
                }
            });
            currencyRateService.applyBulkSync(map);
        } catch (Exception e) {
            log.warn("Currency rate sync failed (se conservan las tasas actuales): {}", e.getMessage());
        }
    }
}
