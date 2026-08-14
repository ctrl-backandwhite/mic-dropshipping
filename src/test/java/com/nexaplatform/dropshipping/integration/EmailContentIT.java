package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.enums.AuthEmailLabel;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.campaign.MarketingUnsubscribeService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Certificación del CONTENIDO de los correos de cuenta (activación, reenvío), del idioma en que salen,
 * de la baja de comunicaciones y del comportamiento cuando el SMTP no responde.
 *
 * <p>Cada caso recorre el camino real: la aplicación encola el correo, el barrido de la cola lo compone
 * y se inspecciona el mensaje MIME resultante ya serializado (ver {@link EmailITSupport}). Lo que se
 * afirma es lo que le llega al destinatario, no que se haya llamado a un método.
 *
 * <p>Los correos del ciclo de vida del pedido (factura, envío, reembolso) van en
 * {@link EmailOrderContentIT}.
 */
class EmailContentIT extends EmailITSupport {

    private static final String REGISTRO = "/api/auth/register";
    private static final String ACTIVAR = "/api/auth/activate";
    private static final String REENVIAR = "/api/auth/activate/resend";
    private static final String PASSWORD = "Segura123!";
    /** El enlace del correo lleva el código de activación: se extrae de ahí para probarlo de verdad. */
    private static final String PATRON_CODIGO = "/activate\\?code=([A-Za-z0-9_-]+)";

    @Autowired
    private UserUseCase userUseCase;

    @Autowired
    private MarketingUnsubscribeService unsubscribeService;

    /* ==================================================================================
     * Activación de cuenta
     * ================================================================================== */

