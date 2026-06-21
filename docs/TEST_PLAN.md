# Plan de cobertura de tests — patrón `mic-authservice`, repo sin core

> Objetivo: replicar el patrón de testing de `mic-authservice` (unit + integración con
> Testcontainers + JWT por roles) en este repo, que **no tiene core** (todo el soporte se crea local).
> Estado actual: **45 archivos de test, 222 tests, 0 de integración**. Testcontainers está en el `pom`
> pero **sin usar**; `maven-failsafe` **no configurado**; `application-test.yml` usa **H2** (no Postgres real).

## Patrón a seguir (de `mic-authservice`)
- **Unit** (`XxxTest`, package-private): JUnit5 + `@ExtendWith(MockitoExtension)` + `@Mock`/`@InjectMocks` + AssertJ. Fixtures en `provider/XxxProvider` (factorías estáticas) + `provider/AuditProvider`. Mappers anidados inyectados con `util/MapperTestUtils.setField` (reflexión). Mappers: `usingRecursiveComparison().ignoringFields(auditoría)`; los *UpdateMapper* se verifican **campo a campo** (qué se preserva vs qué se copia).
- **Integración** (`XxxControllerIT`, sufijo `IT`): `BaseIntegration` + `TestContainersConfiguration` + `JwtTestUtil`, `WebTestClient`, Postgres real (`@ServiceConnection`), limpieza de tablas en `@BeforeEach`. Bloque `@Nested Security` con permitido (2xx) / prohibido (403) / sin token (401).

---

## FASE 0 — Infraestructura de test (habilitar el patrón, SIN core)
Crear en `src/test/java/com/nexaplatform/dropshipping/`:
- **`config/TestContainersConfiguration`**: `@TestConfiguration` con `@Bean @ServiceConnection PostgreSQLContainer` (`postgres:16`), + JWT de test: `JWKSource` RSA en memoria, `JwtEncoder`/`JwtDecoder`, `SecurityFilterChain` de test que mapea claim `authorities`→`ROLE_*` y exige `typ=access`, + bean `JwtTestUtil`.
- **`config/JwtTestUtil`**: genera JWT firmado con claims reales del sistema (`typ=access`, `authorities=[ROLE_*]`, `iss`=issuer, `sub` válido para `JwtRevocationService`). `getToken(roles)`, `getToken(roles,email)`, y variante con `scope` (`SCOPE_catalog.read/orders.write/shop.sync`) para Partner API.
- **`config/BaseIntegration`**: `@SpringBootTest(RANDOM_PORT)` + `@ActiveProfiles("test")` + `@Testcontainers` + `@Import(TestContainersConfiguration)`; `WebTestClient` + `JdbcTemplate`; `cleanAllTables()` (TRUNCATE … RESTART IDENTITY CASCADE de tablas `public` salvo liquibase/hibernate).
- **`util/MapperTestUtils`** (`setField` por reflexión) + **`provider/*Provider`** (fixtures por agregado) + **`provider/AuditProvider`**.
- **`src/test/resources/application-test.yml`**: cambiar H2 → Postgres (Liquibase **ON** para validar migraciones), `test.postgres.{username,password,database}`.
- **`pom.xml`**: añadir `maven-failsafe-plugin` (`**/*IT.java` en `integration-test`/`verify`). Deps Testcontainers ya presentes.

---

## FASE 1 — Unit tests, capa aplicación (31 clases sin test)
Orden por criticidad (dinero/seguridad primero):

**Seguridad/Auth/2FA**: `AuthUseCaseImplTest` (normalización email, 401 genérico anti-enumeración, rotación refresh, link Google, changePassword verifica password vieja), `TotpServiceTest` (RFC6238 ±1 drift, AES-GCM, Base32, backup codes single-use), `TotpUseCaseImplTest`, `DeviceSessionServiceTest`, `UserUpdateMapperTest` (NUNCA toca email/passwordHash/role/active/totp), `WebhookSubscriptionUpdateMapperTest` (no toca `secret`).

**Dinero**: `MarginServiceTest` (rangos coste, PERCENTAGE/FIXED, jerarquía scope VARIANT>…>GLOBAL, cache 5min, canal STOREFRONT/INTEGRATION), `InvoiceServiceTest` (conversión por línea USD→moneda, redondeo, i18n), `CountryTaxServiceTest` (BPS→cents HALF_UP), `OperatorCommissionServiceTest` (idempotencia, % por source, quita IVA 13%, redondeo), `OperatorEarningsServiceTest`, `PriceRuleUpdateMapperTest`/`PaymentUpdateMapperTest`/`WalletUpdateMapperTest`.

**Pedidos/fulfillment**: `FulfillmentServiceTest` (guards creación, dedup eventos, mapeo Cainiao→OrderStatus, parseo push), `ShippingQuoteServiceTest`, `WarehouseUseCaseImplTest`, `OrderUpdateMapperTest`, `OrderEmailServiceTest`.

**Integraciones/partners**: `WebhookDispatcherServiceTest` (firma HMAC, backoff `[60s,5m,30m,2h,8h]`, MAX_ATTEMPTS=5), `PartnerWebhookDispatcherServiceTest`, `PartnerPlanSyncServiceTest`.

**Resto**: `WinningProductUseCaseImplTest`, `NewsletterServiceTest`, `TranslationServiceTest`, `PricingChannelHolderTest`, + UpdateMappers restantes (`Product/Category/CustomerSubscription/SubscriptionPlan/UserAddress`).

