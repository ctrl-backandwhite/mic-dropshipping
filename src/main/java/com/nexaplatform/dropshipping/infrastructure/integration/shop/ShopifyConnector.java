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
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DROP-701: real Shopify connector using the Admin REST API. Publishes a catalog product as a
 * Shopify product (POST /admin/api/{version}/products.json) authenticated with the store access
 * token (X-Shopify-Access-Token). The store handle is the {@code *.myshopify.com} host.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopifyConnector implements ShopConnector {

    private static final String API_VERSION = "2024-10";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public String platform() {
        return "shopify";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public PushResult push(ShopConnection shop, String decryptedToken, ProductEntity product) {
        if (decryptedToken == null || decryptedToken.isBlank()) {
            return PushResult.fail("Falta el token de acceso de Shopify (Admin API access token).");
        }
        String host = normalizeHost(shop.getShopHandle());
        if (host == null) {
            return PushResult.fail("Handle de tienda inválido: se esperaba algo como mi-tienda.myshopify.com");
        }
        try {
            String title = product.getTitleZh() != null ? product.getTitleZh() : product.getSlug();
            BigDecimal price = product.getBasePrice() != null ? product.getBasePrice() : BigDecimal.ZERO;
            Map<String, Object> body = Map.of("product", Map.of(
                    "title", title,
                    "body_html", product.getDescriptionZh() != null ? product.getDescriptionZh() : "",
                    "vendor", product.getBrand() != null ? product.getBrand() : "",
                    "status", "active",
                    "variants", List.of(Map.of("price", price.toPlainString()))));
            String json = objectMapper.writeValueAsString(body);
            URI uri = URI.create("https://" + host + "/admin/api/" + API_VERSION + "/products.json");
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("X-Shopify-Access-Token", decryptedToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                JsonNode root = objectMapper.readTree(res.body());
                String remoteId = root.path("product").path("id").asText(null);
                return PushResult.ok(remoteId != null ? "shopify-" + remoteId : "shopify");
            }
            log.warn("Shopify push failed ({}): {}", res.statusCode(), truncate(res.body()));
            return PushResult.fail("Shopify respondió " + res.statusCode() + ": " + truncate(res.body()));
        } catch (Exception ex) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Shopify push error for shop {}: {}", shop.getId(), ex.getMessage());
            return PushResult.fail("No se pudo conectar con Shopify: " + ex.getMessage());
        }
    }

    /** Accepts "shop.myshopify.com", "https://shop.myshopify.com" or "shop" → "shop.myshopify.com". */
    private String normalizeHost(String handle) {
        if (handle == null || handle.isBlank()) {
            return null;
        }
        String h = handle.trim().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        if (h.isEmpty()) {
            return null;
        }
        return h.contains(".") ? h : h + ".myshopify.com";
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