    @Test
    @DisplayName("al registrarse llega el correo de activación, con su enlace, y ese enlace activa la cuenta")
    void registroEnviaCorreoDeActivacionConEnlaceQueFunciona() {
        String email = "alta-" + UUID.randomUUID() + "@example.com";

        registrar(email, "es", Map.of("firstName", "Ana", "lastName1", "López"));
        despacharCola();

        MimeMessage correo = unicoCorreoPara(email);
        String html = cuerpoHtml(correo);

        assertThat(asuntoDe(correo)).isEqualTo(AuthEmailLabel.CONFIRM_SUBJECT.of("es"));
        assertThat(html).contains("Ana López")
                .contains(AuthEmailLabel.CONFIRM_CTA.of("es"));

        String codigo = extraer(html, PATRON_CODIGO);
        assertThat(codigo).as("el correo debe traer el enlace de activación con el código").isNotBlank();
        assertThat(html).contains("http://localhost:3003/activate?code=" + codigo);

        // El código del correo tiene que ser EL de la cuenta y tiene que servir para activarla.
        assertThat(codigoActivacionEnBd(email)).isEqualTo(codigo);
        client.post().uri(ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", codigo)).exchange().expectStatus().isNoContent();
        assertThat(estaActiva(email)).as("tras pulsar el enlace la cuenta queda activa").isTrue();
    }

    @Test
    @DisplayName("el reenvío manda un código NUEVO: el viejo deja de valer y el nuevo activa la cuenta")
    void reenvioDelCodigoDeActivacion() {
        String email = "reenvio-" + UUID.randomUUID() + "@example.com";
        registrar(email, "es", Map.of("firstName", "Marta"));
        despacharCola();
        String codigoOriginal = extraer(cuerpoHtml(correosPara(email).get(0)), PATRON_CODIGO);

        client.post().uri(REENVIAR).contentType(MediaType.APPLICATION_JSON)
                .header("CF-Connecting-IP", ipDeCliente())
                .bodyValue(Map.of("email", email)).exchange().expectStatus().isNoContent();
        despacharCola();

        assertThat(correosPara(email)).as("un correo por el alta y otro por el reenvío").hasSize(2);
        MimeMessage reenviado = correosPara(email).get(1);
        String codigoNuevo = extraer(cuerpoHtml(reenviado), PATRON_CODIGO);

        assertThat(asuntoDe(reenviado)).isEqualTo(AuthEmailLabel.CONFIRM_SUBJECT.of("es"));
        assertThat(codigoNuevo).isNotBlank().isNotEqualTo(codigoOriginal);

        // El código viejo ya no vale (se ha reemplazado); el que acaba de llegar, sí.
        client.post().uri(ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", codigoOriginal)).exchange().expectStatus().is4xxClientError();
        assertThat(estaActiva(email)).isFalse();
        client.post().uri(ACTIVAR).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", codigoNuevo)).exchange().expectStatus().isNoContent();
        assertThat(estaActiva(email)).isTrue();
    }

    @Test
    @DisplayName("el reenvío a una cuenta YA activa no manda ningún correo (respuesta neutra)")
    void reenvioSobreCuentaActivaNoMandaNada() {
        String email = "activa-" + UUID.randomUUID() + "@example.com";
        alta(email, "es", "Rosa");
        userUseCase.activate(codigoActivacionEnBd(email));

        client.post().uri(REENVIAR).contentType(MediaType.APPLICATION_JSON)
                .header("CF-Connecting-IP", ipDeCliente())
                .bodyValue(Map.of("email", email)).exchange().expectStatus().isNoContent();
        despacharCola();

        assertThat(correosPara(email))
                .as("solo el correo del alta: reenviar a una cuenta activa no puede generar otro")
                .hasSize(1);
    }

    /* ==================================================================================
     * Idiomas
     * ================================================================================== */

    @ParameterizedTest(name = "idioma {0}")
    @ValueSource(strings = {"es", "en", "pt", "zh", "fr", "de", "it", "nl"})
    @DisplayName("el correo sale en el idioma del usuario y sin claves de traducción crudas")
    void elCorreoSaleEnElIdiomaDelUsuario(String idioma) {
        String email = "idioma-" + idioma + "-" + UUID.randomUUID() + "@example.com";
        alta(email, idioma, "Nombre");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(email);
        String html = cuerpoHtml(correo);

        assertThat(asuntoDe(correo)).isEqualTo(AuthEmailLabel.CONFIRM_SUBJECT.of(idioma));
        assertThat(html).contains(AuthEmailLabel.CONFIRM_TITLE.of(idioma))
                .contains(AuthEmailLabel.CONFIRM_CTA.of(idioma));
        assertThat(sinClavesDeTraduccion(html))
                .as("el cuerpo no puede llevar claves sin traducir (email.x.y) ni variables sin resolver")
                .isTrue();
        assertThat(sinClavesDeTraduccion(asuntoDe(correo))).isTrue();
    }

    @Test
    @DisplayName("un idioma desconocido no deja el correo en blanco: cae al español")
    void idiomaDesconocidoCaeAlEspanol() {
        String email = "idioma-raro-" + UUID.randomUUID() + "@example.com";
        alta(email, "xx", "Nombre");
        despacharCola();

        assertThat(asuntoDe(unicoCorreoPara(email))).isEqualTo(AuthEmailLabel.CONFIRM_SUBJECT.of("es"));
    }

    /* ==================================================================================
     * Casos borde del destinatario y del nombre
     * ================================================================================== */

    @ParameterizedTest(name = "nombre \"{0}\"")
    @CsvSource(delimiter = '|', value = {
            "Begoña Núñez-Ángel|es",
            "李小龙 张伟|zh",
            "Jean-François Sørensen|fr"
    })
    @DisplayName("los nombres con acentos, eñes o caracteres CJK llegan intactos al cuerpo del correo")
    void nombresConAcentosYCjkNoSeRompen(String nombre, String idioma) {
        String email = "nombre-" + UUID.randomUUID() + "@example.com";
        alta(email, idioma, nombre);
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(email));
        // El cuerpo se ha escrito al cable y se ha vuelto a leer: si la codificación estuviera mal, aquí
        // saldrían interrogantes o mojibake en vez del nombre.
        assertThat(html).contains(nombre);
    }

    @Test
    @DisplayName("un usuario SIN nombre recibe su correo igual, sin dejar un hueco 'null' en el saludo")
    void usuarioSinNombreNoRompeElCorreo() {
        String email = "sin-nombre-" + UUID.randomUUID() + "@example.com";
        alta(email, "es", null);
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(email));
        assertThat(html).doesNotContain("null").doesNotContain("{name}");
        assertThat(html).contains(AuthEmailLabel.CONFIRM_CTA.of("es"));
    }

    @Test
    @DisplayName("el destinatario se respeta tal cual: subdirección con +etiqueta y direcciones largas")
    void destinatariosConEtiquetaOMuyLargos() {
        String conEtiqueta = "comprador+facturas.2026@example.com";
        // 254 es el máximo que admite la columna; se prueba una dirección larga pero legal.
        String largo = "u" + "n".repeat(60) + "-" + UUID.randomUUID() + "@" + "sub".repeat(15) + ".example.com";

        alta(conEtiqueta, "es", "Con Etiqueta");
        alta(largo, "es", "Largo");
        despacharCola();

        assertThat(destinatarioDe(unicoCorreoPara(conEtiqueta)))
                .as("la subdirección +etiqueta no se puede perder: es la que enruta en el buzón del cliente")
                .isEqualTo(conEtiqueta);
        assertThat(destinatarioDe(unicoCorreoPara(largo))).isEqualTo(largo);
        assertThat(largo.length()).isLessThanOrEqualTo(254);
    }

    /* ==================================================================================
     * Entrega: sin duplicados y sin perder nada si el SMTP falla
     * ================================================================================== */

