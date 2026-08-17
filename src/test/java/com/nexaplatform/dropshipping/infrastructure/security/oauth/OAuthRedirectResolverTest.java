package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Destinos del login social. Lo que se protege aquí es que el cliente NUNCA pueda dictar una dirección:
 * solo elige entre las que el servidor tiene configuradas.
 */
class OAuthRedirectResolverTest {

    private static final String FRONT = "https://app.example.com";
    private static final String MOBILE = "nx036://auth/callback";

    private final OAuthRedirectResolver resolver = new OAuthRedirectResolver(FRONT + "//", MOBILE);

    @Test
    void devuelveLosTokensAlFragmentoDeLaWeb() {
        String url = resolver.success(OAuthClientTarget.WEB, "acceso", "renovacion");

        assertThat(url).isEqualTo(FRONT + "/auth/callback#token=acceso&refresh=renovacion");
    }

    @Test
    void devuelveLosTokensAlEnlaceProfundoDeLaAplicacion() {
        String url = resolver.success(OAuthClientTarget.MOBILE, "acceso", "renovacion");

        assertThat(url).isEqualTo(MOBILE + "#token=acceso&refresh=renovacion");
    }

    @Test
    void losTokensViajanSiempreEnElFragmento() {
        // El fragmento no se envía al servidor ni queda en los registros de los proxys intermedios.
        assertThat(resolver.success(OAuthClientTarget.WEB, "a", "r")).contains("#token=");
        assertThat(resolver.success(OAuthClientTarget.MOBILE, "a", "r")).contains("#token=");
    }

    @Test
    void llevaElFalloALaPantallaDeAccesoEnLaWebYAlEnlaceProfundoEnLaAplicacion() {
        assertThat(resolver.error(OAuthClientTarget.WEB, "google_no_email"))
                .isEqualTo(FRONT + "/login?error=google_no_email");
        assertThat(resolver.error(OAuthClientTarget.MOBILE, "google_no_email"))
                .isEqualTo(MOBILE + "?error=google_no_email");
    }

    @Test
    void pideConfirmarLaVinculacionEnElDestinoDeCadaCliente() {
        assertThat(resolver.linkRequired(OAuthClientTarget.WEB)).isEqualTo(FRONT + "/login?link=required");
        assertThat(resolver.linkRequired(OAuthClientTarget.MOBILE)).isEqualTo(MOBILE + "?link=required");
    }

    @Test
    void unDestinoNoConfiguradoNuncaSaleDeLosConocidos() {
        // Ni siquiera con la configuración vacía se puede colar una dirección ajena: el resolutor solo
        // sabe componer las suyas.
        OAuthRedirectResolver sinConfigurar = new OAuthRedirectResolver(null, null);

        assertThat(sinConfigurar.success(OAuthClientTarget.MOBILE, "a", "r")).doesNotContain("http");
    }

    @Test
    void elIdentificadorDeClienteEsCerrado() {
        assertThat(OAuthClientTarget.from("mobile")).isEqualTo(OAuthClientTarget.MOBILE);
        assertThat(OAuthClientTarget.from("  MOBILE  ")).isEqualTo(OAuthClientTarget.MOBILE);
        assertThat(OAuthClientTarget.from("web")).isEqualTo(OAuthClientTarget.WEB);
    }

    @Test
    void cualquierValorDesconocidoCaeEnLaWeb() {
        // Es la defensa central: un intento de inyectar un destino propio no abre ninguna puerta, se
        // comporta como si no se hubiera pedido nada.
        assertThat(OAuthClientTarget.from("https://atacante.example")).isEqualTo(OAuthClientTarget.WEB);
        assertThat(OAuthClientTarget.from("javascript:alert(1)")).isEqualTo(OAuthClientTarget.WEB);
        assertThat(OAuthClientTarget.from("")).isEqualTo(OAuthClientTarget.WEB);
        assertThat(OAuthClientTarget.from(null)).isEqualTo(OAuthClientTarget.WEB);
    }

    @Test
    void sinSesionElClienteEsLaWeb() {
        assertThat(OAuthClientTargetFilter.resolve(new MockHttpServletRequest())).isEqualTo(OAuthClientTarget.WEB);
    }
}
