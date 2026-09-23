package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validación del ID token de un login social sin depender de que el proveedor responda deprisa.
 *
 * <p>Por qué existe esta clase: el descodificador que arma Spring por omisión concede
 * <b>500 ms</b> para descargar las claves públicas del proveedor y, además, se construye de nuevo en cada
 * intento, así que ese viaje a Internet se repite en <b>cada</b> login. Con una latencia normal a Google
 * (unos 120 ms de ida y vuelta, más el saludo TLS) el margen se agota y el acceso falla de forma
 * intermitente con {@code invalid_id_token}. Aquí se comprueba lo uno y lo otro: que un proveedor lento no
 * tumba el acceso, y que las claves se reaprovechan en vez de volver a pedirse.
 *
 * <p>El resto de las pruebas fija lo que NO debe relajarse al cambiar el descodificador: un ID token de
 * otro emisor, para otro cliente o ya caducado se sigue rechazando.
 */
class ResilientOidcIdTokenDecoderFactoryTest {

    private static final String ISSUER = "https://accounts.example.com";
    private static final String CLIENT_ID = "cliente-de-prueba";
    private static final String KEY_ID = "k1";

    /** Un par RSA es caro de generar: se calcula una vez para toda la clase. */
    private static RSAPublicKey publicKey;
    private static RSAPrivateKey privateKey;
    private static String jwkSetJson;

    private HttpServer server;

