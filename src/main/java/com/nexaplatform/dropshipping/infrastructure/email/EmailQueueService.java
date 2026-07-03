package com.nexaplatform.dropshipping.infrastructure.email;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @org.springframework.beans.factory.annotation.Value("${nexadrop.email.from:noreply@nexadrop.local}")
    private String fromAddress;
    @org.springframework.beans.factory.annotation.Value("${nexadrop.email.from-name:NX036 Dropshipping}")
    private String fromName;

    @Transactional
    public OutboundEmailEntity enqueue(String to, String subject, String template, Map<String, Object> vars) {
        return enqueue(to, null, subject, template, vars);
    }

    @Transactional
    public OutboundEmailEntity enqueue(String to, String replyTo, String subject, String template,
            Map<String, Object> vars) {
        Context ctx = new Context();
        vars.forEach(ctx::setVariable);
        String html = templateEngine.process(template, ctx);
        OutboundEmailEntity email = OutboundEmailEntity.builder().toAddress(to).replyTo(replyTo).subject(subject)
                .bodyHtml(html).template(template).status("PENDING").build();
        return repo.save(email);
    }

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
                // Named From + Reply-To greatly reduces Gmail spam classification.
                helper.setFrom(new jakarta.mail.internet.InternetAddress(fromAddress, fromName, "UTF-8"));
                String replyTo = email.getReplyTo();
                helper.setReplyTo(replyTo != null && !replyTo.isBlank() ? replyTo : fromAddress);
                for (String cid : cids) {
                    ClassPathResource icon = new ClassPathResource("email-icons/" + cid + ".png");
                    if (icon.exists()) {
                        helper.addInline(cid, icon, "image/png");
                    }
                }
                mailSender.send(msg);
                email.setStatus("SENT");
                email.setSentAt(Instant.now());
            } catch (Exception e) {
                email.setStatus("FAILED");
                email.setAttemptCount(email.getAttemptCount() + 1);
                email.setErrorMessage(e.getMessage());
                log.warn("Email {} send failed: {}", email.getId(), e.getMessage());
            }
            repo.save(email);
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
