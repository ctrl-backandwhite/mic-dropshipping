package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.PartnerApiKeyCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyCreatedDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * API contract + OpenAPI documentation for the self-service partner API keys resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Me · API Keys", description = "Self-service partner OAuth credentials")
@SecurityRequirement(name = "session")
public interface PartnerApiKeysApi {

    @Operation(summary = "Create a new OAuth2 client for the authenticated user", description = """
            Generates fresh `client_id` and `client_secret` linked to the current user.
            The secret is shown ONCE — store it securely on your side.

            The JWT issued with these credentials will carry a `plan` claim that mirrors
            the user's active subscription (FREE→sandbox, STARTER/PRO/ENTERPRISE→paid).
            """)
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Credentials created"),
            @ApiResponse(responseCode = "400", description = "Invalid scopes or quota exceeded")})
    @PostMapping
    ResponseEntity<PartnerApiKeyCreatedDtoOut> create(Authentication auth,
            @Valid @RequestBody PartnerApiKeyCreateDtoIn req);

    @Operation(summary = "List API keys belonging to the authenticated user")
    @ApiResponse(responseCode = "200", description = "API keys listed")
    @GetMapping
    ResponseEntity<List<PartnerApiKeyDtoOut>> list(Authentication auth);

    @Operation(summary = "Revoke an API key (irreversible)")
    @DeleteMapping("/{clientId}")
    ResponseEntity<Void> revoke(Authentication auth, @PathVariable String clientId);
}