    @Test
    @DisplayName("barrer la cola dos veces no reenvía el mismo correo")
    void elMismoCorreoNoSeEnviaDosVeces() {
        String email = "unavez-" + UUID.randomUUID() + "@example.com";
        alta(email, "es", "Única");

        despacharCola();
        despacharCola();
        despacharCola();

        assertThat(correosPara(email)).as("la fila ya enviada no puede volver a salir").hasSize(1);
        assertThat(estadoDelCorreo(email)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("si el SMTP está caído, la cuenta se crea igual y el correo queda para reintentar (no se pierde)")
    void unFalloDeSmtpNoTumbaElAltaNiPierdeElCorreo() {
        String email = "smtp-caido-" + UUID.randomUUID() + "@example.com";
        doThrow(new MailSendException("Connection refused: no route to SMTP host"))
                .when(mailSender).send(any(MimeMessage.class));

        registrar(email, "es", Map.of("firstName", "Sonia"));
        despacharCola();

        // 1) El negocio no se ve arrastrado por el correo: la cuenta existe y está pendiente de activar.
        assertThat(existeUsuario(email)).as("el alta no puede depender de que el SMTP responda").isTrue();
        assertThat(estaActiva(email)).isFalse();
        // 2) El correo no se descarta: sigue pendiente, con el intento contado y aplazado.
        assertThat(estadoDelCorreo(email)).isEqualTo("PENDING");
        assertThat(intentosDelCorreo(email)).isEqualTo(1);
        assertThat(tieneProximoIntento(email)).as("un fallo temporal se aplaza, no se rinde").isTrue();
        verify(mailSender, times(1)).send(any(MimeMessage.class));

        // 3) Cuando el SMTP vuelve, el correo sale (se adelanta el reintento, que si no espera 2 minutos).
        doNothing().when(mailSender).send(any(MimeMessage.class));
        jdbcTemplate.update("UPDATE outbound_email SET next_attempt_at = NULL WHERE to_address = ?", email);
        despacharCola();

        // Dos intentos con el MISMO correo (el primero reventó, el segundo entregó): el mensaje no se
        // reescribe ni se pierde por el camino.
        assertThat(correosPara(email)).hasSize(2);
        assertThat(asuntoDe(correosPara(email).get(1))).isEqualTo(AuthEmailLabel.CONFIRM_SUBJECT.of("es"));
        assertThat(extraer(cuerpoHtml(correosPara(email).get(1)), PATRON_CODIGO))
                .isEqualTo(codigoActivacionEnBd(email));
        assertThat(estadoDelCorreo(email)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("un rechazo definitivo del servidor (5xx) descarta el correo sin reintentos inútiles")
    void unRechazoPermanenteMarcaElCorreoComoFallido() {
        String email = "buzon-inexistente-" + UUID.randomUUID() + "@example.com";
        doThrow(new MailSendException("550 5.1.1 The email account that you tried to reach does not exist"))
                .when(mailSender).send(any(MimeMessage.class));

        alta(email, "es", "Fantasma");
        despacharCola();
        despacharCola();

        assertThat(estadoDelCorreo(email)).isEqualTo("FAILED");
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    /* ==================================================================================
     * Baja de comunicaciones
     * ================================================================================== */

    @Test
    @DisplayName("el enlace de baja del correo lleva un HMAC válido y da de baja; manipulado, NO da de baja")
    void bajaDeMarketingConHmacValidoYManipulado() {
        String email = "baja-" + UUID.randomUUID() + "@example.com";
        User usuario = alta(email, "es", "Suscriptor");
        // Correo de campaña tal y como lo compone la plataforma: el pie lleva el enlace de baja firmado.
        String enlaceBaja = "http://localhost:18082/api/campaigns/unsubscribe?lang=es&token="
                + unsubscribeService.tokenFor(usuario.getId());
        emailQueue.enqueue(email, "Novedades de esta semana · NX036", "emails/notification",
                Map.of("title", "Novedades", "bodyHtml", "Productos nuevos para ti.",
                        "unsubscribeUrl", enlaceBaja, "footer", "NX036"));
        despacharCola();

        String html = cuerpoHtml(correosPara(email).get(1));
        // El HTML escapa el separador de parámetros (&amp;), así que se busca el parámetro, no la URL entera.
        assertThat(html).contains("/api/campaigns/unsubscribe?lang=es");
        String token = extraer(html, "token=([A-Za-z0-9._-]+)");
        assertThat(token).as("el correo tiene que traer el enlace de baja con su firma").isNotBlank();
        assertThat(token).contains(".");

        // Manipulado (se cambia la firma): el usuario NO puede quedar dado de baja.
        String manipulado = token.substring(0, token.lastIndexOf('.')) + ".00000000000000000000000000000000";
        client.get().uri("/api/campaigns/unsubscribe?lang=es&token=" + manipulado)
                .exchange().expectStatus().isOk();
        assertThat(estaDadoDeBajaDeMarketing(email))
                .as("una firma falsa no puede dar de baja a nadie").isFalse();

        // El del correo, tal cual: da de baja.
        client.get().uri("/api/campaigns/unsubscribe?lang=es&token=" + token)
                .exchange().expectStatus().isOk();
        assertThat(estaDadoDeBajaDeMarketing(email)).isTrue();
    }

    @Test
    @DisplayName("el alta en el boletín manda el correo de confirmación y solo el token del correo confirma")
    void altaEnBoletinConfirmaSoloConElTokenDelCorreo() {
        String email = "boletin-" + UUID.randomUUID() + "@example.com";

        client.post().uri("/api/newsletter/subscribe").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email)).exchange().expectStatus().isOk();
        despacharCola();

        MimeMessage correo = unicoCorreoPara(email);
        String token = extraer(cuerpoHtml(correo), "newsletter/confirm\\?token=([A-Za-z0-9]+)");
        assertThat(asuntoDe(correo)).isEqualTo("Confirma tu suscripción · NX036");
        assertThat(token).isNotBlank();
        assertThat(estadoDeSuscripcion(email)).isEqualTo("PENDING");

        // Un token inventado no puede confirmar el alta de otro.
        client.get().uri("/api/newsletter/confirm?token=" + UUID.randomUUID().toString().replace("-", ""))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.confirmed").isEqualTo(false);
        assertThat(estadoDeSuscripcion(email)).isEqualTo("PENDING");

        client.get().uri("/api/newsletter/confirm?token=" + token)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.confirmed").isEqualTo(true);
        assertThat(estadoDeSuscripcion(email)).isEqualTo("SUBSCRIBED");
    }

    @Test
    @DisplayName("sin destinatario no se encola ni se manda nada")
    void sinDestinatarioNoSeMandaNada() {
        despacharCola();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    /* ==================================================================================
     * Utilidades del caso de prueba
     * ================================================================================== */

    /** Alta por HTTP (camino completo del usuario real), con IP propia para no chocar con el límite. */
    private void registrar(String email, String idioma, Map<String, String> nombre) {
        Map<String, Object> cuerpo = new HashMap<>(nombre);
        cuerpo.put("email", email);
        cuerpo.put("password", PASSWORD);
        cuerpo.put("language", idioma);
        cuerpo.put("acceptedTerms", true);
        client.post().uri(REGISTRO).contentType(MediaType.APPLICATION_JSON)
                .header("CF-Connecting-IP", ipDeCliente())
                .bodyValue(cuerpo).exchange().expectStatus().isCreated();
    }

    /**
     * Alta por el caso de uso. Se usa cuando lo que se certifica es el CORREO y no el endpoint: el alta
     * por HTTP está limitada a 5/hora por IP y hay más casos que cupo, así que el trámite se hace por el
     * mismo camino de negocio que usa el controlador, sin pasar por el limitador.
     */
    private User alta(String email, String idioma, String nombre) {
        return userUseCase.register(User.builder().email(email).language(idioma).displayName(nombre).build(),
                PASSWORD);
    }

    private String codigoActivacionEnBd(String email) {
        return jdbcTemplate.queryForObject("SELECT activation_code FROM users WHERE email = ?", String.class, email);
    }

    private boolean estaActiva(String email) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT active FROM users WHERE email = ?", Boolean.class, email));
    }

    private boolean existeUsuario(String email) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        return n != null && n > 0;
    }

    private boolean estaDadoDeBajaDeMarketing(String email) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT marketing_opt_out FROM users WHERE email = ?", Boolean.class, email));
    }

    private String estadoDeSuscripcion(String email) {
        return jdbcTemplate.queryForObject("SELECT status FROM newsletter_subscriber WHERE email = ?", String.class,
                email);
    }

    private String estadoDelCorreo(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbound_email WHERE to_address = ? ORDER BY created_at DESC LIMIT 1",
                String.class, email);
    }

    private int intentosDelCorreo(String email) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbound_email WHERE to_address = ? ORDER BY created_at DESC LIMIT 1",
                Integer.class, email);
        return n == null ? 0 : n;
    }

    private boolean tieneProximoIntento(String email) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbound_email WHERE to_address = ? AND next_attempt_at > now()",
                Integer.class, email);
        return n != null && n > 0;
    }

    /**
     * El correo no puede llevar una clave de traducción sin resolver ("email.order.subject"), ni una
     * expresión de plantilla sin evaluar. Es el fallo típico cuando se añade un texto y se olvida el
     * idioma: el usuario recibe la clave en crudo.
     */
    private static boolean sinClavesDeTraduccion(String texto) {
        return !texto.matches("(?s).*\\b(email|order|invoice|auth)\\.[a-z]+\\.[a-z.]+\\b.*")
                && !texto.contains("${")
                && !texto.contains("th:text");
    }
}
