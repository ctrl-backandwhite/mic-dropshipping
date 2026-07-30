package com.nexaplatform.dropshipping.infrastructure.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailQueueService {

    private final OutboundEmailRepository repo;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final ObjectStorageService storage;
    private final ObjectMapper objectMapper;

    @Value("${nexadrop.email.from:noreply@nexadrop.local}")
    private String fromAddress;
    @Value("${nexadrop.email.from-name:NX036 Dropshipping}")
    private String fromName;
    // Remitentes por tipo de correo (alias del dominio). Si no se definen, caen al 'from' por defecto.
    @Value("${nexadrop.email.from-billing:${nexadrop.email.from:noreply@nexadrop.local}}")
    private String fromBilling;
    @Value("${nexadrop.email.from-support:${nexadrop.email.from:noreply@nexadrop.local}}")
    private String fromSupport;

    /**
     * Elige el remitente (alias del dominio) según el tipo de correo, identificado por el template:
     * facturas/recibos → billing@; acuse de contacto → support@; el resto (auth, notificaciones) → no-reply@.
     */
    private String resolveFrom(String template) {
        if (template != null) {
            if (template.contains("invoice")) return fromBilling;
            if (template.contains("contact-ack")) return fromSupport;
        }
        return fromAddress;
    }

    @Transactional
    public OutboundEmailEntity enqueue(String to, String subject, String template, Map<String, Object> vars) {
        return enqueue(to, null, subject, template, vars);
    }

    @Transactional
    public OutboundEmailEntity enqueue(String to, String replyTo, String subject, String template,
            Map<String, Object> vars) {
        return enqueue(to, replyTo, subject, template, vars, Map.of());
    }

    /**
     * Encola un correo cuyas imágenes viajan DENTRO del mensaje.
     *
     * @param inlineImages mapa {@code cid → urlPublica} del storage. El HTML debe referenciarlas como
     *        {@code src="cid:<clave>"}; al enviar se descargan del bucket, se reducen a miniatura y se
     *        adjuntan como inline. Se persiste con el correo para que el envío diferido las resuelva.
     */
    @Transactional
    public OutboundEmailEntity enqueue(String to, String replyTo, String subject, String template,
            Map<String, Object> vars, Map<String, String> inlineImages) {
        Context ctx = new Context();
        vars.forEach(ctx::setVariable);
        String html = templateEngine.process(template, ctx);
        OutboundEmailEntity email = OutboundEmailEntity.builder().toAddress(to).replyTo(replyTo).subject(subject)
                .bodyHtml(html).template(template).status("PENDING")
                .inlineImages(writeInlineImages(inlineImages)).build();
        return repo.save(email);
    }

    /** Serializa el mapa de imágenes inline; null si no hay ninguna (columna vacía). */
    private String writeInlineImages(Map<String, String> inlineImages) {
        if (inlineImages == null || inlineImages.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(inlineImages);
        } catch (JsonProcessingException e) {
            log.warn("No se pudieron serializar las imágenes inline del email: {}", e.getMessage());
            return null;
        }
    }

    /** Deserializa el mapa persistido; vacío si la columna está vacía o corrupta. */
    private Map<String, String> readInlineImages(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("Imágenes inline del email ilegibles: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Intentos de envío antes de rendirse. Un fallo de SMTP suele ser pasajero. */
    private static final int MAX_SEND_ATTEMPTS = 5;

    @Scheduled(fixedDelay = 15_000)
    @Transactional
    public void dispatchPending() {
        for (OutboundEmailEntity email : repo.findTop20ByStatusOrderByCreatedAtAsc("PENDING")) {
            try {
                String html = email.getBodyHtml();
                // Iconos FontAwesome incrustados como adjuntos inline (CID): funcionan en Gmail sin
                // necesidad de hosting público (los data-URI/SVG los bloquea). multipart solo si hay alguno.
                Set<String> cids = referencedCids(html);
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, !cids.isEmpty(),
                        StandardCharsets.UTF_8.name());
                helper.setTo(email.getToAddress());
                helper.setSubject(email.getSubject());
                helper.setText(html, true);
                // From por tipo (alias del dominio) + Reply-To — reduce mucho la clasificación como spam en Gmail.
                String from = resolveFrom(email.getTemplate());
                helper.setFrom(new InternetAddress(from, fromName, "UTF-8"));
                String replyTo = email.getReplyTo();
                helper.setReplyTo(replyTo != null && !replyTo.isBlank() ? replyTo : from);
                // Un CID se resuelve primero contra los iconos empaquetados y, si no es uno de ellos,
                // contra las imágenes del storage declaradas al encolar (fotos de producto de la factura).
                Map<String, String> storageImages = readInlineImages(email.getInlineImages());
                for (String cid : cids) {
                    ClassPathResource icon = new ClassPathResource("email-icons/" + cid + ".png");
                    if (icon.exists()) {
                        helper.addInline(cid, icon, "image/png");
                        continue;
                    }
                    attachStorageImage(helper, cid, storageImages.get(cid));
                }
                mailSender.send(msg);
                email.setStatus("SENT");
                email.setSentAt(Instant.now());
            } catch (Exception e) {
                // El barrido sólo lee PENDING, así que marcar FAILED al primer tropiezo era rendirse
                // para siempre: attemptCount no pasaba nunca de 1 y ningún correo fallido volvía a
                // salir. Un SMTP que no responde suele estar de vuelta al minuto siguiente, así que la
                // fila se queda PENDING hasta agotar los intentos y sólo entonces pasa a FAILED.
                int attempts = email.getAttemptCount() + 1;
                email.setAttemptCount(attempts);
                email.setErrorMessage(e.getMessage());
                if (attempts >= MAX_SEND_ATTEMPTS) {
                    email.setStatus("FAILED");
                    log.error("Email {} descartado tras {} intentos: {}", email.getId(), attempts, e.getMessage());
                } else {
                    log.warn("Email {} falló (intento {}/{}): {}", email.getId(), attempts, MAX_SEND_ATTEMPTS,
                            e.getMessage());
                }
            }
            repo.save(email);
        }
    }

    /**
     * Adjunta como inline una imagen del storage. Los bytes se leen del bucket por el cliente S3
     * (no por HTTP), así que funciona igual en local —donde la URL pública es {@code localhost} y sería
     * inalcanzable desde fuera— que en producción. Se reduce a miniatura para no inflar el correo.
     *
     * <p>Si la imagen no está en nuestro bucket (URL externa, p. ej. alicdn) o no se puede leer, no se
     * adjunta: el {@code <img>} quedará roto, lo mismo que ocurría antes, pero el correo se envía igual.
     */
    private void attachStorageImage(MimeMessageHelper helper, String cid, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        byte[] bytes = storage.bytesFromPublicUrl(url);
        if (bytes == null || bytes.length == 0) {
            log.debug("Imagen inline {} no disponible en el storage: {}", cid, url);
            return;
        }
        byte[] thumb = EmailImageThumbnailer.thumbnail(bytes);
        try {
            helper.addInline(cid, new ByteArrayResource(thumb), "image/jpeg");
        } catch (MessagingException e) {
            log.warn("No se pudo adjuntar la imagen inline {}: {}", cid, e.getMessage());
        }
    }

    private static final Pattern CID_REF = Pattern.compile("cid:([A-Za-z0-9_-]+)");

    /** Extrae los identificadores referenciados como {@code src="cid:NAME"} en el HTML del email. */
    private static Set<String> referencedCids(String html) {
        Set<String> cids = new LinkedHashSet<>();
        if (html != null) {
            Matcher m = CID_REF.matcher(html);
            while (m.find()) {
                cids.add(m.group(1));
            }
        }
        return cids;
    }
}
