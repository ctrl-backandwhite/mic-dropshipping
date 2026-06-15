package com.nexaplatform.dropshipping.infrastructure.integration.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * DROP-701: real WooCommerce connector using the WC REST API v3. Publishes a catalog product
 * (POST {storeUrl}/wp-json/wc/v3/products) authenticated with Basic auth, where the access token
 * is stored as {@code consumerKey:consumerSecret}. The store handle is the WordPress base URL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WooCommerceConnector implements ShopConnector {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public String platform() {
        return "woocommerce";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public PushResult push(ShopConnection shop, String decryptedToken, ProductEntity product) {
        if (decryptedToken == null || !decryptedToken.contains(":")) {
            return PushResult.fail("Credenciales WooCommerce inválidas: usa 'consumerKey:consumerSecret' como token.");
        }
        String base = normalizeBase(shop.getShopHandle());
        if (base == null) {
            return PushResult.fail("URL de tienda inválida: se esperaba la URL base de WordPress (https://mi-tienda.com).");
        }
        try {
            String title = product.getTitleZh() != null ? product.getTitleZh() : product.getSlug();
            BigDecimal price = product.getBasePrice() != null ? product.getBasePrice() : BigDecimal.ZERO;
            Map<String, Object> body = Map.of(
                    "name", title,
                    "type", "simple",
                    "regular_price", price.toPlainString(),
                    "description", product.getDescriptionZh() != null ? product.getDescriptionZh() : "",
                    "short_description", product.getShortDescriptionZh() != null ? product.getShortDescriptionZh() : "");
            String json = objectMapper.writeValueAsString(body);
            String basic = Base64.getEncoder().encodeToString(decryptedToken.getBytes(StandardCharsets.UTF_8));
            URI uri = URI.create(base + "/wp-json/wc/v3/products");
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                JsonNode root = objectMapper.readTree(res.body());
                String remoteId = root.path("id").asText(null);
                return PushResult.ok(remoteId != null ? "woo-" + remoteId : "woo");
            }
            log.warn("WooCommerce push failed ({}): {}", res.statusCode(), truncate(res.body()));
            return PushResult.fail("WooCommerce respondió " + res.statusCode() + ": " + truncate(res.body()));
        } catch (Exception ex) {
            log.warn("WooCommerce push error for shop {}: {}", shop.getId(), ex.getMessage());
            return PushResult.fail("No se pudo conectar con WooCommerce: " + ex.getMessage());
        }
    }

    private String normalizeBase(String handle) {
        if (handle == null || handle.isBlank()) {
            return null;
        }
        String h = handle.trim().replaceAll("/+$", "");
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "https://" + h;
        }
        return h;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
