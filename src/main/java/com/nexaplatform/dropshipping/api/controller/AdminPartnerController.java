package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminPartnerApi;
import com.nexaplatform.dropshipping.api.dto.out.AdminOAuthClientDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerAppDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerWebhookDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminShopConnectionDtoOut;
import com.nexaplatform.dropshipping.application.service.PartnerAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin Partners controller. Pure implementation of {@link AdminPartnerApi}:
 * no business logic and no manual mapping — delegates to
 * {@link PartnerAdminService} and wraps every result in a standardized
 * {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/partners")
@RequiredArgsConstructor
public class AdminPartnerController implements AdminPartnerApi {

    private final PartnerAdminService partnerAdminService;

    @Override
    public ResponseEntity<List<AdminOAuthClientDtoOut>> oauthClients() {
        return new ResponseEntity<>(partnerAdminService.listOAuthClients(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminPartnerWebhookDtoOut>> webhooks() {
        return new ResponseEntity<>(partnerAdminService.listWebhooks(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminPartnerAppDtoOut>> partnerApps() {
        return new ResponseEntity<>(partnerAdminService.listPartnerApps(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminShopConnectionDtoOut>> shopConnections() {
        return new ResponseEntity<>(partnerAdminService.listShopConnections(), HttpStatus.OK);
    }
}
