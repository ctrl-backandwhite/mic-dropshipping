package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.application.service.Texts;

/**
 * Construye la dirección a la que devolver a la persona usuaria al terminar un login social.
 *
 * <p>La web y la aplicación móvil no comparten forma de destino. La web tiene rutas —se vuelve a
 * {@code /auth/callback} en el éxito y a {@code /login} en el fallo, porque son pantallas distintas—.
 * La aplicación tiene un único enlace profundo y distingue el caso por los parámetros, ya que el sistema
 * operativo solo sabe abrirla por su esquema.
 *
 * <p>Los tokens viajan siempre en el fragmento ({@code #}), que no llega al servidor ni queda en los
 * registros de los proxys intermedios.
 */
public class OAuthRedirectResolver {

    private final String frontBaseUrl;
    private final String mobileCallbackUrl;

    public OAuthRedirectResolver(String frontBaseUrl, String mobileCallbackUrl) {
        this.frontBaseUrl = frontBaseUrl == null ? "" : Texts.stripTrailingSlashes(frontBaseUrl);
        this.mobileCallbackUrl = mobileCallbackUrl == null ? "" : Texts.stripTrailingSlashes(mobileCallbackUrl);
    }

    /**
     * Destino del login completado: par de tokens en el fragmento, con el testigo del flujo.
     *
     * <p>El {@code nonce} lo generó el cliente ANTES de salir hacia el proveedor y lo guardó en su
     * propia pestaña; aquí solo se le devuelve. Es lo que le permite distinguir «vengo de un flujo que
     * yo empecé» de «alguien me ha mandado un enlace con unos tokens dentro».
     *
     * <p>Sin esto, la única comprobación posible en el cliente era que los tokens TUVIERAN FORMA de JWT,
     * y eso no distingue basura de un JWT auténtico de otra cuenta: bastaba con publicar un enlace a
     * {@code /auth/callback#token=…} con los tokens del atacante para que la víctima acabara operando
     * dentro de su cuenta —con el refresco plantado, además, el secuestro sobrevivía a la caducidad—.
     *
     * <p>Si no hay testigo anotado no se inventa ninguno: el destino sale como antes y es el cliente
     * quien decide qué hacer con su ausencia.
     */
    public String success(OAuthClientTarget target, String accessToken, String refreshToken, String nonce) {
        String base = target == OAuthClientTarget.MOBILE ? mobileCallbackUrl : frontBaseUrl + "/auth/callback";
        String destino = base + "#token=" + accessToken + "&refresh=" + refreshToken;
        return nonce == null || nonce.isBlank() ? destino : destino + "&nonce=" + nonce;
    }

    /** Destino de un login rechazado, con el motivo para que el cliente lo explique. */
    public String error(OAuthClientTarget target, String errorCode) {
        return target == OAuthClientTarget.MOBILE
                ? mobileCallbackUrl + "?error=" + errorCode
                : frontBaseUrl + "/login?error=" + errorCode;
    }

    /**
     * Destino cuando el correo ya pertenece a una cuenta local sin vincular: hay que confirmar con la
     * contraseña antes de enlazar la identidad social.
     */
    public String linkRequired(OAuthClientTarget target) {
        return target == OAuthClientTarget.MOBILE
                ? mobileCallbackUrl + "?link=required"
                : frontBaseUrl + "/login?link=required";
    }
}
