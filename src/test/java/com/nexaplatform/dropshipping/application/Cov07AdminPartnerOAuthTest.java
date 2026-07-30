package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.AdminPartnerUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClient;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClientCreated;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerApp;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerWebhook;
import com.nexaplatform.dropshipping.domain.model.AdminShopConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alta/rotación/baja de clientes OAuth de partner y traducción de cada fila SQL a su proyección.
 * Los mapeadores de fila se capturan y se aplican a un {@link ResultSet} doblado, que es donde vive
 * la regla de los nulos (un entero SQL nulo no puede leerse como 0).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07AdminPartnerOAuthTest {

    @Mock
    JdbcTemplate jdbc;
    @Mock
    RegisteredClientRepository registeredClientRepository;
    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    AdminPartnerUseCaseImpl useCase;

    /* ==================== alta de cliente OAuth ==================== */

    @Test
    void unClienteOAuthSinNombreNoSeCrea() {
        assertThatThrownBy(() -> useCase.createOAuthClient("  ", List.of("catalog.read"), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> useCase.createOAuthClient(null, List.of("catalog.read"), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        verify(registeredClientRepository, never()).save(any());
    }

    @Test
    void sinScopesElClienteNaceSoloConLecturaDeCatalogo() {
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        useCase.createOAuthClient("Tienda Ana", null, UUID.randomUUID());

        assertThat(savedClient().getScopes()).containsExactly("catalog.read");
    }

    @Test
    void losScopesConDosPuntosSeNormalizanAPunto() {
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        useCase.createOAuthClient("Tienda Ana", List.of("catalog:read", "orders.write"), UUID.randomUUID());

        // El ResourceServer concede SCOPE_catalog.read: con ':' el authority no casaría y daría 403.
        assertThat(savedClient().getScopes()).containsExactlyInAnyOrder("catalog.read", "orders.write");
    }

    @Test
    void elClienteSeCreaConCredencialesDeClienteYSecretoHasheado() {
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");

        AdminOAuthClientCreated created = useCase.createOAuthClient("Tienda Ana", List.of("catalog.read"),
                UUID.randomUUID());

        RegisteredClient client = savedClient();
        assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(client.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(client.getClientSecret()).isEqualTo("HASH");
        // El secreto en claro SOLO se devuelve aquí (nunca se persiste): es la única vez que se puede copiar.
        assertThat(created.getClientSecret()).startsWith("sk_").isNotEqualTo("HASH");
        assertThat(created.getClientId()).startsWith("partner_").isEqualTo(client.getClientId());
    }

    @Test
    void elAltaEnlazaUnaFilaPartnerAppConElUuidDeterministaDelClientId() {
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        UUID owner = UUID.randomUUID();

        AdminOAuthClientCreated created = useCase.createOAuthClient("Tienda Ana", List.of("catalog.read"), owner);

        // El partner API resuelve el partner como este UUID; sin la fila, POST /partner/orders rompe por FK.
        UUID expected = UUID.nameUUIDFromBytes(("partner:" + created.getClientId()).getBytes());
        verify(jdbc).update(contains("INSERT INTO partner_app"), eq(expected), eq(owner), eq("Tienda Ana"),
                eq("OAuth API client"), eq(created.getClientId()), eq("HASH"), eq("catalog.read"));
    }

    /* ==================== rotación y baja ==================== */

    @Test
    void rotarElSecretoDeUnClienteInexistenteFalla() {
        when(registeredClientRepository.findByClientId("partner_x")).thenReturn(null);

        assertThatThrownBy(() -> useCase.rotateSecret("partner_x")).isInstanceOf(NotFoundException.class);
        verify(registeredClientRepository, never()).save(any());
    }

    @Test
    void rotarElSecretoGuardaElNuevoHashYDevuelveElSecretoEnClaroUnaSolaVez() {
        RegisteredClient existing = RegisteredClient.withId("id-1").clientId("partner_x").clientName("Tienda Ana")
                .clientSecret("HASH_VIEJO")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("catalog.read").build();
        when(registeredClientRepository.findByClientId("partner_x")).thenReturn(existing);
        when(passwordEncoder.encode(anyString())).thenReturn("HASH_NUEVO");

        AdminOAuthClientCreated rotated = useCase.rotateSecret("partner_x");

        assertThat(rotated.getClientSecret()).startsWith("sk_");
        assertThat(rotated.getId()).isEqualTo("id-1");
        assertThat(rotated.getName()).isEqualTo("Tienda Ana");
        assertThat(savedClient().getClientSecret()).isEqualTo("HASH_NUEVO");
        // La rotación no puede cambiar el clientId: las integraciones del partner dejarían de autenticarse.
        assertThat(savedClient().getClientId()).isEqualTo("partner_x");
    }

    @Test
    void borrarUnClienteQueNoExisteFalla() {
        when(jdbc.update(contains("DELETE FROM oauth2_registered_client"), eq("x"), eq("x"))).thenReturn(0);

        assertThatThrownBy(() -> useCase.deleteOAuthClient("x")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void borrarAceptaTantoElIdInternoComoElClientId() {
        when(jdbc.update(anyString(), eq("partner_x"), eq("partner_x"))).thenReturn(1);

        useCase.deleteOAuthClient("partner_x");

        verify(jdbc).update(contains("id = ? OR client_id = ?"), eq("partner_x"), eq("partner_x"));
    }

    /* ==================== mapeo de filas ==================== */

    @Test
    void unaEntregaDeWebhookSinIntentosNiRespuestaSeMapeaANulos() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("attempt_count")).thenReturn(0);
        when(rs.getInt("response_code")).thenReturn(0);
        // wasNull() se consulta tras CADA lectura: primero el intento, luego el código de respuesta.
        when(rs.wasNull()).thenReturn(true, true);
        when(rs.getTimestamp("created_at")).thenReturn(null);
        when(rs.getString("event_type")).thenReturn("ORDER_CREATED");

        AdminPartnerWebhook row = rowMapper("partner_webhook_delivery", AdminPartnerWebhook.class).mapRow(rs, 0);

        // Un 0 significaría "cero intentos" cuando en realidad no hay dato: hay que devolver null.
        assertThat(row.getAttemptCount()).isNull();
        assertThat(row.getResponseCode()).isNull();
        assertThat(row.getCreatedAt()).isNull();
        assertThat(row.getEventType()).isEqualTo("ORDER_CREATED");
    }

    @Test
    void unaEntregaDeWebhookConDatosMantieneSusValores() throws SQLException {
        Instant created = Instant.parse("2026-07-01T10:00:00Z");
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        when(rs.getObject("id")).thenReturn(id);
        when(rs.getObject("partner_app_id")).thenReturn(id);
        when(rs.getInt("attempt_count")).thenReturn(3);
        when(rs.getInt("response_code")).thenReturn(200);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(created));
        when(rs.getString("status")).thenReturn("DELIVERED");

        AdminPartnerWebhook row = rowMapper("partner_webhook_delivery", AdminPartnerWebhook.class).mapRow(rs, 0);

        assertThat(row.getAttemptCount()).isEqualTo(3);
        assertThat(row.getResponseCode()).isEqualTo(200);
        assertThat(row.getCreatedAt()).isEqualTo(created);
        assertThat(row.getStatus()).isEqualTo("DELIVERED");
        assertThat(row.getId()).isEqualTo(id);
    }

    @Test
    void unaAppDePartnerSinColumnaActivaSeMapeaANulo() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBoolean("active")).thenReturn(false);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getString("name")).thenReturn("Demo Partner");
        when(rs.getString("webhook_url")).thenReturn("https://demo/hook");

        AdminPartnerApp row = rowMapper("FROM partner_app", AdminPartnerApp.class).mapRow(rs, 0);

        assertThat(row.getActive()).isNull();
        assertThat(row.getName()).isEqualTo("Demo Partner");
        assertThat(row.getWebhookUrl()).isEqualTo("https://demo/hook");
    }

    @Test
    void unaAppDePartnerActivaSeMapeaComoActiva() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBoolean("active")).thenReturn(true);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));

        AdminPartnerApp row = rowMapper("FROM partner_app", AdminPartnerApp.class).mapRow(rs, 0);

        assertThat(row.getActive()).isTrue();
        assertThat(row.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void unaConexionDeTiendaSeMapeaConSuPlataformaYHandle() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("platform")).thenReturn("shopify");
        when(rs.getString("shop_handle")).thenReturn("mi-tienda.myshopify.com");
        when(rs.getBoolean("active")).thenReturn(true);
        when(rs.wasNull()).thenReturn(false);

        AdminShopConnection row = rowMapper("shop_connection", AdminShopConnection.class).mapRow(rs, 0);

        assertThat(row.getPlatform()).isEqualTo("shopify");
        assertThat(row.getShopHandle()).isEqualTo("mi-tienda.myshopify.com");
        assertThat(row.getActive()).isTrue();
    }

    @Test
    void unClienteOAuthSeMapeaConSusMetodosYScopes() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("id-1");
        when(rs.getString("client_id")).thenReturn("partner_x");
        when(rs.getString("client_name")).thenReturn("Tienda Ana");
        when(rs.getString("auth_methods")).thenReturn("client_secret_basic");
        when(rs.getString("grant_types")).thenReturn("client_credentials");
        when(rs.getString("scopes")).thenReturn("catalog.read,orders.write");

        AdminOAuthClient row = rowMapper("oauth2_registered_client", AdminOAuthClient.class).mapRow(rs, 0);

        assertThat(row.getClientId()).isEqualTo("partner_x");
        assertThat(row.getAuthMethods()).isEqualTo("client_secret_basic");
        assertThat(row.getGrantTypes()).isEqualTo("client_credentials");
        assertThat(row.getScopes()).isEqualTo("catalog.read,orders.write");
    }

    /* ==================== helpers ==================== */

    /** Ejecuta el listado que consulta {@code sqlFragment} y devuelve el mapeador de fila que usó. */
    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> rowMapper(String sqlFragment, Class<T> type) {
        if (type == AdminPartnerWebhook.class) {
            useCase.listWebhooks();
        } else if (type == AdminPartnerApp.class) {
            useCase.listPartnerApps();
        } else if (type == AdminShopConnection.class) {
            useCase.listShopConnections();
        } else {
            useCase.listOAuthClients();
        }
        ArgumentCaptor<RowMapper<T>> captor = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc).query(contains(sqlFragment), captor.capture());
        return captor.getValue();
    }

    private RegisteredClient savedClient() {
        ArgumentCaptor<RegisteredClient> captor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(registeredClientRepository).save(captor.capture());
        return captor.getValue();
    }
}
