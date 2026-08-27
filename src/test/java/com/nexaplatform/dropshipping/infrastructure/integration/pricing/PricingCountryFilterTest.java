package com.nexaplatform.dropshipping.infrastructure.integration.pricing;

import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * De dónde sale el país que decide el precio.
 *
 * <p>Qué se rompe si esto falla, medido el 26-ago-2026 sobre el catálogo real: el mismo producto y el
 * mismo usuario —con ES grabado en su ficha— salía a <b>13,72 EUR</b> con {@code X-Country: ES} y a
 * <b>12,07 EUR</b> con {@code X-Country: US}, sin arancel de 3 EUR y sin IVA del 21 %. La cabecera la
 * pone el navegador y cualquiera podía escribirla desde la consola: un 12 % de descuento a voluntad, más
 * saltarse el derecho de aduana de un pedido que igualmente se enviaba a Zaragoza.
 *
 * <p>La regla que fija esta clase: <b>a un usuario identificado se le cobra por su país de registro</b>,
 * que vive en el servidor. La cabecera solo vale para quien no ha iniciado sesión.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingCountryFilterTest {

    @Mock
    UserRepository userRepository;

    @InjectMocks
    PricingCountryFilter filter;

    private static final UUID USER_ID = UUID.randomUUID();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        PricingCountryHolder.clear();
    }

    /** Deja al usuario autenticado con el país que tenga grabado en su ficha. */
    private void usuarioAutenticadoCon(String pais) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null,
                        AuthorityUtils.createAuthorityList("ROLE_USER")));
        UserEntity u = new UserEntity();
        u.setId(USER_ID);
        u.setCountry(pais);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
    }

    /** Corre el filtro y devuelve el país que quedó fijado mientras duraba la petición. */
    private String paisResuelto(MockHttpServletRequest req) throws Exception {
        AtomicReference<String> visto = new AtomicReference<>();
        FilterChain chain = (a, b) -> visto.set(PricingCountryHolder.get());
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        return visto.get();
    }

    @Test
    void aUnUsuarioIdentificadoSeLeCobraPorSuPaisDeRegistro() throws Exception {
        usuarioAutenticadoCon("ES");
        MockHttpServletRequest req = new MockHttpServletRequest();
        // El navegador dice que está en Estados Unidos. No cuela.
        req.addHeader("X-Country", "US");

        assertThat(paisResuelto(req)).isEqualTo("ES");
    }

    /** Ni con la cabecera del CDN: la ficha del usuario manda sobre cualquier cosa que llegue por HTTP. */
    @Test
    void niLaCabeceraDelCdnPisaElPaisDelUsuario() throws Exception {
        usuarioAutenticadoCon("ES");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "US");

        assertThat(paisResuelto(req)).isEqualTo("ES");
    }

    /** Quien no ha iniciado sesión no tiene ficha: ahí la cabecera del CDN es la mejor pista que hay. */
    @Test
    void alInvitadoSeLeAplicaElPaisQueDiceElCdn() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "FR");

        assertThat(paisResuelto(req)).isEqualTo("FR");
        verify(userRepository, never()).findById(any());
    }

    /**
     * El CDN gana a lo que diga el navegador: la cabecera de geolocalización la pone la infraestructura y
     * el cliente no la puede falsear; X-Country sí.
     */
    @Test
    void paraElInvitadoElCdnGanaALaCabeceraDelNavegador() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Country", "US");
        req.addHeader("CF-IPCountry", "ES");

        assertThat(paisResuelto(req)).isEqualTo("ES");
    }

    /** Sin sesión y sin CDN —desarrollo local, o un CDN que no lo rellena— queda la cabecera del cliente. */
    @Test
    void sinSesionYSinCdnSeAceptaLaCabeceraDelCliente() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Country", "PT");

        assertThat(paisResuelto(req)).isEqualTo("PT");
    }

    /** Un usuario sin país en su ficha no bloquea nada: se sigue con las pistas de la petición. */
    @Test
    void siElUsuarioNoTienePaisSeCaeALasCabeceras() throws Exception {
        usuarioAutenticadoCon(null);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "IT");

        assertThat(paisResuelto(req)).isEqualTo("IT");
    }

    /** Algunos CDN mandan XX o T1 (Tor) cuando no saben el país: eso no es un país. */
    @Test
    void lasMarcasDeCdnQueNoSonUnPaisSeIgnoran() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "XX");
        req.addHeader("X-Vercel-IP-Country", "T1");

        assertThat(paisResuelto(req)).isNull();
    }

    /** El hilo vuelve al pool limpio: si no, la siguiente petición hereda el país de la anterior. */
    @Test
    void elPaisNoSeQuedaPegadoAlHiloAlTerminar() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "DE");

        paisResuelto(req);

        assertThat(PricingCountryHolder.get()).isNull();
    }

    /** Un token con un subject que no es un UUID no puede tumbar la petición entera. */
    @Test
    void unSubjectQueNoEsUnIdentificadorNoRevienta() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("no-es-un-uuid", null,
                        AuthorityUtils.createAuthorityList("ROLE_USER")));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "NL");

        assertThat(paisResuelto(req)).isEqualTo("NL");
    }
}