    @BeforeAll
    static void generarClaves() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        RSAKey jwk = new RSAKey.Builder(publicKey).keyID(KEY_ID).algorithm(JWSAlgorithm.RS256).build();
        jwkSetJson = new JWKSet(jwk).toString();
    }

    @AfterEach
    void detenerServidor() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void descodificaAunqueElProveedorTardeMasDeMedioSegundoEnDarSusClaves() throws IOException {
        // Medio segundo es justo el límite que concede el descodificador por omisión: este proveedor lo pasa.
        String jwkSetUri = arrancarServidorDeClaves(Duration.ofMillis(1200), new AtomicInteger());
        JwtDecoder decoder = new ResilientOidcIdTokenDecoderFactory().createDecoder(registro(jwkSetUri));

        Jwt jwt = decoder.decode(idToken(ISSUER, CLIENT_ID, Instant.now().plusSeconds(300)));

        assertThat(jwt.getSubject()).isEqualTo("105815860537719339869");
    }

    @Test
    void elDescodificadorPorOmisionDeSpringYaAguantaAEseProveedor() throws IOException {
        // EL AVISO SE CUMPLIÓ, el 18-sep-2026 al subir a Spring Boot 4.1.1.
        //
        // Esta prueba era el contraste que justificaba la clase: comprobaba que la fábrica de Spring SE
        // RENDÍA con un proveedor lento, porque su descodificador concedía 500 ms para pedir las claves.
        // Spring Security subió ese plazo a 30 segundos —por considerar el anterior demasiado corto para
        // muchos despliegues—, así que su fábrica ya aguanta y la comprobación se invierte.
        //
        // La clase propia NO sobra por eso: además del plazo, guarda las claves para no volver a pedirlas
        // en cada acceso, que es lo que mide `pideLasClavesUnaSolaVezAunqueSeEntreVariasVeces`. Quien
        // quiera retirarla tiene que resolver antes esa parte.
        //
        // Y sigue siendo un canario, ahora en el sentido contrario: si Spring volviera a acortar el
        // plazo, esta prueba se pondría en rojo y avisaría.
        String jwkSetUri = arrancarServidorDeClaves(Duration.ofMillis(1200), new AtomicInteger());
        JwtDecoder porOmision = new OidcIdTokenDecoderFactory().createDecoder(registro(jwkSetUri));
        String token = idToken(ISSUER, CLIENT_ID, Instant.now().plusSeconds(300));

        assertThat(porOmision.decode(token).getSubject()).isEqualTo("105815860537719339869");
    }

    @Test
    void pideLasClavesUnaSolaVezAunqueSeEntreVariasVeces() throws IOException {
        AtomicInteger descargas = new AtomicInteger();
        String jwkSetUri = arrancarServidorDeClaves(Duration.ZERO, descargas);
        ResilientOidcIdTokenDecoderFactory factory = new ResilientOidcIdTokenDecoderFactory();
        ClientRegistration registro = registro(jwkSetUri);

        for (int i = 0; i < 3; i++) {
            factory.createDecoder(registro).decode(idToken(ISSUER, CLIENT_ID, Instant.now().plusSeconds(300)));
        }

        assertThat(descargas.get()).isEqualTo(1);
    }

    @Test
    void reaprovechaElDescodificadorDeCadaProveedor() throws IOException {
        String jwkSetUri = arrancarServidorDeClaves(Duration.ZERO, new AtomicInteger());
        ResilientOidcIdTokenDecoderFactory factory = new ResilientOidcIdTokenDecoderFactory();
        ClientRegistration google = registro(jwkSetUri);
        ClientRegistration github = ClientRegistration.withClientRegistration(google).registrationId("github").build();

        assertThat(factory.createDecoder(google)).isSameAs(factory.createDecoder(google));
        assertThat(factory.createDecoder(github)).isNotSameAs(factory.createDecoder(google));
    }

    @Test
    void rechazaUnIdTokenDeOtroEmisor() throws IOException {
        String jwkSetUri = arrancarServidorDeClaves(Duration.ZERO, new AtomicInteger());
        JwtDecoder decoder = new ResilientOidcIdTokenDecoderFactory().createDecoder(registro(jwkSetUri));
        String token = idToken("https://impostor.example.com", CLIENT_ID, Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaUnIdTokenEmitidoParaOtroCliente() throws IOException {
        String jwkSetUri = arrancarServidorDeClaves(Duration.ZERO, new AtomicInteger());
        JwtDecoder decoder = new ResilientOidcIdTokenDecoderFactory().createDecoder(registro(jwkSetUri));
        String token = idToken(ISSUER, "otro-cliente", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaUnIdTokenCaducado() throws IOException {
        String jwkSetUri = arrancarServidorDeClaves(Duration.ZERO, new AtomicInteger());
        JwtDecoder decoder = new ResilientOidcIdTokenDecoderFactory().createDecoder(registro(jwkSetUri));
        String token = idToken(ISSUER, CLIENT_ID, Instant.now().minusSeconds(600));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void unProveedorSinClavesPublicasNoDejaAbiertaLaPuerta() {
        ClientRegistration sinJwkSet = ClientRegistration.withRegistrationId("google").clientId(CLIENT_ID)
                .clientSecret("secreto").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google").scope("openid")
                .authorizationUri(ISSUER + "/auth").tokenUri(ISSUER + "/token").issuerUri(ISSUER).build();

        assertThatThrownBy(() -> new ResilientOidcIdTokenDecoderFactory().createDecoder(sinJwkSet))
                .isInstanceOf(RuntimeException.class);
    }

    /** Servidor local que entrega el JWK set tras el retardo indicado y cuenta cuántas veces se le pide. */
    private String arrancarServidorDeClaves(Duration retardo, AtomicInteger descargas) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            descargas.incrementAndGet();
            esperar(retardo);
            byte[] cuerpo = jwkSetJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, cuerpo.length);
            try (OutputStream salida = exchange.getResponseBody()) {
                salida.write(cuerpo);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
    }

    private static void esperar(Duration retardo) {
        if (retardo.isZero()) {
            return;
        }
        try {
            Thread.sleep(retardo.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ClientRegistration registro(String jwkSetUri) {
        return ClientRegistration.withRegistrationId("google").clientId(CLIENT_ID).clientSecret("secreto")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google").scope("openid")
                .authorizationUri(ISSUER + "/auth").tokenUri(ISSUER + "/token").jwkSetUri(jwkSetUri).issuerUri(ISSUER)
                .build();
    }

    private static String idToken(String issuer, String audience, Instant expiration) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("105815860537719339869")
                    .audience(List.of(audience)).expirationTime(Date.from(expiration))
                    .issueTime(Date.from(Instant.now().minusSeconds(5))).claim("email", "persona@example.com")
                    .claim("email_verified", true).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el ID token de prueba", e);
        }
    }
}
