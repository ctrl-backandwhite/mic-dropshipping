package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CategoryUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.impl.CategoryUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.domain.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategorySearchService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategorySearchService.IndexedCategory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del caso de uso de categorías que la suite existente no fija: el conteo de productos, el filtro
 * con/sin productos, el alta/baja en lote, el borrado protegido y el manejo del padre.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10CategoryUseCaseImplTest {

    @Mock
    CategoryRepository categoryRepository;
    @Mock
    CategoryUpdateMapper categoryUpdateMapper;
    @Mock
    CategoryIndexer categoryIndexer;
    @Mock
    CategorySearchService categorySearchService;
    @Mock
    EntityManager em;

    @InjectMocks
    CategoryUseCaseImpl useCase;

    @BeforeEach
    void setUp() throws Exception {
        // El EntityManager entra por @PersistenceContext, no por el constructor de Lombok: Mockito lo
        // ignora al inyectar por constructor, y sin ponerlo a mano todos los conteos de productos
        // revientan con NullPointerException.
        Field emField = CategoryUseCaseImpl.class.getDeclaredField("em");
        emField.setAccessible(true);
        emField.set(useCase, em);
    }

    /* ---------- listados ---------- */

    @Test
    void elListadoSaleDelIndiceYLeAnadeElConteoRealDeProductos() {
        UUID conProductos = UUID.randomUUID();
        UUID vacia = UUID.randomUUID();
        stubConteoAgrupado(conProductos, 7L);
        when(categorySearchService.listFromIndex(null))
                .thenReturn(Optional.of(List.of(indexada(conProductos, "moda"), indexada(vacia, "hogar"))));

        List<Category> all = useCase.findAll();

        // El índice no guarda el número de productos: si no se resolviera aquí, el panel mostraría 0.
        assertThat(all).extracting(Category::getProductCount).containsExactly(7L, 0L);
        assertThat(all.get(0).getNames()).containsEntry("es", "Moda").containsEntry("en", "Fashion").containsEntry("pt",
                "Moda pt");
        verify(categoryRepository, never()).findAll();
    }

    @Test
    void siElIndiceNoRespondeElListadoCaeALaBaseDeDatos() {
        UUID id = UUID.randomUUID();
        stubConteoAgrupado(id, 3L);
        when(categorySearchService.listFromIndex(null)).thenReturn(Optional.empty());
        when(categoryRepository.findAll()).thenReturn(List.of(Category.builder().id(id).slug("moda").build()));

        List<Category> all = useCase.findAll();

        assertThat(all).singleElement().extracting(Category::getProductCount).isEqualTo(3L);
    }

    @Test
    void elMenuDelEscaparateSoloListaCategoriasConProductos() {
        UUID llena = UUID.randomUUID();
        UUID vacia = UUID.randomUUID();
        stubConteoAgrupado(llena, 2L);
        when(categorySearchService.listFromIndex(null)).thenReturn(Optional.empty());
        when(categoryRepository.findAll()).thenReturn(List.of(Category.builder().id(llena).slug("moda").build(),
                Category.builder().id(vacia).slug("hogar").build()));

        List<Category> conProductos = useCase.findWithProducts();

        assertThat(conProductos).extracting(Category::getId).containsExactly(llena);
    }

    @Test
    void sinFiltroDeProductosLaPaginacionLaHaceLaBaseDeDatos() {
        UUID id = UUID.randomUUID();
        stubConteoAgrupado(id, 4L);
        Pageable pageable = PageRequest.of(0, 10);
        Page<Category> page = new PageImpl<>(List.of(Category.builder().id(id).slug("moda").build()), pageable, 1);
        when(categoryRepository.search("mod", pageable)).thenReturn(page);

        Page<Category> result = useCase.findAllPaged("mod", null, pageable);

        assertThat(result.getContent()).singleElement().extracting(Category::getProductCount).isEqualTo(4L);
        // El camino eficiente NO debe pedir las 100.000 filas para filtrar en memoria.
        verify(categoryRepository, never()).search("mod", PageRequest.of(0, 100_000));
    }

    @Test
    void elFiltroConProductosPaginaSobreElResultadoYaFiltrado() {
        UUID llena1 = UUID.randomUUID();
        UUID llena2 = UUID.randomUUID();
        UUID vacia = UUID.randomUUID();
        stubConteoAgrupado(List.of(new Object[]{llena1, 5L}, new Object[]{llena2, 9L}));
        when(categoryRepository.search(eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(Category.builder().id(llena1).build(),
                        Category.builder().id(vacia).build(), Category.builder().id(llena2).build())));

        Page<Category> result = useCase.findAllPaged(null, true, PageRequest.of(1, 1));

        // Total = las que superan el filtro (2), no las 3 leídas; la página 1 de tamaño 1 es la segunda.
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(Category::getId).containsExactly(llena2);
    }

    @Test
    void elFiltroDeVaciasDevuelveSoloLasQueNoTienenNingunProducto() {
        UUID llena = UUID.randomUUID();
        UUID vacia = UUID.randomUUID();
        stubConteoAgrupado(llena, 5L);
        when(categoryRepository.search(eq(null), any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(Category.builder().id(llena).build(), Category.builder().id(vacia).build())));

        Page<Category> result = useCase.findAllPaged(null, false, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Category::getId).containsExactly(vacia);
    }

    @Test
    void unaPaginaMasAllaDelFinalDevuelveVacioEnVezDeReventar() {
        UUID llena = UUID.randomUUID();
        stubConteoAgrupado(llena, 5L);
        when(categoryRepository.search(eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(Category.builder().id(llena).build())));

        Page<Category> result = useCase.findAllPaged(null, true, PageRequest.of(9, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    /* ---------- activación en lote ---------- */

    @Test
    void elLoteVacioNoTocaNadaYDevuelveCero() {
        assertThat(useCase.setActiveBulk(null, true)).isZero();
        assertThat(useCase.setActiveBulk(List.of(), true)).isZero();
        verify(categoryRepository, never()).update(any());
    }

    @Test
    void elLoteSoloCuentaYReindexaLasCategoriasQueDeVerdadCambian() {
        UUID yaActiva = UUID.randomUUID();
        UUID inactiva = UUID.randomUUID();
        Category activa = Category.builder().id(yaActiva).active(true).build();
        Category apagada = Category.builder().id(inactiva).active(false).build();
        when(categoryRepository.getById(yaActiva)).thenReturn(activa);
        when(categoryRepository.getById(inactiva)).thenReturn(apagada);
        when(categoryRepository.update(apagada)).thenReturn(apagada);

        int updated = useCase.setActiveBulk(List.of(yaActiva, inactiva), true);

        assertThat(updated).isEqualTo(1);
        assertThat(apagada.getActive()).isTrue();
        verify(categoryRepository, never()).update(activa);
        verify(categoryIndexer).indexCategory(inactiva);
        verify(categoryIndexer, never()).indexCategory(yaActiva);
    }

    @Test
    void unaCategoriaSinEstadoSeConsideraDistintaDeActivaYSeEnciende() {
        // active == null (fila antigua) no puede confundirse con "ya está activa" y quedarse sin encender.
        UUID id = UUID.randomUUID();
        Category sinEstado = Category.builder().id(id).active(null).build();
        when(categoryRepository.getById(id)).thenReturn(sinEstado);
        when(categoryRepository.update(sinEstado)).thenReturn(sinEstado);

        assertThat(useCase.setActiveBulk(List.of(id), true)).isEqualTo(1);
        assertThat(sinEstado.getActive()).isTrue();
    }

    /* ---------- borrado protegido ---------- */

    @Test
    void noSeBorraUnaCategoriaQueTodaviaTieneProductos() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.getById(id)).thenReturn(Category.builder().id(id).slug("moda").build());
        stubConteoSimple(id, 12L);

        assertThatThrownBy(() -> useCase.delete(id)).isInstanceOf(BusinessException.class).hasMessageContaining("12");
        verify(categoryRepository, never()).delete(id);
    }

    @Test
    void noSeBorraUnaCategoriaDeLaQueCuelganSubcategorias() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.getById(id)).thenReturn(Category.builder().id(id).slug("moda").build());
        stubConteoSimple(id, 0L, 3L);

        assertThatThrownBy(() -> useCase.delete(id)).isInstanceOf(BusinessException.class).hasMessageContaining("3");
        verify(categoryRepository, never()).delete(id);
    }

    @Test
    void unaCategoriaLibreSeBorraYDesaparaceDelIndice() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.getById(id)).thenReturn(Category.builder().id(id).slug("moda").build());
        stubConteoSimple(id, 0L, 0L);

        useCase.delete(id);

        verify(categoryRepository).delete(id);
        // Si no se quita del índice, la categoría borrada seguiría apareciendo en el escaparate.
        verify(categoryIndexer).deleteFromIndex(id);
    }

    /* ---------- actualización ---------- */

    @Test
    void unaCategoriaNoPuedeSerPadreDeSiMisma() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.getById(id)).thenReturn(Category.builder().id(id).slug("moda").build());
        Category patch = Category.builder().slug("moda").parentId(id).build();

        assertThatThrownBy(() -> useCase.update(patch, id)).isInstanceOf(BusinessException.class);
        verify(categoryRepository, never()).update(any());
    }

    @Test
    void noSePuedeColgarUnaCategoriaDeUnPadreInexistente() {
        UUID id = UUID.randomUUID();
        UUID padre = UUID.randomUUID();
        when(categoryRepository.getById(id)).thenReturn(Category.builder().id(id).slug("moda").build());
        when(categoryRepository.existsById(padre)).thenReturn(false);
        Category patch = Category.builder().slug("moda").parentId(padre).build();

        assertThatThrownBy(() -> useCase.update(patch, id)).isInstanceOf(NotFoundException.class);
        verify(categoryRepository, never()).update(any());
    }

    @Test
    void guardarSinPadreDevuelveLaCategoriaALaRaiz() {
        UUID id = UUID.randomUUID();
        Category existing = Category.builder().id(id).slug("moda").parentId(UUID.randomUUID()).build();
        when(categoryRepository.getById(id)).thenReturn(existing);
        when(categoryRepository.update(existing)).thenReturn(existing);
        stubConteoSimple(id, 6L);

        Category saved = useCase.update(Category.builder().slug("moda").build(), id);

        assertThat(existing.getParentId()).isNull();
        assertThat(saved.getProductCount()).isEqualTo(6L);
        verify(categoryIndexer).indexCategory(id);
    }

    @Test
    void cambiarElSlugAUnoLibreLoAplicaYReindexa() {
        UUID id = UUID.randomUUID();
        Category existing = Category.builder().id(id).slug("moda").build();
        when(categoryRepository.getById(id)).thenReturn(existing);
        when(categoryRepository.findBySlug("moda-mujer")).thenReturn(Optional.empty());
        when(categoryRepository.update(existing)).thenReturn(existing);
        stubConteoSimple(id, 0L);
        Category patch = Category.builder().slug("moda-mujer").build();

        useCase.update(patch, id);

        assertThat(existing.getSlug()).isEqualTo("moda-mujer");
        verify(categoryUpdateMapper).updateFromModel(patch, existing);
        verify(categoryIndexer).indexCategory(id);
    }

    @Test
    void reusarElPropioSlugNoSeConsideraColision() {
        UUID id = UUID.randomUUID();
        Category existing = Category.builder().id(id).slug("moda").build();
        when(categoryRepository.getById(id)).thenReturn(existing);
        when(categoryRepository.update(existing)).thenReturn(existing);
        stubConteoSimple(id, 0L);

        useCase.update(Category.builder().slug("moda").build(), id);

        // El slug no cambió: ni siquiera hace falta comprobar unicidad.
        verify(categoryRepository, never()).findBySlug(anyString());
    }

    /* ---------- utilidades ---------- */

    private static IndexedCategory indexada(UUID id, String slug) {
        return new IndexedCategory(id, slug, "时尚", "Moda", "Fashion", "Moda pt", "shirt", 1, true, null);
    }

    /** Stub del GROUP BY que resuelve el número de productos por categoría. */
    private void stubConteoAgrupado(UUID id, long count) {
        stubConteoAgrupado(List.<Object[]>of(new Object[]{id, count}));
    }

    private void stubConteoAgrupado(List<Object[]> rows) {
        Query query = mock(Query.class);
        when(em.createQuery(contains("GROUP BY"))).thenReturn(query);
        doReturn(rows).when(query).getResultList();
    }

    /** Stub del COUNT por categoría; se pueden encadenar varios resultados (productos, hijas). */
    @SuppressWarnings("unchecked")
    private void stubConteoSimple(UUID id, Long first, Long... rest) {
        TypedQuery<Long> typed = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(typed);
        when(typed.setParameter("id", id)).thenReturn(typed);
        when(typed.getSingleResult()).thenReturn(first, rest);
    }
}
