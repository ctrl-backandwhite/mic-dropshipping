package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Siembra del cliente OAuth de partners y claims que se le meten al token.
 *
 * <p>Dos reglas de seguridad viven aquí: no se registra ningún cliente si no hay secreto configurado
 * (arrancar con una credencial vacía deja la API de partners abierta), y ante cualquier duda sobre el plan
 * se concede la cuota MÁS restrictiva —{@code sandbox}, 1 req/min—, nunca la de pago.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09RegisteredClientConfigTest {

    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    RegisteredClientRepository repo;
    @Mock
    CustomerSubscriptionRepository subsRepo;
    @Mock
    org.springframework.core.env.Environment environment;

    @InjectMocks
    private RegisteredClientConfig subject;

    @BeforeEach
    void buildSubject() {
        when(passwordEncoder.encode(any())).thenAnswer(i -> "enc(" + i.getArgument(0) + ")");
        secreto("s3cret0");
    }

    private void secreto(String valor) {
        try {
            Field f = RegisteredClientConfig.class.getDeclaredField("partnerSecret");
            f.setAccessible(true);
            f.set(subject, valor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<RegisteredClient> sembrados() {
        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(repo, times(2)).save(captor.capture());
        return captor.getAllValues();
    }

    // ---------------------------------------------------------------- siembra del cliente

    @Test
    void sinSecretoConfiguradoNoSeRegistraNingunClienteOauth() {
        // Registrarlo con secreto vacío deja la API de partners accesible con una credencial trivial:
        // es preferible que la aplicación no arranque.
        secreto("   ");

        assertThatThrownBy(() -> subject.seedClients(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partner-api.default-secret");

        verify(repo, never()).save(any());
    }

    @Test
    void elClienteDeSandboxSeSiembraConSecretoCifradoPermisosYCaducidadDeDoceHoras() {
        subject.seedClients(null);

        RegisteredClient sandbox = sembrados().get(0);
        assertThat(sandbox.getClientId()).isEqualTo("demo-partner");
        // El secreto NUNCA se guarda en claro.
        assertThat(sandbox.getClientSecret()).isEqualTo("enc(s3cret0)");
        assertThat(sandbox.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(sandbox.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(sandbox.getScopes()).containsExactlyInAnyOrder("catalog.read", "orders.write", "shop.sync");
        String plan = sandbox.getClientSettings().getSetting("nexadrop.plan");
        assertThat(plan).isEqualTo("sandbox");
        assertThat(sandbox.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void elClienteDePagoLlevaSuPropioSecretoYSuPropioPlan() {
        subject.seedClients(null);

        RegisteredClient pago = sembrados().get(1);
        assertThat(pago.getClientId()).isEqualTo("demo-partner-paid");
        assertThat(pago.getClientSecret()).isEqualTo("enc(s3cret0-paid)");
        String plan = pago.getClientSettings().getSetting("nexadrop.plan");
        assertThat(plan).isEqualTo("paid");
    }

    @Test
    void alReiniciarSeReutilizaElIdentificadorInternoDelClienteYaExistente() {
        // Guardarlo con un id nuevo dejaría huérfanas las autorizaciones ya emitidas contra el anterior.
        RegisteredClient existente = RegisteredClient.withId("id-de-siempre").clientId("demo-partner")
                .clientSecret("viejo").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("catalog.read").build();
        when(repo.findByClientId("demo-partner")).thenReturn(existente);

        subject.seedClients(null);

        assertThat(sembrados().get(0).getId()).isEqualTo("id-de-siempre");
    }

    @Test
    void unClienteNuevoRecibeUnIdentificadorInternoValido() {
        when(repo.findByClientId(any())).thenReturn(null);

        subject.seedClients(null);

        assertThat(UUID.fromString(sembrados().get(0).getId())).isNotNull();
    }

    // ---------------------------------------------------------------- claims del token del partner

    /** Ejecuta el customizer sobre un token del tipo indicado y devuelve los claims resultantes. */
    private JwtClaimsSet claims(ClientSettings settings, String tokenType) {
        OAuth2TokenCustomizer<JwtEncodingContext> customizer = subject.partnerPlanClaimCustomizer(subsRepo);
        RegisteredClient client = RegisteredClient.withId("cid").clientId("demo-partner")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("catalog.read")
                .clientSettings(settings).build();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder().subject("demo-partner");
        JwtEncodingContext context = JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), claimsBuilder)
                .registeredClient(client).tokenType(new OAuth2TokenType(tokenType)).build();
        customizer.customize(context);
        return claimsBuilder.build();
    }

    private static ClientSettings settings(String clave, String valor) {
        return valor == null ? ClientSettings.builder().build()
                : ClientSettings.builder().setting(clave, valor).build();
    }

    private static List<CustomerSubscriptionEntity> suscripcionCon(String codigoDePlan) {
        SubscriptionPlanEntity plan = mock(SubscriptionPlanEntity.class);
        when(plan.getCode()).thenReturn(codigoDePlan);
        CustomerSubscriptionEntity sub = mock(CustomerSubscriptionEntity.class);
        when(sub.getPlan()).thenReturn(plan);
        return List.of(sub);
    }

    @Test
    void unTokenQueNoEsDeAccesoNoRecibeElClaimDelPlan() {
        JwtClaimsSet out = claims(settings("nexadrop.plan", "paid"), "id_token");

        assertThat(out.getClaimAsString("plan")).isNull();
    }

    @Test
    void elPlanExplicitoDelClienteMandaSobreCualquierSuscripcion() {
        JwtClaimsSet out = claims(settings("nexadrop.plan", "paid"), "access_token");

        assertThat(out.getClaimAsString("plan")).isEqualTo("paid");
        assertThat(out.getClaimAsString("plan_code")).isEqualTo("OVERRIDE");
        verify(subsRepo, never()).findActiveByUserId(any());
    }

    @Test
    void sinPlanNiDuenoSeConcedeLaCuotaMasRestrictiva() {
        JwtClaimsSet out = claims(settings("nexadrop.plan", null), "access_token");

        assertThat(out.getClaimAsString("plan")).isEqualTo("sandbox");
        assertThat(out.getClaimAsString("owner_user_id")).isNull();
        assertThat(out.getClaimAsString("plan_code")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"STARTER,paid", "PRO,paid", "ENTERPRISE,paid", "pro,paid", "FREE,sandbox", "LEGACY,sandbox"})
    void elTierDeCuotaSeDeduceDelCodigoDePlanDelDueno(String codigoDePlan, String tierEsperado) {
        // Un código desconocido cae a sandbox: nunca se regala la cuota de pago por un plan que no se conoce.
        UUID dueno = UUID.randomUUID();
        // El doble se prepara ANTES de stubbar: montarlo dentro del thenReturn deja el stubbing a medias.
        List<CustomerSubscriptionEntity> activas = suscripcionCon(codigoDePlan);
        when(subsRepo.findActiveByUserId(dueno)).thenReturn(activas);

        JwtClaimsSet out = claims(settings("nexadrop.owner_user_id", dueno.toString()), "access_token");

        assertThat(out.getClaimAsString("plan")).isEqualTo(tierEsperado);
        assertThat(out.getClaimAsString("plan_code")).isEqualTo(codigoDePlan);
        assertThat(out.getClaimAsString("owner_user_id")).isEqualTo(dueno.toString());
    }

    @Test
    void unDuenoSinSuscripcionActivaSeQuedaEnSandboxPeroSigueIdentificado() {
        UUID dueno = UUID.randomUUID();
        when(subsRepo.findActiveByUserId(dueno)).thenReturn(List.of());

        JwtClaimsSet out = claims(settings("nexadrop.owner_user_id", dueno.toString()), "access_token");

        assertThat(out.getClaimAsString("plan")).isEqualTo("sandbox");
        assertThat(out.getClaimAsString("owner_user_id")).isEqualTo(dueno.toString());
        assertThat(out.getClaimAsString("plan_code")).isNull();
    }

    @Test
    void unIdentificadorDeDuenoMalformadoNoRompeLaEmisionDelToken() {
        // Un setting corrupto no puede dejar sin token a un partner: cae a sandbox y sigue.
        JwtClaimsSet out = claims(settings("nexadrop.owner_user_id", "no-es-un-uuid"), "access_token");

        assertThat(out.getClaimAsString("plan")).isEqualTo("sandbox");
        assertThat(out.getClaimAsString("owner_user_id")).isNull();
    }
}
