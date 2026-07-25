package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.PlanLimitService;
import com.nexaplatform.dropshipping.application.usecase.ShopConnectionUseCase;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.domain.model.ShopInboundSecret;
import com.nexaplatform.dropshipping.domain.model.ShopPlatform;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import com.nexaplatform.dropshipping.domain.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.domain.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.shop.ShopConnector;
import com.nexaplatform.dropshipping.infrastructure.integration.shop.ShopConnectorRegistry;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DROP-5: use case for the user's shop integrations (Shopify, Woo, TikTok, eBay,
 * Amazon, BigCommerce, Wix, Shopee, Lazada…). Operates on the {@link ShopConnection}
 * aggregate and its {@link ShopProductListing} sub-entity, delegating persistence
 * to the domain ports. Holds all the logic that used to live in {@code ShopService}:
 * ownership checks, access-token encryption and inbound-secret handling. The
 * {@link TokenCryptoService} is kept as a collaborator for token encryption.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopConnectionUseCaseImpl implements ShopConnectionUseCase {

    private final ShopConnectionRepository shopRepository;
    private final ShopProductListingRepository listingRepository;
    private final TokenCryptoService tokenCrypto;
    private final ShopConnectorRegistry connectorRegistry;
    private final ProductRepository productRepository;
    private final PlanLimitService planLimitService;

    @Override
    @Transactional(readOnly = true)
    public List<ShopConnection> listByUser(UUID userId) {
        List<ShopConnection> shops = shopRepository.findByUserId(userId);
        for (ShopConnection s : shops) {
            s.setListings(listingRepository.countByShopConnectionId(s.getId()));
        }
        return shops;
    }

    @Override
    @Transactional
    public ShopConnection connect(UUID userId, ShopConnection model) {
        // Límite por plan: nº de tiendas conectadas (max_shops). Lanza si se supera el del plan del usuario.
        planLimitService.assertWithinLimit(userId, "max_shops", shopRepository.findByUserId(userId).size());
        // AES-256-GCM with envelope key (rotation supported via TokenCryptoService).
        String encoded = tokenCrypto.encrypt(model.getAccessTokenEnc());
        ShopConnection toPersist = model.withUserId(userId)
                .withPlatform(model.getPlatform() != null ? model.getPlatform().toLowerCase() : null)
                .withAccessTokenEnc(encoded).withStatus("CONNECTED").withMetadata(new HashMap<>());
        ShopConnection saved = shopRepository.save(toPersist);
        saved.setListings(0);
        return saved;
    }

    @Override
    @Transactional
    public ShopConnection sync(UUID userId, UUID id) {
        ShopConnection s = require(userId, id);
        s.setLastSyncAt(Instant.now());

        List<ShopProductListing> listings = listingRepository.findByShopConnectionId(id);
        Optional<ShopConnector> connector = connectorRegistry.connectorFor(s.getPlatform());

        // DROP-693: real, observable sync. Previously a Shopify store could sit at 0 published products
        // with no reason and no log. Now we record WHY: no connector, no products, or the API error.
        if (connector.isEmpty()) {
            s.setStatus("ERROR");
            s.setLastSyncError(null);
            s.setLastSyncMessage("La integración con '" + s.getPlatform()
                    + "' aún no está disponible (Próximamente). No se publicó ningún producto.");
        } else if (listings.isEmpty()) {
            s.setStatus("CONNECTED");
            s.setLastSyncError(null);
            s.setLastSyncMessage("0 productos para sincronizar — añade productos a la tienda antes de sincronizar.");
        } else {
            String token = decryptToken(s);
            int ok = 0;
            int failed = 0;
            String firstError = null;
            for (ShopProductListing listing : listings) {
                ProductEntity product = productRepository.findById(listing.getProductId()).orElse(null);
                if (product == null) {
                    failed++;
                    String msg = "Producto no encontrado: " + listing.getProductId();
                    listing.setStatus("ERROR");
                    listing.setErrorMessage(msg);
                    if (firstError == null) {
                        firstError = msg;
                    }
                } else {
                    ShopConnector.PushResult r = connector.get().push(s, token, product);
                    if (r.ok()) {
                        ok++;
                        listing.setStatus("LISTED");
                        listing.setRemoteProductId(r.remoteProductId());
                        listing.setErrorMessage(null);
                    } else {
                        failed++;
                        listing.setStatus("ERROR");
                        listing.setErrorMessage(r.error());
                        if (firstError == null) {
                            firstError = r.error();
                        }
                    }
                }
                listing.setLastPushedAt(Instant.now());
                listingRepository.save(listing);
            }
            s.setStatus(failed > 0 && ok == 0 ? "ERROR" : "CONNECTED");
            s.setLastSyncError(firstError);
            s.setLastSyncMessage(ok + " publicados, " + failed + " con error.");
            log.info("Shop {} sync: {} ok, {} failed", id, ok, failed);
        }

        ShopConnection saved = shopRepository.update(s);
        saved.setListings(listingRepository.countByShopConnectionId(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public void disconnect(UUID userId, UUID id) {
        require(userId, id);
        shopRepository.delete(id);
    }

    /**
     * Emits (or rotates) the shared HMAC secret so the shop can sign inbound
     * order webhooks. The secret is returned only once — store it on your side.
     */
    @Override
    @Transactional
    public ShopInboundSecret rotateInboundSecret(UUID userId, UUID id) {
        ShopConnection s = require(userId, id);
        // 32 random bytes, URL-safe Base64 without padding -> ~43 chars.
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        if (s.getMetadata() == null)
            s.setMetadata(new HashMap<>());
        s.getMetadata().put("inboundSecret", secret);
        shopRepository.update(s);
        return ShopInboundSecret.builder().inboundSecret(secret)
                .inboundUrl("/api/v1/integrations/shops/" + s.getId() + "/orders").build();
    }

    @Override
    @Transactional
    public ShopProductListing listProduct(UUID userId, UUID id, UUID productId) {
        ShopConnection s = require(userId, id);
        ShopProductListing listing = listingRepository.findByShopConnectionIdAndProductId(id, productId)
                .orElseGet(() -> ShopProductListing.builder().shopConnectionId(id).productId(productId).build());
        listing.setLastPushedAt(Instant.now());

        Optional<ShopConnector> connector = connectorRegistry.connectorFor(s.getPlatform());
        ProductEntity product = productRepository.findById(productId).orElse(null);
        if (connector.isEmpty()) {
            // DROP-701: honest failure instead of a fake "remote-xxxx" id.
            listing.setStatus("ERROR");
            listing.setErrorMessage("La integración con '" + s.getPlatform() + "' aún no está disponible (Próximamente).");
        } else if (product == null) {
            listing.setStatus("ERROR");
            listing.setErrorMessage("Producto no encontrado: " + productId);
        } else {
            ShopConnector.PushResult r = connector.get().push(s, decryptToken(s), product);
            if (r.ok()) {
                listing.setStatus("LISTED");
                listing.setRemoteProductId(r.remoteProductId());
                listing.setErrorMessage(null);
            } else {
                listing.setStatus("ERROR");
                listing.setErrorMessage(r.error());
            }
        }
        return listingRepository.save(listing);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShopProductListing> listings(UUID userId, UUID id) {
        require(userId, id);
        return listingRepository.findByShopConnectionId(id);
    }

    @Override
    public List<ShopPlatform> platforms() {
        // DROP-701: honest catalog — availability reflects which connectors are actually implemented.
        return connectorRegistry.catalog();
    }

    /** Decrypts the stored access token; tolerates legacy plaintext tokens. */
    private String decryptToken(ShopConnection s) {
        String enc = s.getAccessTokenEnc();
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return tokenCrypto.isModern(enc) ? tokenCrypto.decrypt(enc) : enc;
        } catch (RuntimeException ex) {
            log.warn("Could not decrypt access token for shop {}: {}", s.getId(), ex.getMessage());
            return null;
        }
    }

    /** Loads a shop by id and enforces that it is owned by the given user. */
    private ShopConnection require(UUID userId, UUID id) {
        ShopConnection s = shopRepository.getById(id);
        if (s == null) {
            throw new NotFoundException("Shop");
        }
        if (!s.getUserId().equals(userId)) {
            throw new NotFoundException("Shop");
        }
        return s;
    }
}
