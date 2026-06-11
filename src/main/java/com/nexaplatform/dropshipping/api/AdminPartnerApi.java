package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.out.AdminOAuthClientDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerAppDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminPartnerWebhookDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminShopConnectionDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * API contract + OpenAPI documentation for the Admin Partners resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Partners")
public interface AdminPartnerApi {

    @Operation(summary = "List OAuth2 registered clients")
    @ApiResponse(responseCode = "200", description = "Clients listed")
    @GetMapping("/oauth-clients")
    ResponseEntity<List<AdminOAuthClientDtoOut>> oauthClients();

    @Operation(summary = "List recent webhook deliveries")
    @ApiResponse(responseCode = "200", description = "Webhooks listed")
    @GetMapping("/webhooks")
    ResponseEntity<List<AdminPartnerWebhookDtoOut>> webhooks();

    @Operation(summary = "List partner apps")
    @ApiResponse(responseCode = "200", description = "Apps listed")
    @GetMapping("/apps")
    ResponseEntity<List<AdminPartnerAppDtoOut>> partnerApps();

    @Operation(summary = "List shop connections")
    @ApiResponse(responseCode = "200", description = "Connections listed")
    @GetMapping("/shop-connections")
    ResponseEntity<List<AdminShopConnectionDtoOut>> shopConnections();
}
