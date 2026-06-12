package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminPartnerApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminOAuthClientCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminOAuthClientCreatedDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOAuthClientDtoOut;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClientCreated;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerAppDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerWebhookDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminShopConnectionDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminPartnerMapper;
import com.nexaplatform.dropshipping.application.usecase.AdminPartnerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin Partners controller. Pure implementation of {@link AdminPartnerApi}:
 * no routing/documentation annotations here (they live on the interface), no
 * business logic and no manual mapping — maps domain -> DtoOut via the injected
 * mapper and delegates the aggregation to the use case.
 */
@RestController
@RequestMapping("/api/admin/partners")
@RequiredArgsConstructor
public class AdminPartnerController implements AdminPartnerApi {

    private final AdminPartnerMapper mapper;
    private final AdminPartnerUseCase useCase;

    @Override
    public ResponseEntity<List<AdminOAuthClientDtoOut>> oauthClients() {
        return new ResponseEntity<>(mapper.toOAuthClientDtoOutList(useCase.listOAuthClients()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminPartnerWebhookDtoOut>> webhooks() {
        return new ResponseEntity<>(mapper.toWebhookDtoOutList(useCase.listWebhooks()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminPartnerAppDtoOut>> partnerApps() {
        return new ResponseEntity<>(mapper.toPartnerAppDtoOutList(useCase.listPartnerApps()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminShopConnectionDtoOut>> shopConnections() {
        return new ResponseEntity<>(mapper.toShopConnectionDtoOutList(useCase.listShopConnections()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminOAuthClientCreatedDtoOut> createOAuthClient(AdminOAuthClientCreateDtoIn req) {
        return new ResponseEntity<>(toCreatedDtoOut(useCase.createOAuthClient(req.getName(), req.getScopes())),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<AdminOAuthClientCreatedDtoOut> rotateSecret(String clientId) {
        return ResponseEntity.ok(toCreatedDtoOut(useCase.rotateSecret(clientId)));
    }

    @Override
    public ResponseEntity<Void> deleteOAuthClient(String id) {
        useCase.deleteOAuthClient(id);
        return ResponseEntity.noContent().build();
    }

    private static AdminOAuthClientCreatedDtoOut toCreatedDtoOut(AdminOAuthClientCreated c) {
        return new AdminOAuthClientCreatedDtoOut(c.getId(), c.getClientId(), c.getClientSecret(), c.getName(),
                "Guarda el clientSecret ahora — no se volverá a mostrar.");
    }
}
