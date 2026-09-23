package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.CustomsAuditView;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.usecase.impl.CatalogUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El catálogo es el sitio barato de arreglar esto.
 *
 * <p>Un producto al que le falta un dato de aduana no puede darse por listo para vender: si entra al
 * escaparate, el fallo no aparece hasta que alguien lo compra y hay que despachar su pedido, y entonces
 * ya hay dinero cobrado de por medio. Publicar es una acción deliberada del administrador, así que es el
 * momento natural para exigirle los datos.
 *
 * <p>Y como revisar miles de referencias de una en una no es viable, hay además una consulta que
 * enumera de golpe lo que le falta a cada producto.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogDatosAduanerosTest {

    @Mock
    ProductRepository productJpaRepository;
    @Mock
    ProductIndexer productIndexer;

    @InjectMocks
    CatalogUseCaseImpl useCase;

    private static ProductTranslationEntity traduccion(String idioma, String titulo) {
        ProductTranslationEntity t = new ProductTranslationEntity();
        t.setLanguage(idioma);
        t.setTitle(titulo);
        return t;
    }

    /** Producto con todo lo obligatorio; cada test le quita el dato que quiere provocar. */
    private static ProductEntity productoCompleto() {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("OFFER-1").titleZh("男士石英手表")
                .slug("reloj-offer-1").status(ProductStatus.DRAFT).moq(1).basePrice(new BigDecimal("10"))
                .currency("CNY").build();
        p.setId(UUID.randomUUID());
        p.setHsCode("9102190000");
        p.setWeightGrams(300);
        p.setTranslations(new ArrayList<>(List.of(traduccion("en", "Men's quartz watch"), traduccion("zh", "男士石英手表"))));
        return p;
    }

    /* ============ no se publica sin los datos ============ */

    @Test
    @DisplayName("no se puede publicar un producto sin partida arancelaria")
    void noSePuedePublicarUnProductoSinPartidaArancelaria() {
        ProductEntity p = productoCompleto();
        p.setHsCode(null);
        when(productJpaRepository.findById(p.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> useCase.updateStatus(p.getId(), ProductStatus.ACTIVE))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INCOMPLETE_CUSTOMS_DATA");
                    assertThat(ex.getMessage()).contains("partida arancelaria (HSCode)");
                });
    }

    @Test
    @DisplayName("el producto rechazado no se guarda ni se indexa con el estado nuevo")
    void elProductoRechazadoNoSeGuardaNiSeIndexa() {
        ProductEntity p = productoCompleto();
        p.setWeightGrams(null);
        when(productJpaRepository.findById(p.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> useCase.updateStatus(p.getId(), ProductStatus.ACTIVE))
                .isInstanceOf(BusinessException.class);

        assertThat(p.getStatus()).isEqualTo(ProductStatus.DRAFT);
        verify(productJpaRepository, never()).save(any(ProductEntity.class));
        verify(productIndexer, never()).indexProduct(any(UUID.class));
    }

    @Test
    @DisplayName("despublicar o archivar sigue siendo posible aunque falten datos")
    void despublicarSigueSiendoPosibleAunqueFaltenDatos() {
        // Retirar del escaparate un producto defectuoso es justo lo que hay que poder hacer siempre; el
        // control es para ENTRAR a la venta, no para salir.
        ProductEntity p = productoCompleto();
        p.setHsCode(null);
        p.setStatus(ProductStatus.ACTIVE);
        when(productJpaRepository.findById(p.getId())).thenReturn(Optional.of(p));

        assertThatCode(() -> useCase.updateStatus(p.getId(), ProductStatus.PAUSED)).doesNotThrowAnyException();

        assertThat(p.getStatus()).isEqualTo(ProductStatus.PAUSED);
    }

    @Test
    @DisplayName("con los datos completos el producto se publica")
    void conLosDatosCompletosElProductoSePublica() {
        ProductEntity p = productoCompleto();
        when(productJpaRepository.findById(p.getId())).thenReturn(Optional.of(p));

        assertThatCode(() -> useCase.updateStatus(p.getId(), ProductStatus.ACTIVE)).doesNotThrowAnyException();

        assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    /* ============ consulta de catálogo incompleto ============ */

    @Test
    @DisplayName("la auditoría enumera cada producto incompleto con los datos que le faltan")
    void laAuditoriaEnumeraCadaProductoIncompletoConLoQueLeFalta() {
        ProductEntity completo = productoCompleto();
        ProductEntity incompleto = productoCompleto();
        incompleto.setHsCode(null);
        incompleto.setWeightGrams(null);
        prepararAuditoria(List.of(completo, incompleto));

        CustomsAuditView vista = useCase.auditCustomsData("ACTIVE", 100);

        assertThat(vista.scanned()).isEqualTo(2);
        assertThat(vista.incomplete()).isEqualTo(1);
        assertThat(vista.products()).singleElement().satisfies(fila -> {
            assertThat(fila.id()).isEqualTo(incompleto.getId());
            assertThat(fila.externalId()).isEqualTo("OFFER-1");
            assertThat(fila.missing()).containsExactlyInAnyOrder("partida arancelaria (HSCode)",
                    "peso unitario (UnitWeight)");
        });
    }

    @Test
    @DisplayName("un catálogo completo devuelve la lista vacía")
    void unCatalogoCompletoDevuelveListaVacia() {
        prepararAuditoria(List.of(productoCompleto(), productoCompleto()));

        CustomsAuditView vista = useCase.auditCustomsData("ACTIVE", 100);

        assertThat(vista.incomplete()).isZero();
        assertThat(vista.products()).isEmpty();
        assertThat(vista.truncated()).isFalse();
    }

    @Test
    @DisplayName("la auditoría se corta al tope pedido y lo dice, en vez de devolver miles de filas")
    void laAuditoriaSeCortaAlTopePedidoYLoDice() {
        ProductEntity a = productoCompleto();
        a.setHsCode(null);
        ProductEntity b = productoCompleto();
        b.setHsCode(null);
        prepararAuditoria(List.of(a, b));

        CustomsAuditView vista = useCase.auditCustomsData("ACTIVE", 1);

        assertThat(vista.products()).hasSize(1);
        assertThat(vista.truncated()).isTrue();
    }

    /** Deja el repositorio devolviendo esos productos como única página del catálogo auditado. */
    private void prepararAuditoria(List<ProductEntity> productos) {
        List<UUID> ids = productos.stream().map(ProductEntity::getId).toList();
        Page<UUID> pagina = new PageImpl<>(ids);
        when(productJpaRepository.findIdsForCustomsAudit(any(), any(Pageable.class))).thenReturn(pagina);
        when(productJpaRepository.findWithTranslationsByIds(anyList())).thenReturn(productos);
        when(productJpaRepository.findWithVariantsByIds(anyList())).thenReturn(productos);
    }
}
