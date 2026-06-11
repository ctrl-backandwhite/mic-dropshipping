package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerApiKeysApi;
import com.nexaplatform.dropshipping.api.dto.in.PartnerApiKeyCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyCreatedDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyDtoOut;
import com.nexaplatform.dropshipping.api.mapper.PartnerApiKeyDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PartnerApiKeyUseCase;
import com.nexaplatform.dropshipping.domain.model.ApiKey;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service OAuth2 client_credentials credentials for the partner.
 *
 * Each logged-in user can:
 *   - POST /api/me/api-keys           → create a new client_id/secret (secret returned ONCE)
 *   - GET  /api/me/api-keys           → list theirs (without secrets)
 *   - DELETE /api/me/api-keys/{clientId} → revoke
 *
 * Pure implementation of {@link PartnerApiKeysApi}: no business logic and no
 * manual mapping. The current user is resolved here from the {@link Authentication}
 * and every operation is scoped to it; the controller only maps DtoIn -> domain ->
 * DtoOut via {@link PartnerApiKeyDtoMapper}, delegates to {@link PartnerApiKeyUseCase}
 * and wraps results in a standardized {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/me/api-keys")
@RequiredArgsConstructor
public class PartnerApiKeysController implements PartnerApiKeysApi {

    private final PartnerApiKeyDtoMapper mapper;
    private final PartnerApiKeyUseCase useCase;

    @Override
    public ResponseEntity<PartnerApiKeyCreatedDtoOut> create(Authentication auth, PartnerApiKeyCreateDtoIn req) {
        ApiKey created = useCase.create(currentUserId(auth), mapper.toDomain(req));
        return new ResponseEntity<>(mapper.toCreatedDtoOut(created), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<PartnerApiKeyDtoOut>> list(Authentication auth) {
        List<ApiKey> keys = useCase.list(currentUserId(auth));
        return new ResponseEntity<>(mapper.toDtoOutList(keys), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> revoke(Authentication auth, String clientId) {
        useCase.revoke(currentUserId(auth), clientId);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /** Resolves the authenticated user id; the name carries the user's UUID. */
    private UUID currentUserId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
