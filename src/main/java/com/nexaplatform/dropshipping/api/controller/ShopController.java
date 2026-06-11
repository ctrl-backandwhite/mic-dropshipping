package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.ShopApi;
import com.nexaplatform.dropshipping.api.dto.in.ShopConnectDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.ShopDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopInboundSecretDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopListingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopPlatformDtoOut;
import com.nexaplatform.dropshipping.application.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** DROP-5: Shop integrations (Shopify, Woo, TikTok, eBay, Amazon, BigCommerce, Wix, Shopee, Lazada…). */
@RestController
@RequestMapping("/api/me/shops")
@RequiredArgsConstructor
public class ShopController implements ShopApi {

    private final ShopService shopService;

    @Override
    public ResponseEntity<List<ShopDtoOut>> list(Authentication auth) {
        return ResponseEntity.ok(shopService.list(UUID.fromString(auth.getName())));
    }

    @Override
    public ResponseEntity<ShopDtoOut> connect(Authentication auth, ShopConnectDtoIn req) {
        ShopDtoOut result = shopService.connect(UUID.fromString(auth.getName()),
                req.getPlatform(), req.getShopHandle(), req.getAccessToken());
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<ShopDtoOut> sync(Authentication auth, UUID id) {
        return ResponseEntity.ok(shopService.sync(UUID.fromString(auth.getName()), id));
    }

    @Override
    public ResponseEntity<Void> disconnect(Authentication auth, UUID id) {
        shopService.disconnect(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ShopInboundSecretDtoOut> rotateInboundSecret(Authentication auth, UUID id) {
        return ResponseEntity.ok(shopService.rotateInboundSecret(UUID.fromString(auth.getName()), id));
    }

    @Override
    public ResponseEntity<ShopListingDtoOut> listProduct(Authentication auth, UUID id, UUID productId) {
        return ResponseEntity.ok(shopService.listProduct(UUID.fromString(auth.getName()), id, productId));
    }

    @Override
    public ResponseEntity<List<ShopListingDtoOut>> listings(Authentication auth, UUID id) {
        return ResponseEntity.ok(shopService.listings(UUID.fromString(auth.getName()), id));
    }

    @Override
    public ResponseEntity<List<ShopPlatformDtoOut>> platforms() {
        return ResponseEntity.ok(shopService.platforms());
    }
}
