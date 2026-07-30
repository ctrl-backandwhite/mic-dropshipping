package com.nexaplatform.dropshipping.infrastructure.campaign;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.HmacVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Baja de marketing por enlace de un clic, sin login.
 *
 * <p>El enlace del correo es la ÚNICA credencial: si el token se pudiera falsificar o manipular,
 * cualquiera podría dar de baja (o volver a suscribir) a otra persona desde su navegador. Por eso se
 * verifica la firma HMAC antes de tocar nada y cualquier token deforme se descarta en silencio.
 */
class Cov08MarketingUnsubscribeServiceTest {

    private static final String SECRETO = "secreto-de-pruebas";

    private UserRepository userRepository;
    private MarketingUnsubscribeService service;

    private final UUID userId = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        // HmacVerifier real: la firma es la garantía que se quiere fijar, no un detalle a simular.
        service = new MarketingUnsubscribeService(userRepository, new HmacVerifier(), SECRETO);
    }

    private UserEntity usuarioExistente(boolean optOutInicial) {
        UserEntity u = new UserEntity();
        u.setId(userId);
        u.setEmail("cliente@nx.local");
        u.setMarketingOptOut(optOutInicial);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        return u;
    }

    private static String base64(String texto) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void elEnlaceDelCorreoDaDeBajaAlUsuarioQueFirma() {
        UserEntity usuario = usuarioExistente(false);

        Optional<UserEntity> resultado = service.unsubscribe(service.tokenFor(userId));

        assertThat(resultado).containsSame(usuario);
        assertThat(usuario.isMarketingOptOut()).isTrue();
        verify(userRepository).save(usuario);
    }

    @Test
    void elMismoEnlaceSirveParaVolverARecibirCampanas() {
        UserEntity usuario = usuarioExistente(true);

        service.resubscribe(service.tokenFor(userId));

        assertThat(usuario.isMarketingOptOut()).isFalse();
        verify(userRepository).save(usuario);
    }

    @Test
    void unTokenConLaFirmaCambiadaNoDaDeBajaANadie() {
        // Es el ataque directo: coger el enlace propio y cambiar el identificador manteniendo la firma.
        String token = service.tokenFor(userId);
        String firma = token.substring(token.lastIndexOf('.') + 1);
        String manipulado = base64(UUID.randomUUID().toString()) + "." + firma;

        assertThat(service.unsubscribe(manipulado)).isEmpty();
        verify(userRepository, never()).save(any());
    }

    @Test
    void unTokenFirmadoConOtroSecretoNoVale() {
        MarketingUnsubscribeService otroEntorno = new MarketingUnsubscribeService(userRepository, new HmacVerifier(),
                "otro-secreto");
        String tokenAjeno = otroEntorno.tokenFor(userId);

        assertThat(service.unsubscribe(tokenAjeno)).isEmpty();
        verify(userRepository, never()).findById(any());
    }

    @Test
    void unTokenSinSeparadorSeDescarta() {
        assertThat(service.unsubscribe("token-sin-punto")).isEmpty();
    }

    @Test
    void unTokenAusenteSeDescarta() {
        assertThat(service.unsubscribe(null)).isEmpty();
    }

    @Test
    void unTokenConBase64InvalidoSeDescartaSinExcepcion() {
        assertThat(service.unsubscribe("!!no-es-base64!!.deadbeef")).isEmpty();
    }

    @Test
    void unTokenBienFirmadoPeroQueNoContieneUnIdentificadorSeDescarta() {
        // El contenido va firmado, pero un token antiguo (o un secreto filtrado) podría traer basura:
        // convertirla a UUID reventaría con un 500 en un enlace público.
        String contenido = "no-soy-un-uuid";
        String firma = new HmacVerifier().sign(SECRETO, contenido.getBytes(StandardCharsets.UTF_8));

        assertThat(service.unsubscribe(base64(contenido) + "." + firma)).isEmpty();
    }

    @Test
    void unTokenValidoDeUnUsuarioBorradoNoRompeElEnlace() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.unsubscribe(service.tokenFor(userId))).isEmpty();
        verify(userRepository, never()).save(any());
    }

    @Test
    void elTokenLlevaElIdentificadorCodificadoYSuFirma() {
        String token = service.tokenFor(userId);

        assertThat(token).contains(".");
        assertThat(token.substring(0, token.lastIndexOf('.'))).isEqualTo(base64(userId.toString()));
    }
}
