package com.nexaplatform.dropshipping.config;

import com.nexaplatform.dropshipping.api.dto.ApiResponseDtoOut;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.1 specification for NexaDrop.
 *
 * Three groups are exposed at distinct endpoints under /v3/api-docs/{group}:
 *   - partner   → /api/v1/partner/**  (OAuth2 client_credentials, JWT bearer, scopes)
 *   - storefront→ /api/v1/rate-limits,/api/v1/invoices (public, anonymous)
 *   - admin     → /api/admin/** + /api/me/** (session cookie, ADMIN/OPERATOR)
 *
 * Vendor extensions added:
 *   - x-rate-limit: per-client and per-endpoint quotas, documented and surfaced in the spec
 *   - x-idempotency: marker for endpoints that honor the Idempotency-Key header
 */
@Configuration
public class OpenApiConfig {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String PER_CLIENT_ID = "per client_id";
    private static final String APPLIESTO = "appliesTo";
    private static final String PERMINUTE = "perMinute";
    private static final String PERDAY = "perDay";
    private static final String SCOPE = "scope";

    @Value("${nexadrop.oauth.issuer:http://localhost:8080}")
    private String issuer;

    @Value("${nexadrop.public-base-url:http://localhost:8080}")
    private String publicBase;

    @Bean
    public OpenAPI nexaDropOpenAPI() {
        OpenAPI api = buildOpenAPI();
        api.addExtension("x-rate-limit", Map.of("policies", List.of(
                Map.of(SCOPE, "catalog.read", PERMINUTE, 600, PERDAY, 50000, APPLIESTO, PER_CLIENT_ID),
                Map.of(SCOPE, "orders.write", PERMINUTE, 120, PERDAY, 10000, APPLIESTO, PER_CLIENT_ID),
                Map.of(SCOPE, "shop.sync", PERMINUTE, 60, PERDAY, 5000, APPLIESTO, PER_CLIENT_ID),
                Map.of(SCOPE, "storefront", PERMINUTE, 60, PERDAY, 5000, APPLIESTO, "per IP")), "headers",
                List.of("RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset", "Retry-After")));
        return api;
    }

    private OpenAPI buildOpenAPI() {
        OAuthFlow clientCredentials = new OAuthFlow().tokenUrl(issuer + "/oauth2/token")
                .scopes(new Scopes().addString("catalog.read", "Read products, categories, suppliers and pricing tiers")
                        .addString("orders.write", "Create and read partner orders, request shipping quotes").addString(
                                "shop.sync", "Push catalog listings and receive sync callbacks for connected shops"));

        SecurityScheme oauth2 = new SecurityScheme().type(SecurityScheme.Type.OAUTH2).description(
                "OAuth2 client_credentials. Use the /oauth2/token endpoint with your `client_id` and `client_secret` to obtain a JWT.")
                .flows(new OAuthFlows().clientCredentials(clientCredentials));

        SecurityScheme bearer = new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                .description("Raw JWT bearer (already minted via /oauth2/token). Used for /api/v1/partner/**.");

        SecurityScheme session = new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE)
                .name("SESSION")
                .description("HTTP session cookie issued by the web login. Used for /api/admin/** and /api/me/**.");

        SecurityScheme webhookHmac = new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                .name("X-NX-Signature").description(
                        "HMAC-SHA256 of the raw request body, hex-encoded, signed with the shop connection secret. Verified on inbound order webhooks.");

