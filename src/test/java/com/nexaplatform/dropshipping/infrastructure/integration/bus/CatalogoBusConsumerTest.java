package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que este entorno hace con el catálogo que le llega por el bus.
 *
 * <p>La regla que gobierna todo el fichero: <b>ante la duda, no confirmar el mensaje</b>. Un
 * mensaje sin confirmar se reintenta y como mucho cuesta tiempo; uno confirmado por error es un
 * producto que no llega nunca y del que nadie se entera hasta echarlo en falta.
 */
@DisplayName("Recepción del catálogo desde el bus")
class CatalogoBusConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private CatalogUseCase catalogo;
    private com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository categorias;
    private ProductRepository productos;
    private CatalogoBusConsumer consumidor;

    @BeforeEach
    void setUp() {
        catalogo = mock(CatalogUseCase.class);
        categorias = mock(com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository.class);
        productos = mock(ProductRepository.class);
        consumidor = new CatalogoBusConsumer(catalogo, categorias, productos);
    }

    @Test
    @DisplayName("Una categoría raíz se aplica con su código y sus idiomas")
    void categoriaRaiz() {
        consumidor.recibirCategoria(json(
                CategoriaPublicada.de("moda-mujer", Map.of("es", "Mujer", "en", "Women", "zh", "女装"), null, true)));

        ArgumentCaptor<IngestCategoryRequest> captor = ArgumentCaptor.captor();
        verify(catalogo).upsertCategory(captor.capture());
        IngestCategoryRequest req = captor.getValue();
        assertThat(req.slug()).isEqualTo("moda-mujer");
        assertThat(req.parentId()).isNull();
        assertThat(req.nameZh()).isEqualTo("女装");
        // El chino va en su propio campo; repetirlo entre las traducciones crearía una fila de
        // traducción al chino que el escaparate no espera.
        assertThat(req.nameTranslations()).containsEntry("es", "Mujer").doesNotContainKey("zh");
    }

    @Test
    @DisplayName("El padre se resuelve por su CÓDIGO, porque el identificador de origen no existe aquí")
    void resuelveElPadrePorCodigo() {
        UUID idPadre = UUID.randomUUID();
        CategoryEntity padre = CategoryEntity.builder().slug("moda-mujer").build();
        padre.setId(idPadre);
        when(categorias.findBySlug("moda-mujer")).thenReturn(Optional.of(padre));

        consumidor.recibirCategoria(
                json(CategoriaPublicada.de("moda-mujer-abrigos", Map.of("es", "Abrigos"), "moda-mujer", true)));

        ArgumentCaptor<IngestCategoryRequest> captor = ArgumentCaptor.captor();
        verify(catalogo).upsertCategory(captor.capture());
        assertThat(captor.getValue().parentId()).isEqualTo(idPadre);
    }

    @Test
    @DisplayName("Si el padre aún no ha llegado se reintenta, en vez de colgarla de la raíz")
    void sinPadreSeReintenta() {
        // Crearla sin padre dejaría la rama colgando de la raíz del escaparate, y el árbol torcido
        // no da ningún error: simplemente queda mal, y nadie lo ve hasta que alguien navega.
        when(categorias.findBySlug("moda-mujer")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consumidor.recibirCategoria(
                json(CategoriaPublicada.de("moda-mujer-abrigos", Map.of("es", "Abrigos"), "moda-mujer", true))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("moda-mujer");
        verify(catalogo, never()).upsertCategory(any());
    }

    @Test
    @DisplayName("El producto se aplica con el importador de siempre, que ya espeja las imágenes")
    void productoSeAplicaConElImportador() {
        when(catalogo.bulkCreateProducts(anyList())).thenReturn(new BulkResultDtoOut(1, 0, List.of()));

        consumidor.recibirProducto(json(ProductoCertificado.de(ficha())));

        ArgumentCaptor<List<BulkProductDtoIn>> captor = ArgumentCaptor.captor();
        verify(catalogo).bulkCreateProducts(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getExternalId()).isEqualTo("1688-987");
    }

    @Test
    @DisplayName("Si el producto no entra, NO se da por bueno: se reintenta")
    void productoFallidoSeReintenta() {
        // Confirmarlo lo perdería para siempre, sin más rastro que una línea de registro.
        when(catalogo.bulkCreateProducts(anyList()))
                .thenReturn(new BulkResultDtoOut(0, 1, List.of("categoría desconocida")));

        assertThatThrownBy(() -> consumidor.recibirProducto(json(ProductoCertificado.de(ficha()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1688-987");
    }

    @Test
    @DisplayName("Un producto retirado se PAUSA, nunca se borra")
    void retiradaPausaEnVezDeBorrar() {
        // Borrarlo se llevaría por delante el historial de los pedidos que ya lo compraron.
        UUID id = UUID.randomUUID();
        ProductEntity p = ProductEntity.builder().externalId("1688-987").build();
        p.setId(id);
        when(productos.findFirstByExternalId("1688-987")).thenReturn(Optional.of(p));

        consumidor.recibirRetirada(json(ProductoRetirado.de("1688-987", "abrigo", "prohibido")));

        verify(catalogo).updateStatus(id, ProductStatus.PAUSED);
    }

    @Test
    @DisplayName("Retirar algo que aquí nunca existió no es un error")
    void retiradaDeProductoDesconocido() {
        when(productos.findFirstByExternalId("1688-000")).thenReturn(Optional.empty());

        consumidor.recibirRetirada(json(ProductoRetirado.de("1688-000", "nada", "prohibido")));

        verify(catalogo, never()).updateStatus(any(UUID.class), any(ProductStatus.class));
    }

    @Test
    @DisplayName("Un mensaje ilegible se registra entero y no se da por procesado")
    void mensajeIlegible() {
        assertThatThrownBy(() -> consumidor.recibirProducto("no soy json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BulkProductDtoIn ficha() {
        BulkProductDtoIn d = new BulkProductDtoIn();
        d.setExternalId("1688-987");
        d.setTitleEs("Abrigo de lana");
        d.setCategorySlug("moda-mujer-abrigos");
        return d;
    }

    /** Serializa el evento como viaja de verdad por el bus: texto plano. */
    private String json(Object evento) {
        try {
            return MAPPER.writeValueAsString(evento);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
