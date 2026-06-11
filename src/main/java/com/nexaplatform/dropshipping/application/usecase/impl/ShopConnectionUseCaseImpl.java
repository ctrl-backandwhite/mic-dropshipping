package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.ShopConnectionUseCase;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.domain.model.ShopInboundSecret;
import com.nexaplatform.dropshipping.domain.model.ShopPlatform;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import com.nexaplatform.dropshipping.domain.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.domain.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * DROP-5: use case for the user's shop integrations (Shopify, Woo, TikTok, eBay,
 * Amazon, BigCommerce, Wix, Shopee, Lazada…). Operates on the {@link ShopConnection}
 * aggregate and its {@link ShopProductListing} sub-entity, delegating persistence
 * to the domain ports. Holds all the logic that used to live in {@code ShopService}:
 * ownership checks, access-token encryption and inbound-secret handling. The
 * {@link TokenCryptoService} is kept as a collaborator for token encryption.
 */
@Service
@RequiredArgsConstructor
public class ShopConnectionUseCaseImpl implements ShopConnectionUseCase {

    private final ShopConnectionRepository shopRepository;
    private final ShopProductListingRepository listingRepository;
    private final TokenCryptoService tokenCrypto;

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
        // AES-256-GCM with envelope key (rotation supported via TokenCryptoService).
        String encoded = tokenCrypto.encrypt(model.getAccessTokenEnc());
        ShopConnection toPersist = model
                .withUserId(userId)
                .withPlatform(model.getPlatform() != null ? model.getPlatform().toLowerCase() : null)
                .withAccessTokenEnc(encoded)
                .withStatus("CONNECTED")
                .withMetadata(new HashMap<>());
        ShopConnection saved = shopRepository.save(toPersist);
        saved.setListings(0);
        return saved;
    }

    @Override
    @Transactional
    public ShopConnection sync(UUID userId, UUID id) {
        ShopConnection s = require(userId, id);
        s.setLastSyncAt(Instant.now());
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
        if (s.getMetadata() == null) s.setMetadata(new HashMap<>());
        s.getMetadata().put("inboundSecret", secret);
        shopRepository.update(s);
        return ShopInboundSecret.builder()
                .inboundSecret(secret)
                .inboundUrl("/api/v1/integrations/shops/" + s.getId() + "/orders")
                .build();
    }

    @Override
    @Transactional
    public ShopProductListing listProduct(UUID userId, UUID id, UUID productId) {
        require(userId, id);
        ShopProductListing listing = listingRepository.findByShopConnectionIdAndProductId(id, productId)
                .orElseGet(() -> ShopProductListing.builder()
                        .shopConnectionId(id).productId(productId).build());
        listing.setStatus("LISTED");
        listing.setRemoteProductId("remote-" + UUID.randomUUID().toString().substring(0, 8));
        listing.setLastPushedAt(Instant.now());
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
        return List.of(
                ShopPlatform.builder().code("shopify").label("Shopify").build(),
                ShopPlatform.builder().code("woocommerce").label("WooCommerce").build(),
                ShopPlatform.builder().code("tiktokshop").label("TikTok Shop").build(),
                ShopPlatform.builder().code("ebay").label("eBay").build(),
                ShopPlatform.builder().code("amazon").label("Amazon").build(),
                ShopPlatform.builder().code("bigcommerce").label("BigCommerce").build(),
                ShopPlatform.builder().code("wix").label("Wix").build(),
                ShopPlatform.builder().code("squarespace").label("Squarespace").build(),
                ShopPlatform.builder().code("magento").label("Magento").build(),
                ShopPlatform.builder().code("lazada").label("Lazada").build(),
                ShopPlatform.builder().code("shopee").label("Shopee").build());
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
