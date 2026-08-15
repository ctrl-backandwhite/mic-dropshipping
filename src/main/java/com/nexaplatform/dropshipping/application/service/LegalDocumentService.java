package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.LegalDocumentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.LegalDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Lectura y edición de los textos legales.
 *
 * <p>Los cinco documentos —privacidad, términos, cookies, aviso legal y desistimiento— viven en la base y
 * el admin los edita. Antes eran constantes del escaparate: cambiar una coma exigía tocar código,
 * compilar y desplegar, y quien revisa un texto legal no es quien despliega.
 *
 * <p><b>Reserva de idioma.</b> Si un documento no existe en el idioma pedido, se devuelve el español. Es
 * deliberado: un texto legal ausente no puede resolverse enseñando la clave o una página en blanco — el
 * usuario tiene derecho a leer las condiciones que le vinculan, y es mejor leerlas en otro idioma que no
 * leerlas. Hoy cookies, aviso legal y desistimiento solo existen en dos idiomas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalDocumentService {

    /** Los cinco que el escaparate publica. Cualquier otro tipo se rechaza. */
    public static final Set<String> TIPOS = Set.of("privacy", "terms", "cookies", "notice", "withdrawal");

    private static final String IDIOMA_RESERVA = "es";

    private final LegalDocumentRepository repository;
    private final LegalUpdateNoticeService noticeService;

    /** ¿Es un tipo de documento conocido? Se comprueba antes de tocar la base. */
    public boolean tipoValido(String docType) {
        return docType != null && TIPOS.contains(docType.trim().toLowerCase());
    }

    /**
     * El documento PUBLICADO en ese idioma, con reserva al español.
     *
     * @return vacío solo si el documento no existe en ningún idioma, que sería un fallo de la semilla.
     */
    @Transactional(readOnly = true)
    public Optional<LegalDocumentEntity> publicado(String docType, String lang) {
        String tipo = docType.trim().toLowerCase();
        String idioma = lang == null || lang.isBlank() ? IDIOMA_RESERVA : lang.trim().toLowerCase();
        return repository.findByDocTypeAndLangAndPublishedTrue(tipo, idioma)
                .or(() -> repository.findByDocTypeAndLangAndPublishedTrue(tipo, IDIOMA_RESERVA));
    }

    /** Todos los documentos, borradores incluidos: es la vista del admin. */
    @Transactional(readOnly = true)
    public List<LegalDocumentEntity> todos() {
        return repository.findAllByOrderByDocTypeAscLangAsc();
    }

    /** Un documento concreto para editarlo, esté publicado o no. */
    @Transactional(readOnly = true)
    public Optional<LegalDocumentEntity> paraEditar(String docType, String lang) {
        return repository.findByDocTypeAndLang(docType.trim().toLowerCase(), lang.trim().toLowerCase());
    }

    /**
     * Guarda el contenido de un documento en un idioma. NO cambia la versión ni avisa a nadie: guardar es
     * trabajo en curso, y quien está redactando necesita poder hacerlo a medias sin que salga un correo a
     * toda la base de usuarios en cada pulsación.
     */
    @Transactional
    public LegalDocumentEntity guardar(String docType, String lang, String title, String body, String quien) {
        String tipo = docType.trim().toLowerCase();
        String idioma = lang.trim().toLowerCase();
        LegalDocumentEntity doc = repository.findByDocTypeAndLang(tipo, idioma)
                .orElseGet(() -> LegalDocumentEntity.builder().docType(tipo).lang(idioma)
                        .version("borrador").build());
        // Se escribe en el BORRADOR, no en lo publicado: el escaparate sigue sirviendo la versión
        // anterior, completa, hasta que alguien pulse Publicar.
        doc.setDraftTitle(title);
        doc.setDraftBody(body);
        doc.setUpdatedBy(quien);
        doc.setUpdatedAt(Instant.now());
        return repository.save(doc);
    }

    /**
     * Publica TODOS los documentos con una versión nueva y avisa a las cuentas activas.
     *
     * <p>Se publica en bloque, no documento a documento, porque los usuarios aceptan «los términos y la
     * política de privacidad» como una unidad y la constancia que se guarda es una sola versión. Publicar
     * la privacidad por un lado y los términos por otro dejaría a la mitad de los usuarios aceptando una
     * combinación de textos que nunca existió.
     *
     * @return cuántas cuentas han recibido el aviso.
     */
    @Transactional
    public int publicar(String version, String quien) {
        List<LegalDocumentEntity> docs = repository.findAll();
        Instant ahora = Instant.now();
        for (LegalDocumentEntity doc : docs) {
            // El borrador pasa a ser lo publicado y se limpia. Un documento sin borrador se queda como
            // está: publicar en bloque no puede vaciar los textos que nadie ha tocado.
            if (doc.getDraftBody() != null) {
                doc.setBody(doc.getDraftBody());
                if (doc.getDraftTitle() != null) {
                    doc.setTitle(doc.getDraftTitle());
                }
                doc.setDraftBody(null);
                doc.setDraftTitle(null);
            }
            doc.setVersion(version);
            doc.setPublished(true);
            doc.setUpdatedBy(quien);
            doc.setUpdatedAt(ahora);
        }
        repository.saveAll(docs);
        log.info("::> [LEGAL] {} documentos publicados en la versión {} por {}", docs.size(), version, quien);
        // El aviso es parte de publicar, no un paso aparte que alguien pueda olvidar: cambiar las
        // condiciones sin decírselo al usuario lo deja vinculado por un texto que nunca vio.
        return noticeService.avisarDeVersion(version);
    }
}
