package com.nexaplatform.dropshipping.infrastructure.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de la cola de correo saliente: el remitente que se elige por tipo de correo, la resolución
 * de las imágenes incrustadas por Content-ID y el marcado de la fila tras enviar (o fallar).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07EmailQueueServiceTest {

    @Mock
    OutboundEmailRepository repo;
    @Mock
    JavaMailSender mailSender;
    @Mock
    ObjectStorageService storage;

    private EmailQueueService service;
    private MimeMessage message;

    @BeforeEach
    void setUp() {
        // Motor de plantillas y ObjectMapper REALES: TemplateEngine.process es final (no se puede doblar)
        // y, además, lo que interesa comprobar es el HTML/JSON que acaba en la fila, no un doble.
        // Con StringTemplateResolver el "nombre de plantilla" es la propia plantilla.
        // SpringTemplateEngine, el mismo que usa la aplicación: el motor plano evalúa con OGNL, que no
        // está en el classpath (las plantillas están escritas con SpringEL).
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(new StringTemplateResolver());
        service = new EmailQueueService(repo, mailSender, templateEngine, storage, new ObjectMapper());
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@nexadrop.local");
        ReflectionTestUtils.setField(service, "fromName", "NX036");
        ReflectionTestUtils.setField(service, "fromBilling", "billing@nexadrop.local");
        ReflectionTestUtils.setField(service, "fromSupport", "support@nexadrop.local");
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /* ==================== encolado ==================== */

    @Test
    void alEncolarSeRenderizaLaPlantillaConSusVariablesYLaFilaNacePendiente() {
        OutboundEmailEntity email = service.enqueue("u@example.com", "Bienvenida", "<p th:text=\"${name}\">x</p>",
                Map.of("name", "Ana"));

        assertThat(email.getStatus()).isEqualTo("PENDING");
        assertThat(email.getBodyHtml()).isEqualTo("<p>Ana</p>");
        assertThat(email.getToAddress()).isEqualTo("u@example.com");
        assertThat(email.getSubject()).isEqualTo("Bienvenida");
        assertThat(email.getReplyTo()).isNull();
        verify(repo).save(email);
    }

    @Test
    void sinImagenesIncrustadasLaColumnaQuedaVacia() {
        OutboundEmailEntity sinMapa = service.enqueue("u@example.com", "r@example.com", "S", "t", Map.of());
        OutboundEmailEntity mapaVacio = service.enqueue("u@example.com", null, "S", "t", Map.of(), Map.of());

        // null (no la cadena "{}") para que la columna quede realmente vacía en base de datos.
        assertThat(sinMapa.getInlineImages()).isNull();
        assertThat(mapaVacio.getInlineImages()).isNull();
        assertThat(sinMapa.getReplyTo()).isEqualTo("r@example.com");
    }

    @Test
    void lasImagenesIncrustadasSePersistenComoJsonParaElEnvioDiferido() {
        OutboundEmailEntity email = service.enqueue("u@example.com", null, "S", "t", Map.of(),
                Map.of("img1", "http://cdn/1.jpg"));

        assertThat(email.getInlineImages()).isEqualTo("{\"img1\":\"http://cdn/1.jpg\"}");
    }

    /* ==================== envío ==================== */

    @Test
    void unCorreoEnviadoQuedaMarcadoComoSentConSuFecha() throws Exception {
        pending("<p>sin imágenes</p>", "emails/welcome", null, null);

        service.dispatchPending();

        ArgumentCaptor<OutboundEmailEntity> captor = ArgumentCaptor.forClass(OutboundEmailEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SENT");
        assertThat(captor.getValue().getSentAt()).isNotNull();
        assertThat(message.getSubject()).isEqualTo("Asunto");
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("noreply@nexadrop.local");
        // Sin Reply-To propio se responde al mismo remitente (mejora la clasificación en Gmail).
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("noreply@nexadrop.local");
        verify(mailSender).send(message);
    }

    @Test
    void lasFacturasSalenDesdeElAliasDeFacturacion() throws Exception {
        pending("<p>x</p>", "emails/invoice", null, null);

        service.dispatchPending();

        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("billing@nexadrop.local");
    }

    @Test
    void elAcuseDeContactoSaleDesdeElAliasDeSoporte() throws Exception {
        pending("<p>x</p>", "emails/contact-ack", null, null);

        service.dispatchPending();

        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("support@nexadrop.local");
    }

    @Test
    void unCorreoSinPlantillaUsaElRemitentePorDefecto() throws Exception {
        pending("<p>x</p>", null, null, null);

        service.dispatchPending();

        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("noreply@nexadrop.local");
    }

    @Test
    void elReplyToPropioMandaSobreElRemitente() throws Exception {
        pending("<p>x</p>", "emails/welcome", null, "soporte@cliente.com");

        service.dispatchPending();

        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("soporte@cliente.com");
    }

    @Test
    void unReplyToEnBlancoSeIgnoraYSeRespondeAlRemitente() throws Exception {
        pending("<p>x</p>", "emails/welcome", null, "   ");

        service.dispatchPending();

        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("noreply@nexadrop.local");
    }

    @Test
    void unFalloDeEnvioMarcaFailedYCuentaElIntentoSinPropagarLaExcepcion() {
        OutboundEmailEntity email = pending("<p>x</p>", "emails/welcome", null, null);
        email.setAttemptCount(2);
        doThrow(new IllegalStateException("SMTP caído")).when(mailSender).send(any(MimeMessage.class));

        service.dispatchPending();

        // El barrido no puede reventar: la fila queda marcada para poder diagnosticar y reintentar.
        assertThat(email.getStatus()).isEqualTo("FAILED");
        assertThat(email.getAttemptCount()).isEqualTo(3);
        assertThat(email.getErrorMessage()).isEqualTo("SMTP caído");
        assertThat(email.getSentAt()).isNull();
        verify(repo).save(email);
    }

    /* ==================== imágenes incrustadas (CID) ==================== */

    @Test
    void unCidQueCoincideConUnIconoEmpaquetadoNoSeBuscaEnElStorage() {
        // truck.png existe en src/main/resources/email-icons: gana al mapa de imágenes del storage.
        pending("<img src=\"cid:truck\">", "emails/welcome", "{\"truck\":\"http://cdn/otra.jpg\"}", null);

        service.dispatchPending();

        verify(storage, never()).bytesFromPublicUrl(anyString());
    }

    @Test
    void unCidQueNoEsIconoSeResuelveContraLasImagenesDelStorage() {
        pending("<img src=\"cid:foto1\">", "emails/invoice", "{\"foto1\":\"http://cdn/1.jpg\"}", null);
        when(storage.bytesFromPublicUrl("http://cdn/1.jpg")).thenReturn(new byte[] { 9, 9, 9 });

        service.dispatchPending();

        verify(storage).bytesFromPublicUrl("http://cdn/1.jpg");
    }

    @Test
    void unCidSinUrlDeclaradaNoSeDescargaYElCorreoSaleIgual() {
        OutboundEmailEntity email = pending("<img src=\"cid:huerfano\">", "emails/invoice", "{}", null);

        service.dispatchPending();

        verify(storage, never()).bytesFromPublicUrl(anyString());
        assertThat(email.getStatus()).isEqualTo("SENT");
    }

    @Test
    void unaImagenQueElStorageNoDevuelveNoRompeElEnvio() {
        OutboundEmailEntity email = pending("<img src=\"cid:foto1\">", "emails/invoice",
                "{\"foto1\":\"http://externo/1.jpg\"}", null);
        when(storage.bytesFromPublicUrl("http://externo/1.jpg")).thenReturn(new byte[0]);

        service.dispatchPending();

        assertThat(email.getStatus()).isEqualTo("SENT");
    }

    @Test
    void unJsonDeImagenesCorruptoSeIgnoraYElCorreoSeEnviaIgual() {
        OutboundEmailEntity email = pending("<img src=\"cid:foto1\">", "emails/invoice", "esto-no-es-json", null);

        service.dispatchPending();

        verify(storage, never()).bytesFromPublicUrl(anyString());
        assertThat(email.getStatus()).isEqualTo("SENT");
    }

    @Test
    void seRecogenTodosLosCidDistintosDelHtmlUnaSolaVez() {
        pending("<img src=\"cid:foto1\"><img src=\"cid:foto1\"><img src=\"cid:foto2\">", "emails/invoice",
                "{\"foto1\":\"http://cdn/1.jpg\",\"foto2\":\"http://cdn/2.jpg\"}", null);
        when(storage.bytesFromPublicUrl(anyString())).thenReturn(new byte[] { 1 });

        service.dispatchPending();

        // El cid repetido se adjunta una sola vez: duplicarlo inflaría el correo y rompería el multipart.
        verify(storage).bytesFromPublicUrl("http://cdn/1.jpg");
        verify(storage).bytesFromPublicUrl("http://cdn/2.jpg");
    }

    @Test
    void unCuerpoNuloNoRompeElBarrido() {
        OutboundEmailEntity email = pending(null, "emails/welcome", null, null);

        service.dispatchPending();

        // setText(null) revienta dentro del try: el correo queda FAILED, pero el barrido continúa.
        assertThat(email.getStatus()).isEqualTo("FAILED");
        assertThat(email.getAttemptCount()).isEqualTo(1);
    }

    /** Deja en la cola un único correo PENDING con el cuerpo/plantilla indicados y lo devuelve. */
    private OutboundEmailEntity pending(String html, String template, String inlineImages, String replyTo) {
        OutboundEmailEntity email = OutboundEmailEntity.builder().id(UUID.randomUUID())
                .toAddress("destino@example.com").subject("Asunto").bodyHtml(html).template(template)
                .inlineImages(inlineImages).replyTo(replyTo).status("PENDING").build();
        when(repo.findTop20ByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(email));
        return email;
    }
}