        return new OpenAPI()
                .info(new Info().title("NexaDrop Platform API").version("1.1.0")
                        .description(
                                """
                                        Public + partner + admin API for the NexaDrop dropshipping platform.

                                        **Authentication models**
                                        - `oauth2` — partner integrations (client_credentials → JWT bearer)
                                        - `session` — admin/operator/customer web UI
                                        - `webhookHmac` — inbound webhooks (shop → NexaDrop) signed with the connection secret

                                        **Rate limits** (per `client_id` for partner, per IP for storefront/anonymous):
                                        - `catalog.read`  → 600 req/min, 50 000 req/day
                                        - `orders.write`  → 120 req/min, 10 000 req/day
                                        - `shop.sync`     → 60 req/min, 5 000 req/day
                                        - storefront/anon → 60 req/min per IP

                                        Every response includes `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` (RFC 9239 style).

                                        **Idempotency** — write endpoints (`POST` orders, listings, sync) honor the `Idempotency-Key` header for up to 24 h.
                                        """)
                        .contact(new Contact().name("NexaDrop API").email("api@nexadrop.io")
                                .url("https://nexadrop.io/developers"))
                        .license(new License().name("Proprietary").url("https://nexadrop.io/terms")))
                .servers(List.of(new Server().url(publicBase).description("Current environment"),
                        new Server().url("https://api.nexadrop.io").description("Production"),
                        new Server().url("https://staging.api.nexadrop.io").description("Staging")))
                .tags(List.of(new Tag().name("Auth").description("OAuth2 token issuance and OIDC discovery"),
                        new Tag().name("Partner · Catalog").description("Read products available for resale"),
                        new Tag().name("Partner · Orders").description("Create and track partner orders"),
                        new Tag().name("Partner · Shop").description("Connected shops, listings, inbound webhooks"),
                        new Tag().name("Storefront").description("Anonymous storefront catalog API"),
                        new Tag().name("Admin · Partners")
                                .description("Manage OAuth clients, webhooks and apps (session)"),
                        new Tag().name("Admin · Webhooks").description("Manage outbound webhook subscriptions"),
                        new Tag().name("Admin · Orders").description("Internal order management"),
                        new Tag().name("Admin · Catalog").description("Internal catalog management"),
                        new Tag().name("Admin · Billing").description("Plans, subscriptions, invoices"),
                        new Tag().name("Me").description("Authenticated user resources (wallet, orders, profile)")))
                .components(
                        new Components().addSecuritySchemes("oauth2", oauth2).addSecuritySchemes("bearer-jwt", bearer)
                                .addSecuritySchemes("session", session).addSecuritySchemes("webhookHmac", webhookHmac));
    }

    /** Partner-only group: requires OAuth2 by default. */
    @Bean
    public GroupedOpenApi partnerApi() {
        return GroupedOpenApi.builder().group("partner")
                .pathsToMatch("/api/v1/partner/**", "/oauth2/**", "/.well-known/**")
                .addOpenApiCustomizer(applyDefaultSecurity("oauth2", "bearer-jwt"))
                .addOpenApiCustomizer(globalResponses()).build();
    }

    /** Public storefront group: no authentication. */
    @Bean
    public GroupedOpenApi storefrontApi() {
        return GroupedOpenApi.builder().group("storefront").pathsToMatch("/api/v1/rate-limits/**", "/api/v1/invoices/**")
                .addOpenApiCustomizer(globalResponses()).build();
    }

    /** Admin / self-service group: cookie session. */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin")
                .pathsToMatch("/api/admin/**", "/api/me/**", "/api/auth/**", "/api/currencies/**")
                .addOpenApiCustomizer(applyDefaultSecurity("session")).addOpenApiCustomizer(globalResponses()).build();
    }

    /**
     * Documents the standard error responses (400/401/403/404/409/422/429/500) on
     * every operation, all referencing the canonical {@link ApiResponseDtoOut}
     * error envelope produced by the GlobalExceptionHandler.
     */
    private OpenApiCustomizer globalResponses() {
        return openApi -> {
            ResolvedSchema resolved = ModelConverters.getInstance()
                    .resolveAsResolvedSchema(new AnnotatedType(ApiResponseDtoOut.class));
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            resolved.referencedSchemas.forEach(openApi.getComponents()::addSchemas);

            Content errorContent = new Content().addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponseDtoOut")));

            String[][] standard = {{"400", "Bad request — validation or malformed input"},
                    {"401", "Unauthorized — authentication required or invalid"},
                    {"403", "Forbidden — insufficient permissions"}, {"404", "Not found — the resource does not exist"},
                    {"409", "Conflict — domain/state or uniqueness conflict"},
                    {"422", "Unprocessable entity — business rule violated"},
                    {"429", "Too many requests — rate limit exceeded"}, {"500", "Internal server error"}};

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
                ApiResponses responses = op.getResponses() != null ? op.getResponses() : new ApiResponses();
                for (String[] entry : standard) {
                    if (!responses.containsKey(entry[0])) {
                        responses.addApiResponse(entry[0],
                                new ApiResponse().description(entry[1]).content(errorContent));
                    }
                }
                op.setResponses(responses);
            }));
        };
    }

    /** Adds the named security requirements as the default for every operation in the group. */
    private OpenApiCustomizer applyDefaultSecurity(String... schemeNames) {
        return openApi -> {
            SecurityRequirement requirement = new SecurityRequirement();
            for (String name : schemeNames)
                requirement.addList(name);
            if (openApi.getPaths() == null)
                return;
            openApi.getPaths().values().forEach(path -> path.readOperationsMap().forEach((method, op) -> {
                if (op.getSecurity() == null || op.getSecurity().isEmpty())
                    op.addSecurityItem(requirement);
            }));
            // Force the method type access so the lambda compiles cleanly under -Werror.
            for (PathItem ignored : openApi.getPaths().values()) {
            }
        };
    }
}
