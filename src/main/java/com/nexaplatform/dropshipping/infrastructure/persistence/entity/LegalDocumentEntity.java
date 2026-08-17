package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Un documento legal en un idioma: privacidad, términos, cookies, aviso legal o desistimiento.
 *
 * <p>Antes eran constantes de TypeScript dentro del escaparate, así que cambiar una coma exigía tocar
 * código, compilar y desplegar — y quien revisa un texto legal no suele ser quien despliega.
 *
 * <p>El cuerpo se guarda como JSON con la MISMA forma que consume la página pública
 * ({@code {intro, sections:[{h, p:[…]}]}}). Guardarlo así, y no en columnas o en HTML, evita traducir de
 * un formato a otro entre el editor, la API y el escaparate: los tres hablan de lo mismo.
 */
@Entity
@Table(name = "legal_document")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LegalDocumentEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    /** privacy | terms | cookies | notice | withdrawal */
    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType;

    @Column(nullable = false, length = 5)
    private String lang;

    @Column(nullable = false, length = 200)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String body;

    @Column(nullable = false, length = 20)
    private String version;

    /**
     * Borrador en edición, o {@code null} si no hay cambios pendientes. El escaparate NUNCA lo sirve: sin
     * esta separación, quien redactara una nueva versión la publicaría frase a frase y cualquiera que
     * entrara mientras tanto leería un documento a medio escribir.
     */
    @Column(name = "draft_title", length = 200)
    private String draftTitle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_body", columnDefinition = "jsonb")
    private String draftBody;

    /**
     * Un documento sin publicar es un borrador: el admin puede guardarlo a medias sin que el escaparate lo
     * enseñe. La página pública solo sirve los publicados.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean published = true;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
