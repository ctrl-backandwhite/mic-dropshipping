package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.CategoryEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryJpaRepositoryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Adaptador de persistencia de categorías: lo que el modelo de dominio deja plano (el padre por id y las
 * traducciones como mapa) y aquí hay que resolver contra la entidad gestionada.
 *
 * <p>La regla cara es el upsert de traducciones EN SITIO: sustituir la colección entera hacía saltar la
 * restricción única (category_id, language) en cada edición. Reutilizar las filas existentes es lo que lo
 * evita, y es exactamente lo que fijan estos tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09CategoryRepositoryImplTest {

    @Mock
    CategoryEntityMapper categoryEntityMapper;
    @Mock
    CategoryJpaRepositoryAdapter categoryJpaRepositoryAdapter;
    @InjectMocks
    CategoryRepositoryImpl subject;

    private final UUID categoryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID parentId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void devolverLaEntidadGuardada() {
        when(categoryJpaRepositoryAdapter.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static CategoryEntity entity(UUID id, String... idiomasYNombres) {
        CategoryEntity c = new CategoryEntity();
        c.setId(id);
        c.setTranslations(new ArrayList<>());
        for (int i = 0; i < idiomasYNombres.length; i += 2) {
            CategoryTranslationEntity tr = CategoryTranslationEntity.builder().category(c)
                    .language(idiomasYNombres[i]).name(idiomasYNombres[i + 1]).build();
            c.getTranslations().add(tr);
        }
        return c;
    }

    private static Category model(UUID id, UUID parentId, Map<String, String> names) {
        return Category.builder().id(id).slug("moda").parentId(parentId).names(names).build();
    }

    /** Entidad que acabó guardándose, para inspeccionar padre y traducciones. */
    private CategoryEntity guardada() {
        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoryJpaRepositoryAdapter).save(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- entidad gestionada y padre

    @Test
    void unaCategoriaNuevaSeGuardaSobreUnaEntidadLimpia() {
        subject.save(model(null, null, Map.of("es", "Moda")));

        assertThat(guardada().getId()).isNull();
        verify(categoryJpaRepositoryAdapter, never()).findById(any());
    }

    @Test
    void editarUnaCategoriaQueYaNoExisteFalla() {
        // Crearla de cero con el id recibido resucitaría una categoría que alguien acaba de borrar.
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.empty());
        Category modelo = model(categoryId, null, Map.of());

        assertThatThrownBy(() -> subject.save(modelo)).isInstanceOf(NotFoundException.class);

        verify(categoryJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void colgarLaCategoriaDeUnPadreInexistenteNoGuardaNada() {
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(entity(categoryId)));
        when(categoryJpaRepositoryAdapter.findById(parentId)).thenReturn(Optional.empty());
        Category modelo = model(categoryId, parentId, Map.of());

        assertThatThrownBy(() -> subject.save(modelo)).isInstanceOf(NotFoundException.class);

        verify(categoryJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void quitarElPadreDevuelveLaCategoriaALaRaiz() {
        CategoryEntity gestionada = entity(categoryId);
        gestionada.setParent(entity(parentId));
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(gestionada));

        subject.save(model(categoryId, null, Map.of()));

        assertThat(guardada().getParent()).isNull();
    }

    @Test
    void elPadreSeResuelveDesdeSuIdentificador() {
        CategoryEntity padre = entity(parentId);
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(entity(categoryId)));
        when(categoryJpaRepositoryAdapter.findById(parentId)).thenReturn(Optional.of(padre));

        subject.save(model(categoryId, parentId, Map.of()));

        assertThat(guardada().getParent()).isSameAs(padre);
    }

    // ---------------------------------------------------------------- upsert de traducciones

    @Test
    void unIdiomaQueYaExistiaSeActualizaEnLaMismaFila() {
        // Añadir una fila nueva para el mismo idioma viola la restricción única (category_id, language).
        CategoryEntity gestionada = entity(categoryId, "es", "Nombre viejo");
        CategoryTranslationEntity filaOriginal = gestionada.getTranslations().get(0);
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(gestionada));

        subject.save(model(categoryId, null, Map.of("es", "Nombre nuevo")));

        assertThat(guardada().getTranslations()).hasSize(1).first().isSameAs(filaOriginal);
        assertThat(filaOriginal.getName()).isEqualTo("Nombre nuevo");
    }

    @Test
    void elIdiomaEnMayusculasNoCreaUnaFilaDuplicada() {
        // "ES" y "es" son el mismo idioma: sin normalizar se insertaría una segunda fila y saltaría la clave.
        CategoryEntity gestionada = entity(categoryId, "es", "Moda");
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(gestionada));

        subject.save(model(categoryId, null, Map.of("ES", "Moda mujer")));

        assertThat(guardada().getTranslations()).hasSize(1);
        assertThat(guardada().getTranslations().get(0).getLanguage()).isEqualTo("es");
        assertThat(guardada().getTranslations().get(0).getName()).isEqualTo("Moda mujer");
    }

    @Test
    void unIdiomaQueDejaDeEnviarseSeElimina() {
        CategoryEntity gestionada = entity(categoryId, "es", "Moda", "en", "Fashion");
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(gestionada));

        subject.save(model(categoryId, null, Map.of("es", "Moda")));

        assertThat(guardada().getTranslations()).extracting(CategoryTranslationEntity::getLanguage)
                .containsExactly("es");
    }

    @Test
    void unIdiomaNuevoSeAniadeApuntandoASuCategoria() {
        CategoryEntity gestionada = entity(categoryId, "es", "Moda");
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(gestionada));
        Map<String, String> nombres = new LinkedHashMap<>();
        nombres.put("es", "Moda");
        nombres.put("fr", "Mode");

        subject.save(model(categoryId, null, nombres));

        assertThat(guardada().getTranslations()).extracting(CategoryTranslationEntity::getLanguage)
                .containsExactlyInAnyOrder("es", "fr");
        // La fila nueva tiene que llevar su categoría o el insert se va sin category_id (columna NOT NULL).
        assertThat(guardada().getTranslations()).allSatisfy(tr -> assertThat(tr.getCategory()).isNotNull());
    }

    @Test
    void unNombreVacioONuloNoCreaTraduccion() {
        CategoryEntity gestionada = entity(categoryId);
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(gestionada));
        Map<String, String> nombres = new HashMap<>();
        nombres.put("fr", "   ");
        nombres.put("de", null);
        nombres.put("es", "Moda");

        subject.save(model(categoryId, null, nombres));

        assertThat(guardada().getTranslations()).extracting(CategoryTranslationEntity::getLanguage)
                .containsExactly("es");
    }

    @Test
    void sinNombresLaColeccionQuedaVaciaPeroNuncaNula() {
        // Una colección a null reventaría el flush de Hibernate al persistir la categoría.
        CategoryEntity gestionada = entity(categoryId, "es", "Moda");
        gestionada.setTranslations(null);
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(gestionada));

        subject.save(model(categoryId, null, null));

        assertThat(guardada().getTranslations()).isNotNull().isEmpty();
    }

    // ---------------------------------------------------------------- lecturas

    @Test
    void elListadoCompletoVieneOrdenadoPorPosicion() {
        // El árbol del admin se pinta en este orden; devolverlo como venga lo descoloca en cada carga.
        CategoryEntity tercera = entity(UUID.randomUUID());
        tercera.setPosition(30);
        CategoryEntity primera = entity(UUID.randomUUID());
        primera.setPosition(10);
        when(categoryJpaRepositoryAdapter.findAllWithTranslations()).thenReturn(List.of(tercera, primera));
        when(categoryEntityMapper.toDomain(any()))
                .thenAnswer(i -> Category.builder().id(((CategoryEntity) i.getArgument(0)).getId()).build());

        List<Category> out = subject.findAll();

        assertThat(out).extracting(Category::getId).containsExactly(primera.getId(), tercera.getId());
    }

    @Test
    void buscarSinTerminoUsaElListadoPaginadoNormal() {
        // Enlazar un término nulo en la consulta JPQL hace que Postgres infiera lower(bytea) y falle.
        Pageable pageable = PageRequest.of(0, 20);
        Page<CategoryEntity> page = new PageImpl<>(List.of(entity(categoryId)));
        when(categoryJpaRepositoryAdapter.findAll(pageable)).thenReturn(page);

        subject.search("   ", pageable);

        verify(categoryJpaRepositoryAdapter).findAll(pageable);
        verify(categoryJpaRepositoryAdapter, never()).search(any(), any());
    }

    @Test
    void buscarConTerminoLoRecortaAntesDeConsultar() {
        Pageable pageable = PageRequest.of(0, 20);
        when(categoryJpaRepositoryAdapter.search("moda", pageable))
                .thenReturn(new PageImpl<>(List.of(entity(categoryId))));

        subject.search("  moda  ", pageable);

        verify(categoryJpaRepositoryAdapter).search("moda", pageable);
    }

    @Test
    void pedirUnaCategoriaQueNoExisteDevuelveNuloYNoRevienta() {
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.empty());

        assertThat(subject.getById(categoryId)).isNull();
    }

    @Test
    void buscarPorSlugDevuelveVacioSiNoHayCategoria() {
        when(categoryJpaRepositoryAdapter.findBySlug("moda")).thenReturn(Optional.empty());

        assertThat(subject.findBySlug("moda")).isEmpty();
    }

    @Test
    void actualizarEsExactamenteLoMismoQueGuardar() {
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(entity(categoryId)));

        subject.update(model(categoryId, null, Map.of("es", "Moda")));

        assertThat(guardada().getTranslations()).hasSize(1);
    }

    @Test
    void borrarYComprobarExistenciaDeleganEnElAdaptador() {
        when(categoryJpaRepositoryAdapter.existsById(categoryId)).thenReturn(true);

        subject.delete(categoryId);

        assertThat(subject.existsById(categoryId)).isTrue();
        verify(categoryJpaRepositoryAdapter).deleteById(categoryId);
    }
}
