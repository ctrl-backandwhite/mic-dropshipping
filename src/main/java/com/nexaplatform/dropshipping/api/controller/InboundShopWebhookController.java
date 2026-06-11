package com.nexaplatform.dropshipping.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.InboundShopWebhookApi;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderView;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.OrderService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.HmacVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sync inverso: cuando tu e-commerce recibe un pedido, lo reenvía a NexaDrop
 * con HMAC. Endpoint público pero firmado — no requiere OAuth2 porque viene
 * desde tu sistema, no de un partner_app.
 *
 * Flujo:
 *   1. Tu tienda concentra el body y calcula HMAC-SHA256(body, shop.inboundSecret)
 *   2. POST /api/v1/integrations/shops/{shopId}/orders
 *      Headers:
 *        X-NX-Signature: <hex_hmac>
 *        Idempotency-Key: <orden_id_externo>  (opcional, recomendado)
 *   3. NexaDrop verifica HMAC en tiempo constante, mapea el payload al
 *      formato CreateOrderRequest y delega en OrderService.createOrder().
 *
 * El payload es flexible — acepta el shape canónico de NexaDrop o el shape
 * Shopify-like (line_items / shipping_address). El parser intenta ambos.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/integrations/shops")
@RequiredArgsConstructor
public class InboundShopWebhookController implements InboundShopWebhookApi {

    private final ShopConnectionRepository shopRepo;
    private final ShopProductListingRepository listingRepo;
    private final ProductRepository productRepo;
    private final OrderService orderService;
    private final HmacVerifier hmac;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<OrderView> receiveOrder(
            UUID shopId,
            String signature,
            String idempotencyKey,
            byte[] rawBody) {

        ShopConnectionEntity shop = shopRepo.findById(shopId)
                .orElseThrow(() -> new NotFoundException("ShopConnection"));

        String secret = inboundSecret(shop);
        if (secret == null) {
            return ResponseEntity.status(401).build();
        }
        if (!hmac.verify(secret, rawBody, signature)) {
            log.warn("Rejected inbound webhook for shop {}: HMAC mismatch", shopId);
            return ResponseEntity.status(401).build();
        }

        CreateOrderRequest req;
        try {
            req = parsePayload(json.readTree(rawBody), shop, idempotencyKey);
        } catch (UnknownLineItemException e) {
            log.warn("Inbound order from shop {}: unmapped SKU {}", shopId, e.sku);
            return ResponseEntity.unprocessableEntity().build();
        } catch (Exception e) {
            log.warn("Malformed inbound order payload from shop {}: {}", shopId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // userId del owner de la shop_connection → la orden queda asociada a su cuenta.
        UUID userId = shop.getUser().getId();
        OrderView view = orderService.createOrder(null, userId, req);
        log.info("Inbound order created from shop {} platform={} externalId={} orderId={}",
                shopId, shop.getPlatform(), req.externalOrderId(), view.id());
        return ResponseEntity.status(201).body(view);
    }

    @SuppressWarnings("unchecked")
    private String inboundSecret(ShopConnectionEntity shop) {
        Object v = shop.getMetadata() == null ? null : shop.getMetadata().get("inboundSecret");
        return v instanceof String s ? s : null;
    }

    /** Acepta tanto el shape canónico de NexaDrop como Shopify-style. */
    private CreateOrderRequest parsePayload(JsonNode root, ShopConnectionEntity shop, String idempotencyKey) {
        String externalId = idempotencyKey;
        if (externalId == null) externalId = text(root, "externalOrderId", "id");
        if (externalId == null) externalId = "shop-" + shop.getId() + "-" + System.currentTimeMillis();

        // Items: aceptamos `items` o `line_items`.
        JsonNode itemsNode = root.has("items") ? root.get("items")
                : root.has("line_items") ? root.get("line_items") : null;
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new IllegalArgumentException("Missing items / line_items");
        }
        List<OrderItemInput> items = new ArrayList<>();
        for (JsonNode it : itemsNode) {
            int qty = it.has("quantity") ? it.get("quantity").asInt(1) : 1;
            UUID productId = resolveProductId(it, shop);
            UUID variantId = it.hasNonNull("variantId") ? UUID.fromString(it.get("variantId").asText()) : null;
            items.add(new OrderItemInput(productId, variantId, qty));
            // DROP-548: si el producto no está listado en la tienda, lo
            // auto-listamos al recibir la orden — así el contador "productos
            // publicados" deja de quedar a 0 mientras hay ventas reales.
            if (productId != null && !listingRepo.findByShopConnection_IdAndProduct_Id(shop.getId(), productId).isPresent()) {
                productRepo.findById(productId).ifPresent(p ->
                    listingRepo.save(ShopProductListingEntity.builder()
                        .shopConnection(shop).product(p)
                        .remoteProductId(it.hasNonNull("sku") ? it.get("sku").asText() : null)
                        .status("PUBLISHED")
                        .build())
                );
            }
        }

        // Address: `shippingAddress` o `shipping_address`.
        JsonNode addr = root.has("shippingAddress") ? root.get("shippingAddress")
                : root.has("shipping_address") ? root.get("shipping_address") : null;
        AddressInput shipping = addr == null ? null : new AddressInput(
                text(addr, "fullName", "name"),
                text(addr, "phone"),
                text(addr, "email"),
                text(addr, "line1", "address1"),
                text(addr, "line2", "address2"),
                text(addr, "city"),
                text(addr, "state", "region", "province"),
                text(addr, "postalCode", "zip"),
                text(addr, "country", "country_code"));

        return new CreateOrderRequest(externalId, shipping, null, items, text(root, "notes"));
    }

    /**
     * Resuelve productId desde:
     *   - productId UUID explícito
     *   - SKU mapeado vía shop_product_listing.remote_product_id
     *   - SKU directo en ProductEntity.externalId
     */
    private UUID resolveProductId(JsonNode item, ShopConnectionEntity shop) {
        if (item.hasNonNull("productId")) {
            return UUID.fromString(item.get("productId").asText());
        }
        String remoteId = text(item, "remoteProductId", "sku", "product_id");
        if (remoteId == null) throw new UnknownLineItemException("(no sku)");
        // 1) listing publicado desde NexaDrop
        ShopProductListingEntity listing = listingRepo
                .findByShopConnection_IdAndRemoteProductId(shop.getId(), remoteId).orElse(null);
        if (listing != null) return listing.getProduct().getId();
        // 2) match por externalId en catálogo
        ProductEntity p = productRepo.findFirstByExternalId(remoteId).orElse(null);
        if (p != null) return p.getId();
        throw new UnknownLineItemException(remoteId);
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isEmpty()) return v.asText();
        }
        return null;
    }

    static class UnknownLineItemException extends RuntimeException {
        final String sku;
        UnknownLineItemException(String sku) { super("Unknown SKU: " + sku); this.sku = sku; }
    }
}
