package com.nexaplatform.dropshipping.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.InboundShopWebhookApi;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderView;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.PartnerOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
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

@Slf4j
@RestController
@RequestMapping("/api/v1/integrations/shops")
@RequiredArgsConstructor
public class InboundShopWebhookController implements InboundShopWebhookApi {

    private final ShopConnectionRepository shopRepo;
    private final ShopProductListingRepository listingRepo;
    private final ProductRepository productRepo;
    private final OrderUseCase orderUseCase;
    private final PartnerOrderDtoMapper partnerOrderDtoMapper;
    private final HmacVerifier hmac;
    private final ObjectMapper json;

    @Override
    public ResponseEntity<OrderView> receiveOrder(UUID shopId, String signature, String idempotencyKey,
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
            JsonNode root = json.readTree(rawBody);
            // Un cuerpo vacío o que no sea un objeto JSON (una lista, un número suelto) no se puede
            // recorrer por clave. Se rechaza aquí con un mensaje que dice qué se esperaba, en vez de
            // dejar que reviente al leer el primer campo.
            if (root == null || !root.isObject()) {
                log.warn("Inbound webhook from shop {} with a non-object body", shopId);
                return ResponseEntity.badRequest().build();
            }
            req = parsePayload(root, shop, idempotencyKey);
        } catch (UnknownLineItemException e) {
            log.warn("Inbound order from shop {}: unmapped SKU {}", shopId, e.sku);
            return ResponseEntity.unprocessableEntity().build();
        } catch (Exception e) {
            log.warn("Malformed inbound order payload from shop {}: {}", shopId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // userId del owner de la shop_connection → la orden queda asociada a su cuenta.
        UUID userId = shop.getUser().getId();
        OrderView view = partnerOrderDtoMapper.toOrderView(orderUseCase.createOrder(null, userId, req));
        log.info("Inbound order created from shop {} platform={} externalId={} orderId={}", shopId, shop.getPlatform(),
                req.externalOrderId(), view.id());
        return ResponseEntity.status(201).body(view);
    }

    @SuppressWarnings("unchecked")
    private String inboundSecret(ShopConnectionEntity shop) {
        Object v = shop.getMetadata() == null ? null : shop.getMetadata().get("inboundSecret");
        return v instanceof String s ? s : null;
    }

    /** Acepta tanto el shape canónico de NexaDrop como Shopify-style. */
    private CreateOrderRequest parsePayload(JsonNode root, ShopConnectionEntity shop, String idempotencyKey) {
        String externalId = externalOrderId(root, shop, idempotencyKey);
        List<OrderItemInput> items = parseItems(root, shop);
        AddressInput shipping = parseShippingAddress(root);
        return new CreateOrderRequest(externalId, shipping, null, items, text(root, "notes"));
    }

    /**
     * Referencia externa del pedido. La Idempotency-Key manda sobre el id del payload: es la que la tienda
     * reenvía al reintentar, así que es la única que garantiza no duplicar el pedido. Si no llega ninguna
     * de las dos se sintetiza una, que al menos deja el pedido trazable a la tienda de origen.
     */
    private static String externalOrderId(JsonNode root, ShopConnectionEntity shop, String idempotencyKey) {
        if (idempotencyKey != null) {
            return idempotencyKey;
        }
        String fromPayload = text(root, "externalOrderId", "id");
        return fromPayload != null ? fromPayload : "shop-" + shop.getId() + "-" + System.currentTimeMillis();
    }

    /** Líneas del pedido: se aceptan bajo la clave {@code items} o {@code line_items}. */
    private List<OrderItemInput> parseItems(JsonNode root, ShopConnectionEntity shop) {
        JsonNode itemsNode = firstPresent(root, "items", "line_items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new IllegalArgumentException("Missing items / line_items");
        }
        List<OrderItemInput> items = new ArrayList<>();
        for (JsonNode it : itemsNode) {
            int qty = it.has("quantity") ? it.get("quantity").asInt(1) : 1;
            UUID productId = resolveProductId(it, shop);
            UUID variantId = it.hasNonNull("variantId") ? UUID.fromString(it.get("variantId").asText()) : null;
            items.add(new OrderItemInput(productId, variantId, qty));
            autoListIfMissing(shop, productId, it);
        }
        return items;
    }

    /**
     * DROP-548: si el producto no está listado en la tienda, lo auto-listamos al recibir la orden — así el
     * contador "productos publicados" deja de quedar a 0 mientras hay ventas reales.
     */
    private void autoListIfMissing(ShopConnectionEntity shop, UUID productId, JsonNode item) {
        if (productId == null
                || listingRepo.findByShopConnection_IdAndProduct_Id(shop.getId(), productId).isPresent()) {
            return;
        }
        productRepo.findById(productId)
                .ifPresent(p -> listingRepo.save(ShopProductListingEntity.builder().shopConnection(shop).product(p)
                        .remoteProductId(item.hasNonNull("sku") ? item.get("sku").asText() : null).status("PUBLISHED")
                        .build()));
    }

    /** Dirección de envío: se acepta bajo la clave {@code shippingAddress} o {@code shipping_address}. */
    private static AddressInput parseShippingAddress(JsonNode root) {
        JsonNode addr = firstPresent(root, "shippingAddress", "shipping_address");
        if (addr == null) {
            // El contrato marca la dirección de envío como obligatoria (@NotNull). Dejar pasar el pedido sin
            // ella solo aplaza el fallo hasta que el transportista pide el destinatario, y para entonces ya
            // está cobrado. Se rechaza aquí, con un mensaje que dice qué falta.
            throw new IllegalArgumentException("Missing shippingAddress / shipping_address");
        }
        return new AddressInput(text(addr, "fullName", "name"), text(addr, "phone"), text(addr, "email"),
                text(addr, "line1", "address1"), text(addr, "line2", "address2"), text(addr, "city"),
                text(addr, "state", "region", "province"), text(addr, "postalCode", "zip"),
                text(addr, "country", "country_code"));
    }

    /**
     * Resuelve productId desde:
     * - productId UUID explícito
     * - SKU mapeado vía shop_product_listing.remote_product_id
     * - SKU directo en ProductEntity.externalId
     */
    private UUID resolveProductId(JsonNode item, ShopConnectionEntity shop) {
        if (item.hasNonNull("productId")) {
            return UUID.fromString(item.get("productId").asText());
        }
        String remoteId = text(item, "remoteProductId", "sku", "product_id");
        if (remoteId == null)
            throw new UnknownLineItemException("(no sku)");
        // 1) listing publicado desde NexaDrop
        ShopProductListingEntity listing = listingRepo.findByShopConnection_IdAndRemoteProductId(shop.getId(), remoteId)
                .orElse(null);
        if (listing != null)
            return listing.getProduct().getId();
        // 2) match por externalId en catálogo
        ProductEntity p = productRepo.findFirstByExternalId(remoteId).orElse(null);
        if (p != null)
            return p.getId();
        throw new UnknownLineItemException(remoteId);
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null)
            return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isEmpty())
                return v.asText();
        }
        return null;
    }

    static class UnknownLineItemException extends RuntimeException {
        final String sku;

        UnknownLineItemException(String sku) {
            super("Unknown SKU: " + sku);
            this.sku = sku;
        }
    }

    /**
     * Primer campo presente de entre varios nombres. Cada tienda manda la misma información con la clave
     * en su propio estilo ({@code lineItems} o {@code line_items}), así que el consumidor acepta ambos.
     */
    private static JsonNode firstPresent(JsonNode root, String... names) {
        if (root == null) {
            return null;
        }
        for (String name : names) {
            if (root.has(name)) {
                return root.get(name);
            }
        }
        return null;
    }
}
