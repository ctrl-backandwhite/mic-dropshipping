package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.ShopApi;
import com.nexaplatform.dropshipping.api.dto.in.ShopConnectDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.ShopDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopInboundSecretDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopListingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopPlatformDtoOut;
import com.nexaplatform.dropshipping.api.mapper.ShopDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.ShopConnectionUseCase;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * DROP-5: Shop integrations (Shopify, Woo, TikTok, eBay, Amazon, BigCommerce,
 * Wix, Shopee, Lazada…). Pure implementation of {@link ShopApi}: injects the
 * {@link ShopDtoMapper} + {@link ShopConnectionUseCase}; maps DtoIn -> domain ->
 * DtoOut; no business logic, no manual mapping. Every call is scoped to the
 * authenticated user.
 */
@RestController
@RequestMapping("/api/me/shops")
@RequiredArgsConstructor
public class ShopController implements ShopApi {

    private final ShopDtoMapper mapper;
    private final ShopConnectionUseCase useCase;

    @Override
    public ResponseEntity<List<ShopDtoOut>> list(Authentication auth) {
        return ResponseEntity.ok(mapper.toDtoOutList(useCase.listByUser(UUID.fromString(auth.getName()))));
    }

    @Override
    public ResponseEntity<ShopDtoOut> connect(Authentication auth, ShopConnectDtoIn req) {
        ShopConnection model = useCase.connect(UUID.fromString(auth.getName()), mapper.toDomain(req));
        return ResponseEntity.ok(mapper.toDtoOut(model));
    }

    @Override
    public ResponseEntity<ShopDtoOut> sync(Authentication auth, UUID id) {
        ShopConnection model = useCase.sync(UUID.fromString(auth.getName()), id);
        return ResponseEntity.ok(mapper.toDtoOut(model));
    }

    @Override
    public ResponseEntity<Void> disconnect(Authentication auth, UUID id) {
        useCase.disconnect(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ShopInboundSecretDtoOut> rotateInboundSecret(Authentication auth, UUID id) {
        return ResponseEntity
                .ok(mapper.toInboundSecretDtoOut(useCase.rotateInboundSecret(UUID.fromString(auth.getName()), id)));
    }

    @Override
    public ResponseEntity<ShopListingDtoOut> listProduct(Authentication auth, UUID id, UUID productId) {
        return ResponseEntity
                .ok(mapper.toListingDtoOut(useCase.listProduct(UUID.fromString(auth.getName()), id, productId)));
    }

    @Override
    public ResponseEntity<List<ShopListingDtoOut>> listings(Authentication auth, UUID id) {
        return ResponseEntity.ok(mapper.toListingDtoOutList(useCase.listings(UUID.fromString(auth.getName()), id)));
    }

    @Override
    public ResponseEntity<List<ShopPlatformDtoOut>> platforms() {
        return ResponseEntity.ok(mapper.toPlatformDtoOutList(useCase.platforms()));
    }
}
