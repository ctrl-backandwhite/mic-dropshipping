package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.LegalDocumentService;
import com.nexaplatform.dropshipping.application.service.LegalUpdateNoticeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.LegalDocumentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.LegalDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Edición y publicación de los textos legales.
 *
 * <p>Lo que se protege aquí es la separación entre BORRADOR y PUBLICADO. Sin ella, guardar cambiaba el
 * texto en vivo: quien redactara una nueva privacidad la publicaría frase a frase y cualquiera que
 * entrara mientras tanto leería un documento a medio escribir. En un texto legal eso no es un detalle
 * estético — es la redacción que vincula al usuario, y tiene que estar completa cuando se hace visible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov08LegalDocumentServiceTest {

    @Mock
    private LegalDocumentRepository repository;
    @Mock
    private LegalUpdateNoticeService noticeService;

    @InjectMocks
    private LegalDocumentService service;

    private static LegalDocumentEntity doc(String tipo, String lang, String titulo, String cuerpo) {
        return LegalDocumentEntity.builder().docType(tipo).lang(lang).title(titulo).body(cuerpo)
                .version("2026-08-15").published(true).build();
    }

    @Test
    @DisplayName("guardar escribe en el BORRADOR y no toca lo publicado")
    void guardarNoTocaLoPublicado() {
        LegalDocumentEntity existente = doc("cookies", "es", "Publicado", "{\"intro\":\"vigente\"}");
        when(repository.findByDocTypeAndLang("cookies", "es")).thenReturn(Optional.of(existente));
        when(repository.save(existente)).thenReturn(existente);

        service.guardar("cookies", "es", "A medio escribir", "{\"intro\":\"sin terminar\"}", "admin@test");

        // Lo que sirve el escaparate sigue intacto; el trabajo en curso va aparte.
        assertThat(existente.getTitle()).isEqualTo("Publicado");
        assertThat(existente.getBody()).isEqualTo("{\"intro\":\"vigente\"}");
        assertThat(existente.getDraftTitle()).isEqualTo("A medio escribir");
        assertThat(existente.getDraftBody()).isEqualTo("{\"intro\":\"sin terminar\"}");
    }

    @Test
    @DisplayName("guardar no cambia la versión ni avisa a nadie")
    void guardarNoAvisa() {
        LegalDocumentEntity existente = doc("terms", "es", "T", "{}");
        when(repository.findByDocTypeAndLang("terms", "es")).thenReturn(Optional.of(existente));
        when(repository.save(existente)).thenReturn(existente);

        service.guardar("terms", "es", "T2", "{\"intro\":\"x\"}", "admin@test");

        // Un correo a toda la base de usuarios en cada pulsación del botón de guardar sería insufrible —
        // y quemaría la credibilidad del aviso justo cuando de verdad haga falta.
        assertThat(existente.getVersion()).isEqualTo("2026-08-15");
        verify(noticeService, org.mockito.Mockito.never()).avisarDeVersion(anyString());
    }

    @Test
    @DisplayName("publicar promociona el borrador y lo limpia")
    void publicarPromocionaElBorrador() {
        LegalDocumentEntity d = doc("cookies", "es", "Viejo", "{\"intro\":\"viejo\"}");
        d.setDraftTitle("Nuevo");
        d.setDraftBody("{\"intro\":\"nuevo\"}");
        when(repository.findAll()).thenReturn(List.of(d));

        service.publicar("2026-09-01", "admin@test");

        assertThat(d.getTitle()).isEqualTo("Nuevo");
        assertThat(d.getBody()).isEqualTo("{\"intro\":\"nuevo\"}");
        assertThat(d.getDraftTitle()).isNull();
        assertThat(d.getDraftBody()).isNull();
        assertThat(d.getVersion()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("un documento SIN borrador no se vacía al publicar")
    void publicarNoVaciaLosNoTocados() {
        // Se publica en bloque: si al hacerlo se copiara un borrador nulo sobre el cuerpo, publicar una
        // corrección de la privacidad dejaría en blanco los otros cuatro documentos.
        LegalDocumentEntity intacto = doc("notice", "es", "Aviso legal", "{\"intro\":\"contenido\"}");
        when(repository.findAll()).thenReturn(List.of(intacto));

        service.publicar("2026-09-01", "admin@test");

        assertThat(intacto.getTitle()).isEqualTo("Aviso legal");
        assertThat(intacto.getBody()).isEqualTo("{\"intro\":\"contenido\"}");
        assertThat(intacto.getVersion()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("publicar avisa a los usuarios con la versión nueva")
    void publicarAvisa() {
        when(repository.findAll()).thenReturn(List.of(doc("terms", "es", "T", "{}")));
        when(noticeService.avisarDeVersion("2026-09-01")).thenReturn(42);

        assertThat(service.publicar("2026-09-01", "admin@test")).isEqualTo(42);

        ArgumentCaptor<String> v = ArgumentCaptor.forClass(String.class);
        verify(noticeService).avisarDeVersion(v.capture());
        assertThat(v.getValue()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("si el documento no existe en el idioma pedido, se sirve el español")
    void reservaAlEspanol() {
        // Cookies, aviso legal y desistimiento solo existen en dos idiomas. Un texto legal ausente no se
        // puede resolver con una página en blanco: el usuario tiene derecho a leer lo que le vincula, y es
        // mejor leerlo en otro idioma que no leerlo.
        LegalDocumentEntity es = doc("cookies", "es", "Política de cookies", "{}");
        when(repository.findByDocTypeAndLangAndPublishedTrue("cookies", "de")).thenReturn(Optional.empty());
        when(repository.findByDocTypeAndLangAndPublishedTrue("cookies", "es")).thenReturn(Optional.of(es));

        assertThat(service.publicado("cookies", "de")).contains(es);
    }

    @Test
    @DisplayName("sin idioma se sirve el español directamente")
    void sinIdiomaEspanol() {
        LegalDocumentEntity es = doc("terms", "es", "Términos", "{}");
        when(repository.findByDocTypeAndLangAndPublishedTrue("terms", "es")).thenReturn(Optional.of(es));

        assertThat(service.publicado("terms", null)).contains(es);
        assertThat(service.publicado("terms", "  ")).contains(es);
    }

    @Test
    @DisplayName("solo se aceptan los cinco tipos conocidos")
    void tiposValidos() {
        assertThat(service.tipoValido("privacy")).isTrue();
        assertThat(service.tipoValido("TERMS")).isTrue();
        assertThat(service.tipoValido(" cookies ")).isTrue();
        assertThat(service.tipoValido("notice")).isTrue();
        assertThat(service.tipoValido("withdrawal")).isTrue();
        // Cualquier otra cosa se corta antes de tocar la base.
        assertThat(service.tipoValido("inventado")).isFalse();
        assertThat(service.tipoValido("../../etc/passwd")).isFalse();
        assertThat(service.tipoValido(null)).isFalse();
        assertThat(service.tipoValido("")).isFalse();
    }

    @Test
    @DisplayName("guardar un documento que aún no existe lo crea como borrador")
    void guardarCreaSiNoExiste() {
        when(repository.findByDocTypeAndLang("withdrawal", "de")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(LegalDocumentEntity.class)))
                .thenAnswer(i -> i.getArgument(0));

        LegalDocumentEntity creado = service.guardar("withdrawal", "de", "Widerruf", "{\"intro\":\"x\"}", "admin");

        // Nace SIN publicar contenido: hasta que alguien pulse Publicar, el escaparate sigue cayendo al
        // español por la reserva de idioma en vez de enseñar una traducción a medias.
        assertThat(creado.getDocType()).isEqualTo("withdrawal");
        assertThat(creado.getLang()).isEqualTo("de");
        assertThat(creado.getDraftTitle()).isEqualTo("Widerruf");
        assertThat(creado.getBody()).isNull();
    }
}
