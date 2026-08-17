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

    /** Destino del login completado: par de tokens en el fragmento. */
    public String success(OAuthClientTarget target, String accessToken, String refreshToken) {
        String base = target == OAuthClientTarget.MOBILE ? mobileCallbackUrl : frontBaseUrl + "/auth/callback";
        return base + "#token=" + accessToken + "&refresh=" + refreshToken;
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
