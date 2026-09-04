package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * El país por IP que pone el CDN, y CUÁNDO puede creerse.
 *
 * <p>Existe porque esas cabeceras no son de fiar por sí solas. El servidor de origen responde 200 si se le
 * llama directamente con {@code --resolve} y el {@code Host} correcto, saltándose Cloudflare por completo
 * —comprobado el 27-ago-2026 y de nuevo el 4-sep—, así que {@code CF-IPCountry} es una cabecera como
 * cualquier otra: quien alcanza el origen se declara del país que le convenga.
 *
 * <p>Y no es cosmético. El país decide el margen y los costes de aduana, y en el alta social decide el país
 * que queda GRABADO en la ficha, que es justo la fuente en la que más se confía después.
 *
 * <p><b>Por qué un secreto y no la IP</b>: Traefik ya ha traducido a la IP real del comprador antes de que
 * la petición llegue aquí, así que una lista blanca con los rangos de Cloudflare responde 403 a todo el
 * mundo (probado). Los pull certificados con mTLS también se descartaron: Cloudflare devolvía 520. Lo que
 * sí funciona es que el CDN inyecte una cabecera con un secreto compartido y el origen la exija.
 *
 * <p>Sin secreto configurado se mantiene el comportamiento de siempre: en local y en cualquier despliegue
 * sin CDN delante no hay nada que demostrar, y exigirlo dejaría la geolocalización muerta en desarrollo.
 */
@Component
public class GeolocalizacionDelCdn {

    /** Cabecera con la que el CDN demuestra que la petición ha pasado por él. */
    public static final String HEADER_CDN = "X-Nexadrop-Edge";

    /** Cabeceras de país por IP que inyectan los CDN/proxys. Las pone la infraestructura, no el cliente. */
    private static final String[] GEO_HEADERS = { "CF-IPCountry", "X-Vercel-IP-Country", "X-Geo-Country",
            "X-Country-Code" };

    @Value("${nexadrop.security.cdn-shared-secret:}")
    private String secreto;

    /**
     * El país ISO-2 según el CDN, o {@code null} si no viene, si es desconocido o si la petición NO
     * demuestra haber pasado por el CDN.
     */
    public String paisDeConfianza(HttpServletRequest req) {
        if (!vieneDelCdn(req)) {
            return null;
        }
        for (String h : GEO_HEADERS) {
            String v = req.getHeader(h);
            // Algunos CDN mandan "XX" o "T1" (Tor) cuando no saben el país: se ignoran.
            if (v != null && v.length() == 2 && !"XX".equalsIgnoreCase(v) && !"T1".equalsIgnoreCase(v)) {
                return v;
            }
        }
        return null;
    }

    /**
     * ¿Ha pasado esta petición por el CDN?
     *
     * <p>La comparación es en tiempo constante: un {@code equals} corriente delata por su duración cuántos
     * caracteres del prefijo se han acertado, y aquí el atacante puede reintentar cuanto quiera.
     */
    public boolean vieneDelCdn(HttpServletRequest req) {
        if (secreto == null || secreto.isBlank()) {
            return true;
        }
        String presentado = req.getHeader(HEADER_CDN);
        if (presentado == null) {
            return false;
        }
        return MessageDigest.isEqual(presentado.getBytes(StandardCharsets.UTF_8),
                secreto.getBytes(StandardCharsets.UTF_8));
    }
}
