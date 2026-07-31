package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyCourseEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AcademyCourseJpaRepositoryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRUD de cursos de Academia desde el panel. La regla que más importa es el slug: es la URL pública
 * del curso, y una edición cualquiera no puede regenerarlo a partir del título (rompería el enlace
 * que ya está compartido y los marcadores de los alumnos).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05AdminAcademyControllerTest {

    @Mock
    AcademyCourseJpaRepositoryAdapter repository;

    @InjectMocks
    AdminAcademyController controller;

    private static final UUID COURSE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(i -> {
            AcademyCourseEntity e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(COURSE_ID);
            }
            return e;
        });
    }

    private AcademyCourseEntity storedCourse() {
        AcademyCourseEntity e = new AcademyCourseEntity();
        e.setId(COURSE_ID);
        e.setSlug("dropshipping-desde-cero");
        e.setTitle("Dropshipping desde cero");
        e.setDescription("Curso inicial");
        e.setInstructor("Ana");
        e.setDurationMinutes(90);
        e.setLocale("es");
        e.setLevel("BEGINNER");
        e.setPublished(true);
        return e;
    }

    private AcademyCourseEntity captureSaved() {
        ArgumentCaptor<AcademyCourseEntity> captor = ArgumentCaptor.forClass(AcademyCourseEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /* ===================== alta ===================== */

    @Test
    @DisplayName("el alta deriva el slug del título")
    void elAltaDerivaElSlugDelTitulo() {
        controller.create(Map.of("title", "Dropshipping desde cero"));

        assertThat(captureSaved().getSlug()).isEqualTo("dropshipping-desde-cero");
    }

    @Test
    @DisplayName("el slug que llega en la petición manda sobre el derivado del título")
    void elSlugDeLaPeticionManda() {
        controller.create(Map.of("title", "Dropshipping desde cero", "slug", "curso-inicial"));

        assertThat(captureSaved().getSlug()).isEqualTo("curso-inicial");
    }

    @Test
    @DisplayName("el alta aplica idioma y nivel por defecto cuando no llegan")
    void elAltaAplicaIdiomaYNivelPorDefecto() {
        controller.create(Map.of("title", "Curso"));

        AcademyCourseEntity saved = captureSaved();
        assertThat(saved.getLocale()).isEqualTo("es");
        assertThat(saved.getLevel()).isEqualTo("BEGINNER");
    }

    @Test
    @DisplayName("el alta respeta el idioma y el nivel que llegan en la petición")
    void elAltaRespetaIdiomaYNivelDeLaPeticion() {
        controller.create(Map.of("title", "Course", "locale", "en", "level", "ADVANCED"));

        AcademyCourseEntity saved = captureSaved();
        assertThat(saved.getLocale()).isEqualTo("en");
        assertThat(saved.getLevel()).isEqualTo("ADVANCED");
    }

    @Test
    @DisplayName("la respuesta del alta devuelve el curso ya mapeado")
    void laRespuestaDelAltaDevuelveElCursoMapeado() {
        ResponseEntity<Map<String, Object>> response = controller
                .create(Map.of("title", "Curso", "instructor", "Ana", "durationMinutes", 45));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("title", "Curso").containsEntry("instructor", "Ana")
                .containsEntry("durationMinutes", 45).containsEntry("id", COURSE_ID);
    }

    /* ===================== edición ===================== */

    @Test
    @DisplayName("editar sin slug NO regenera la URL pública a partir del nuevo título")
    void editarSinSlugNoRegeneraLaUrlPublica() {
        AcademyCourseEntity stored = storedCourse();
        when(repository.findById(COURSE_ID)).thenReturn(Optional.of(stored));

        controller.update(COURSE_ID, Map.of("title", "Dropshipping desde cero (edición 2026)"));

        // Si el slug se regenerara, el enlace ya compartido del curso devolvería 404.
        assertThat(stored.getSlug()).isEqualTo("dropshipping-desde-cero");
        assertThat(stored.getTitle()).isEqualTo("Dropshipping desde cero (edición 2026)");
    }

    @Test
    @DisplayName("un slug en blanco no borra el que ya tenía el curso")
    void unSlugEnBlancoNoBorraElExistente() {
        AcademyCourseEntity stored = storedCourse();
        when(repository.findById(COURSE_ID)).thenReturn(Optional.of(stored));

        controller.update(COURSE_ID, Map.of("slug", "   "));

        assertThat(stored.getSlug()).isEqualTo("dropshipping-desde-cero");
    }

    @Test
    @DisplayName("editar un curso que no existe devuelve 'no encontrado' y no guarda nada")
    void editarUnCursoInexistenteFalla() {
        when(repository.findById(COURSE_ID)).thenReturn(Optional.empty());
        Map<String, Object> body = Map.of("title", "X");

        assertThatThrownBy(() -> controller.update(COURSE_ID, body)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("los campos que no llegan en la petición no se tocan")
    void losCamposQueNoLleganNoSeTocan() {
        AcademyCourseEntity stored = storedCourse();
        when(repository.findById(COURSE_ID)).thenReturn(Optional.of(stored));

        controller.update(COURSE_ID, Map.of("title", "Otro título"));

        assertThat(stored.getDescription()).isEqualTo("Curso inicial");
        assertThat(stored.getInstructor()).isEqualTo("Ana");
        assertThat(stored.getDurationMinutes()).isEqualTo(90);
        assertThat(stored.isPublished()).isTrue();
    }

    @Test
    @DisplayName("un campo opcional que llega en blanco SÍ se vacía (así se puede borrar)")
    void unCampoOpcionalEnBlancoSeVacia() {
        AcademyCourseEntity stored = storedCourse();
        when(repository.findById(COURSE_ID)).thenReturn(Optional.of(stored));
        Map<String, Object> body = new HashMap<>();
        body.put("description", "   ");
        body.put("instructor", null);

        controller.update(COURSE_ID, body);

        assertThat(stored.getDescription()).isNull();
        assertThat(stored.getInstructor()).isNull();
    }

    @Test
    @DisplayName("la duración solo se aplica si llega como número")
    void laDuracionSoloSeAplicaSiEsNumero() {
        AcademyCourseEntity stored = storedCourse();
        when(repository.findById(COURSE_ID)).thenReturn(Optional.of(stored));

        controller.update(COURSE_ID, Map.of("durationMinutes", "hora y media"));
        assertThat(stored.getDurationMinutes()).isEqualTo(90);

        controller.update(COURSE_ID, Map.of("durationMinutes", 120));
        assertThat(stored.getDurationMinutes()).isEqualTo(120);
    }

    @Test
    @DisplayName("la publicación se entiende tanto de un booleano como de su texto")
    void laPublicacionSeEntiendeDeBooleanoODeTexto() {
        AcademyCourseEntity stored = storedCourse();
        when(repository.findById(COURSE_ID)).thenReturn(Optional.of(stored));

        controller.update(COURSE_ID, Map.of("published", "false"));
        assertThat(stored.isPublished()).isFalse();

        controller.update(COURSE_ID, Map.of("published", true));
        assertThat(stored.isPublished()).isTrue();
    }

    @Test
    @DisplayName("las URLs de portada y vídeo se actualizan cuando llegan")
    void lasUrlsDePortadaYVideoSeActualizan() {
        AcademyCourseEntity stored = storedCourse();
        when(repository.findById(COURSE_ID)).thenReturn(Optional.of(stored));

        controller.update(COURSE_ID,
                Map.of("coverUrl", "https://cdn/portada.webp", "videoUrl", "https://cdn/clase.mp4"));

        assertThat(stored.getCoverUrl()).isEqualTo("https://cdn/portada.webp");
        assertThat(stored.getVideoUrl()).isEqualTo("https://cdn/clase.mp4");
    }

    /* ===================== listado y borrado ===================== */

    @Test
    @DisplayName("el listado devuelve todos los cursos, publicados o no")
    void elListadoDevuelveTodosLosCursos() {
        AcademyCourseEntity draft = storedCourse();
        draft.setPublished(false);
        when(repository.findAll()).thenReturn(List.of(storedCourse(), draft));

        List<Map<String, Object>> list = controller.list();

        assertThat(list).hasSize(2);
        assertThat(list.get(1)).containsEntry("published", false);
    }

    @Test
    @DisplayName("los campos vacíos se devuelven como cadena vacía para que el formulario no reciba nulos")
    void losCamposVaciosSeDevuelvenComoCadenaVacia() {
        AcademyCourseEntity bare = new AcademyCourseEntity();
        bare.setId(COURSE_ID);
        when(repository.findAll()).thenReturn(List.of(bare));

        Map<String, Object> row = controller.list().get(0);

        assertThat(row).containsEntry("slug", "").containsEntry("title", "").containsEntry("description", "")
                .containsEntry("instructor", "").containsEntry("coverUrl", "").containsEntry("videoUrl", "")
                .containsEntry("durationMinutes", 0).containsEntry("locale", "es")
                .containsEntry("level", "BEGINNER");
    }

    @Test
    @DisplayName("borrar un curso responde sin contenido")
    void borrarUnCursoRespondeSinContenido() {
        ResponseEntity<Void> response = controller.delete(COURSE_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(repository).deleteById(COURSE_ID);
    }
}
