package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Descodifica el ID token de un login social sin que el acceso dependa de que el proveedor conteste
 * deprisa.
 *
 * <p>Existe porque la fábrica que trae Spring ({@link OidcIdTokenDecoderFactory}) tiene dos costumbres que,
 * juntas, rompen el login con Google de forma intermitente:
 *
 * <ol>
 * <li><b>Construye un descodificador nuevo en cada intento</b> —no guarda ninguno—, y con él nace un
 * almacén de claves vacío. Resultado: las claves públicas del proveedor se descargan de Internet en
 * <b>cada</b> acceso, no una vez cada tanto.</li>
 * <li>Esa descarga se hace con los plazos que Nimbus trae de fábrica: <b>medio segundo</b> para conectar y
 * medio para leer. Con la latencia normal hasta Google (unos 120 ms de ida y vuelta, más el saludo TLS) el
 * margen se agota en cuanto la red o la máquina van cargadas.</li>
 * </ol>
 *
 * <p>Cuando el plazo se agota, la firma no se puede verificar y el acceso termina en
 * {@code invalid_id_token}: la persona ve «no se pudo completar el inicio de sesión con Google» sin motivo
 * aparente, y al reintentar a veces entra y a veces no.
 *
 * <p>Aquí se corrigen las dos cosas: el descodificador de cada proveedor se guarda y se reutiliza, y los
 * plazos suben a {@value #TIMEOUT_SECONDS} segundos. Nótese que <b>no</b> se le pasa una caché de Spring a
 * propósito: al dejarla sin poner, Spring activa la caché propia de Nimbus (cinco minutos), que además
 * vuelve a pedir las claves por su cuenta cuando llega un ID token firmado con una que no conoce —así la
 * rotación periódica de claves de Google sigue funcionando sola—.
 *
 * <p>Lo que <b>no</b> cambia es la validación: se aplican exactamente los mismos controles que pone Spring
 * (emisor, destinatario, caducidad y firma), construidos con las mismas clases suyas.
 */
public class ResilientOidcIdTokenDecoderFactory implements JwtDecoderFactory<ClientRegistration> {

    /** Plazo para conectar y para leer las claves públicas del proveedor. */
    public static final int TIMEOUT_SECONDS = 5;

    private static final String MISSING_SIGNATURE_VERIFIER_ERROR_CODE = "missing_signature_verifier";

    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ResilientOidcIdTokenDecoderFactory() {
        this(Duration.ofSeconds(TIMEOUT_SECONDS), Duration.ofSeconds(TIMEOUT_SECONDS));
    }

    public ResilientOidcIdTokenDecoderFactory(Duration connectTimeout, Duration readTimeout) {
        Assert.notNull(connectTimeout, "connectTimeout cannot be null");
        Assert.notNull(readTimeout, "readTimeout cannot be null");
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public JwtDecoder createDecoder(ClientRegistration clientRegistration) {
        Assert.notNull(clientRegistration, "clientRegistration cannot be null");
        return this.decoders.computeIfAbsent(clientRegistration.getRegistrationId(),
                registrationId -> buildDecoder(clientRegistration));
    }

    private JwtDecoder buildDecoder(ClientRegistration clientRegistration) {
        String jwkSetUri = clientRegistration.getProviderDetails().getJwkSetUri();
        if (!StringUtils.hasText(jwkSetUri)) {
            // Sin claves públicas no hay forma de verificar la firma. Se corta aquí en vez de aceptar el
            // token a ciegas, igual que hace la fábrica de Spring.
            OAuth2Error error = new OAuth2Error(MISSING_SIGNATURE_VERIFIER_ERROR_CODE,
                    "Failed to find a Signature Verifier for Client Registration: '"
                            + clientRegistration.getRegistrationId()
                            + "'. Check to ensure you have configured the JwkSet URI.",
                    null);
            throw new OAuth2AuthenticationException(error, error.toString());
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(restOperations())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(clientRegistration)));
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        return decoder;
    }

    private RestOperations restOperations() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(this.connectTimeout);
        requestFactory.setReadTimeout(this.readTimeout);
        return new RestTemplate(requestFactory);
    }
}
