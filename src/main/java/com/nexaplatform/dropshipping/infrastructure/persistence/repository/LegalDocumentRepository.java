package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.LegalDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalDocumentRepository extends JpaRepository<LegalDocumentEntity, UUID> {

    /** El documento publicado en ese idioma, que es lo que sirve el escaparate. */
    Optional<LegalDocumentEntity> findByDocTypeAndLangAndPublishedTrue(String docType, String lang);

    /** Incluye borradores: es lo que ve el admin al editar. */
    Optional<LegalDocumentEntity> findByDocTypeAndLang(String docType, String lang);

    List<LegalDocumentEntity> findByDocTypeOrderByLangAsc(String docType);

    List<LegalDocumentEntity> findAllByOrderByDocTypeAscLangAsc();
}
