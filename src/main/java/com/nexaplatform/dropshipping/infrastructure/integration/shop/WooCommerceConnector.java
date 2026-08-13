package com.nexaplatform.dropshipping.infrastructure.integration.shop;

import com.nexaplatform.dropshipping.application.service.Texts;
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
    // Anti-SSRF: NUNCA seguir redirecciones automáticamente. Solo se valida el host INICIAL (ShopHostGuard);
    // un 3xx a un host interno (169.254.169.254, 10.x…) se seguiría sin re-validar y su cuerpo se reflejaría
    // en el error → exfiltración. Una API real de WooCommerce no redirige a la red interna.
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();

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
            // Map.of no admite nulos: un producto sin título NI slug reventaba con un NPE que el catch
            // genérico convertía en «No se pudo conectar con WooCommerce: null», despistando sobre la causa.
            String title = Texts.firstNonBlank(product.getTitleZh(), product.getSlug());
            if (title == null) {
                return PushResult.fail("El producto no tiene título ni identificador: complétalo antes de publicarlo.");
            }
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
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("WooCommerce push error for shop {}: {}", shop.getId(), ex.getMessage());
            return PushResult.fail("No se pudo conectar con WooCommerce: " + ex.getMessage());
        }
    }

    private String normalizeBase(String handle) {
        if (handle == null || handle.isBlank()) {
            return null;
        }
        String h = Texts.stripTrailingSlashes(handle.trim());
        // Forzamos HTTPS: la petición lleva las credenciales del partner en Authorization: Basic. Un
        // http:// explícito las expondría en claro. Sin esquema o con http:// → https://.
        if (h.startsWith("http://")) {
            h = "https://" + h.substring("http://".length());
        } else if (!h.startsWith("https://")) {
            h = "https://" + h;
        }
        // Anti-SSRF: el host lo controla el usuario. Rechazamos cualquier destino que resuelva a la red
        // interna (loopback/privadas/metadata), para que el conector no pueda usarse para alcanzar servicios
        // internos ni escanear puertos.
        try {
            String host = URI.create(h).getHost();
            if (host == null || !ShopHostGuard.isPublicHost(host)) {
                return null;
            }
        } catch (IllegalArgumentException ex) {
            return null;
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