---

## FASE 2 — Unit tests, infraestructura
**Cripto (rápidos, alto riesgo)**: `HmacVerifierTest`, `TokenCryptoServiceTest` (encrypt/decrypt round-trip, multi-KEK, legacy vs moderno), `CainiaoLinkClientTest` (firma `Base64(MD5(payload+secret))` + verify), `UserTokenServiceTest` (issue+rotación), `JwkKeyServiceTest` (rotación).
**Pagos/Stripe**: `StripeServiceTest` (`MockedStatic` de Customer/SetupIntent/PaymentMethod/Subscription/Webhook), `StripeGatewayTest`, `PayPalGatewayTest`.
**Mirror/búsqueda/moneda**: `ImageMirrorServiceTest` (flujo `mirrorOne`/auto-heal/variantes — hoy solo está el guard SSRF+magic bytes), `ProductIndexerTest`/`CategoryIndexerTest`/`CategorySearchServiceTest` (mock client), `CurrencyRateServiceTest` (conversión + `formatDisplay` backend-only).
**Filtros seguridad**: `JwtRevocationFilterTest`, `ServerToServerApiFilterTest`, `DeviceSessionRevocationFilterTest`, `GoogleOAuth2SuccessHandlerTest`, `UserAgentBlockingFilterTest`.
**Entity mappers** (MapStruct Model↔Entity, unit `Mappers.getMapper`): `ProductEntityMapper`, `OrderEntityMapper`, `UserEntityMapper`, `PaymentEntityMapper`, `CustomerSubscriptionEntityMapper`, `CategoryEntityMapper` (los de más lógica) + round-trip del resto.

---

## FASE 3 — Integración de PERSISTENCIA (Testcontainers Postgres, `@DataJpaTest`)
La mayor deuda: el JPQL/`EXISTS`/`@Modifying` falla en silencio.
- `ProductRepositoryIT`: `findVisibleByStatus` (solo con ≥1 imagen cdn), `searchStorefront` (needle+filtros+paginación), `findTopByTrendScore`, `findByCategoryOrderByTrend`, `findWithDetailsBySlug` (fetch joins).
- `ProductImageRepositoryIT` / `ProductVariantRepositoryIT` / `VariantValueRepositoryIT`: `findNeedingImageMirror`, `markImageCdn`/`markImageFailed`/`requeueNotMirrored` (`@Modifying`).
- `OperatorOrderActionRepositoryIT`, `CustomerSubscriptionRepositoryIT`, `PaymentJpaRepositoryAdapterIT`, `CategoryRepositoryIT` (jerarquía) y demás `@Query` custom.

---

## FASE 4 — Integración ENDPOINT × ROL (Testcontainers + JWT por rol, `WebTestClient`)
Roles: **USER, PARTNER, OPERATOR, ADMIN** (+ scopes Partner: `catalog.read`, `orders.write`, `shop.sync`). Autorización por **path** (3 filter chains). Por cada grupo: un PERMITIDO (2xx) + un PROHIBIDO (403/401):

| Grupo | Endpoint muestra | Permitido | Prohibido |
|---|---|---|---|
| auth público | POST /api/auth/login | anónimo (200) | — |
| storefront GET | GET /api/storefront/catalog/products | anónimo | — |
| storefront margin (ADMIN) | GET /api/storefront/catalog/products/{id}/margin-estimate | ADMIN | USER→403, anónimo→401 |
| me/** | GET /api/me/orders | USER | anónimo→401 |
| admin+operator | POST /api/admin/orders/{id}/ship | ADMIN u OPERATOR | USER→403 |
| admin operator (singular) | GET /api/admin/operator/earnings | OPERATOR/ADMIN | USER→403 |
| admin general | GET /api/admin/dashboard/metrics | ADMIN | OPERATOR→403 |
| partner scope | GET /api/v1/partner/catalog/products | JWT SCOPE_catalog.read | sin scope→403, sin bearer→401 |
| partner orders | POST /api/v1/partner/orders | JWT SCOPE_orders.write | solo catalog.read→403 |
| webhooks | POST /api/webhooks/stripe | firma válida | firma inválida→4xx |
| swagger | GET /swagger-ui/index.html | ADMIN/OPERATOR | USER→403 |

**Casos límite**: `typ≠access` (refresh como bearer)→401; token revocado→401; `/api/admin/operators` (plural, solo ADMIN) vs `/api/admin/operator` (singular, ADMIN+OPERATOR); cross-token (partner-scope contra /api/admin/** y viceversa).

---

## Resumen de magnitud
- **Fase 0**: ~6 clases de soporte + pom + yml (habilita todo lo demás).
- **Fase 1**: 31 clases de aplicación sin test.
- **Fase 2**: ~25 clases de infraestructura (cripto, pagos, mirror, mappers entity, filtros).
- **Fase 3**: ~17 repos con `@Query` custom (IT Postgres).
- **Fase 4**: ~11 grupos de endpoints × (permitido/prohibido) + 4 casos límite.

Orden recomendado: **Fase 0 → 1 (dinero/seguridad) → 3 (repos) → 4 (endpoints/roles) → 2 (resto infra)**.
Cada lote termina con `mvn -o test` (unit) / `mvn -o verify` (IT) en verde + JaCoCo sin bajar cobertura.
