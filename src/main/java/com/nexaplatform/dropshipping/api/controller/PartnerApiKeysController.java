package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerApiKeysApi;
import com.nexaplatform.dropshipping.api.dto.in.PartnerApiKeyCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyCreatedDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyDtoOut;
import com.nexaplatform.dropshipping.application.service.PartnerApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Self-service OAuth2 client_credentials credentials for the partner.
 *
 * Each logged-in user can:
 *   - POST /api/me/api-keys           → create a new client_id/secret (secret returned ONCE)
 *   - GET  /api/me/api-keys           → list theirs (without secrets)
 *   - DELETE /api/me/api-keys/{clientId} → revoke
 *
 * Pure implementation of {@link PartnerApiKeysApi}: no business logic and no
 * manual mapping — the current user is resolved inside
 * {@link PartnerApiKeyService}; the controller only wraps results in a
 * standardized {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/me/api-keys")
@RequiredArgsConstructor
public class PartnerApiKeysController implements PartnerApiKeysApi {

    private final PartnerApiKeyService partnerApiKeyService;

    @Override
    public ResponseEntity<PartnerApiKeyCreatedDtoOut> create(Authentication auth, PartnerApiKeyCreateDtoIn req) {
        return new ResponseEntity<>(partnerApiKeyService.create(auth, req), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<PartnerApiKeyDtoOut>> list(Authentication auth) {
        return new ResponseEntity<>(partnerApiKeyService.list(auth), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> revoke(Authentication auth, String clientId) {
        partnerApiKeyService.revoke(auth, clientId);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
