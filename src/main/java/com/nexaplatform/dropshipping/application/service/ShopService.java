package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.ShopDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopInboundSecretDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopListingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopPlatformDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.ShopDtoMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
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
 * DROP-5: use-case service for the user's shop integrations (Shopify, Woo,
 * TikTok, eBay, Amazon, BigCommerce, Wix, Shopee, Lazada…). Holds all the logic
 * that used to live inside {@code ShopController}.
 */
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopConnectionRepository shopRepo;
    private final ShopProductListingRepository listingRepo;
    private final UserRepository userRepo;
    private final ProductRepository productRepo;
    private final TokenCryptoService tokenCrypto;
    private final ShopDtoMapper shopDtoMapper;

    @Transactional(readOnly = true)
    public List<ShopDtoOut> list(UUID userId) {
        return shopRepo.findByUser_IdOrderByCreatedAtDesc(userId).stream()
                .map(s -> shopDtoMapper.toShop(s, listingRepo.findByShopConnection_Id(s.getId()).size()))
                .toList();
    }

    @Transactional
    public ShopDtoOut connect(UUID userId, String platform, String shopHandle, String accessToken) {
        UserEntity u = userRepo.findById(userId).orElseThrow();
        // AES-256-GCM with envelope key (rotation supported via TokenCryptoService).
        String encoded = tokenCrypto.encrypt(accessToken);
        ShopConnectionEntity s = ShopConnectionEntity.builder()
                .user(u).platform(platform.toLowerCase()).shopHandle(shopHandle)
                .accessTokenEnc(encoded).status("CONNECTED").metadata(new HashMap<>()).build();
        s = shopRepo.save(s);
        return shopDtoMapper.toShop(s, 0);
    }

    @Transactional
    public ShopDtoOut sync(UUID userId, UUID id) {
        ShopConnectionEntity s = require(userId, id);
        s.setLastSyncAt(Instant.now());
        shopRepo.save(s);
        return shopDtoMapper.toShop(s, listingRepo.findByShopConnection_Id(s.getId()).size());
    }

    @Transactional
    public void disconnect(UUID userId, UUID id) {
        ShopConnectionEntity s = require(userId, id);
        shopRepo.delete(s);
    }

    /**
     * Emits (or rotates) the shared HMAC secret so the shop can sign inbound
     * order webhooks. The secret is returned only once — store it on your side.
     */
    @Transactional
    public ShopInboundSecretDtoOut rotateInboundSecret(UUID userId, UUID id) {
        ShopConnectionEntity s = require(userId, id);
        // 32 random bytes, URL-safe Base64 without padding -> ~43 chars.
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        if (s.getMetadata() == null) s.setMetadata(new HashMap<>());
        s.getMetadata().put("inboundSecret", secret);
        shopRepo.save(s);
        return ShopInboundSecretDtoOut.builder()
                .inboundSecret(secret)
                .inboundUrl("/api/v1/integrations/shops/" + s.getId() + "/orders")
                .build();
    }

    @Transactional
    public ShopListingDtoOut listProduct(UUID userId, UUID id, UUID productId) {
        ShopConnectionEntity s = require(userId, id);
        ProductEntity p = productRepo.findById(productId).orElseThrow(() -> new NotFoundException("Product"));
        ShopProductListingEntity l = listingRepo.findByShopConnection_IdAndProduct_Id(id, productId)
                .orElseGet(() -> ShopProductListingEntity.builder().shopConnection(s).product(p).build());
        l.setStatus("LISTED");
        l.setRemoteProductId("remote-" + UUID.randomUUID().toString().substring(0, 8));
        l.setLastPushedAt(Instant.now());
        l = listingRepo.save(l);
        return shopDtoMapper.toListing(l);
    }

    @Transactional(readOnly = true)
    public List<ShopListingDtoOut> listings(UUID userId, UUID id) {
        require(userId, id);
        return listingRepo.findByShopConnection_Id(id).stream()
                .map(shopDtoMapper::toListing).toList();
    }

    public List<ShopPlatformDtoOut> platforms() {
        return List.of(
                ShopPlatformDtoOut.builder().code("shopify").label("Shopify").build(),
                ShopPlatformDtoOut.builder().code("woocommerce").label("WooCommerce").build(),
                ShopPlatformDtoOut.builder().code("tiktokshop").label("TikTok Shop").build(),
                ShopPlatformDtoOut.builder().code("ebay").label("eBay").build(),
                ShopPlatformDtoOut.builder().code("amazon").label("Amazon").build(),
                ShopPlatformDtoOut.builder().code("bigcommerce").label("BigCommerce").build(),
                ShopPlatformDtoOut.builder().code("wix").label("Wix").build(),
                ShopPlatformDtoOut.builder().code("squarespace").label("Squarespace").build(),
                ShopPlatformDtoOut.builder().code("magento").label("Magento").build(),
                ShopPlatformDtoOut.builder().code("lazada").label("Lazada").build(),
                ShopPlatformDtoOut.builder().code("shopee").label("Shopee").build());
    }

    private ShopConnectionEntity require(UUID userId, UUID id) {
        ShopConnectionEntity s = shopRepo.findById(id).orElseThrow(() -> new NotFoundException("Shop"));
        if (!s.getUser().getId().equals(userId)) throw new NotFoundException("Shop");
        return s;
    }
}
