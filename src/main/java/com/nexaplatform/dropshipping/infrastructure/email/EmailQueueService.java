package com.nexaplatform.dropshipping.infrastructure.email;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailQueueService {

    private final OutboundEmailRepository repo;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Transactional
    public OutboundEmailEntity enqueue(String to, String subject, String template, Map<String, Object> vars) {
        Context ctx = new Context();
        vars.forEach(ctx::setVariable);
        String html = templateEngine.process(template, ctx);
        OutboundEmailEntity email = OutboundEmailEntity.builder().toAddress(to).subject(subject).bodyHtml(html)
                .template(template).status("PENDING").build();
        return repo.save(email);
    }

    @Scheduled(fixedDelay = 15_000)
    @Transactional
    public void dispatchPending() {
        for (OutboundEmailEntity email : repo.findTop20ByStatusOrderByCreatedAtAsc("PENDING")) {
            try {
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
                helper.setTo(email.getToAddress());
                helper.setSubject(email.getSubject());
                helper.setText(email.getBodyHtml(), true);
                helper.setFrom("noreply@nexadrop.local");
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
}
