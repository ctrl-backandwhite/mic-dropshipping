package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbound_email")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundEmailEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "to_address", nullable = false, length = 254)
    private String toAddress;

    @Column(name = "reply_to", length = 254)
    private String replyTo;

    @Column(nullable = false, length = 300)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(length = 80)
    private String template;

    /**
     * Imágenes a adjuntar como inline (Content-ID), serializadas como JSON {@code {cid: urlPublica}}.
     * El HTML las referencia con {@code src="cid:<clave>"} y el dispatcher las descarga del storage al
     * enviar. Null o vacío = el correo no lleva imágenes propias (solo, si acaso, iconos del classpath).
     */
    @Column(name = "inline_images", columnDefinition = "TEXT")
    private String inlineImages;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_count")
    private int attemptCount;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
