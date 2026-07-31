package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.PodDesignUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.PodBlankProduct;
import com.nexaplatform.dropshipping.domain.model.PodDesign;
import com.nexaplatform.dropshipping.domain.repository.PodDesignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Catálogo de "productos base" de impresión bajo demanda y propiedad de los diseños. La regla dura es
 * que el flag {@code podEnabled} NO basta: el semillero lo activa en todas las categorías y sin la
 * lista blanca se colarían artículos no imprimibles (cuchillos, cosmética, electrónica).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07PodDesignBlanksTest {

    @Mock
    PodDesignRepository podDesignRepository;
    @Mock
    ProductRepository productRepository;

    @InjectMocks
    PodDesignUseCaseImpl useCase;

    /* ==================== productos base ==================== */

    @Test
    void soloSonProductoBaseLosImprimiblesConPodActivo() {
        ProductEntity valido = product(true, "fashion-apparel");
        ProductEntity categoriaNoImprimible = product(true, "cocina");
        ProductEntity podApagado = product(false, "fashion-apparel");
        ProductEntity podNulo = product(null, "fashion-apparel");
        ProductEntity sinCategoria = product(true, null);
        when(productRepository.findAll()).thenReturn(
                List.of(valido, categoriaNoImprimible, podApagado, podNulo, sinCategoria));

        List<PodBlankProduct> blanks = useCase.blanks("es");

        assertThat(blanks).hasSize(1);
        assertThat(blanks.get(0).getId()).isEqualTo(valido.getId());
        assertThat(blanks.get(0).getSlug()).isEqualTo(valido.getSlug());
        assertThat(blanks.get(0).getPrice()).isEqualByComparingTo("9.99");
    }

    @Test
    void unaCategoriaSinSlugNoEsImprimible() {
        ProductEntity sinSlug = product(true, "fashion-apparel");
        sinSlug.getCategory().setSlug(null);
        when(productRepository.findAll()).thenReturn(List.of(sinSlug));

        assertThat(useCase.blanks("es")).isEmpty();
    }

    @Test
    void elSlugDeCategoriaSeComparaSinDistinguirMayusculas() {
        when(productRepository.findAll()).thenReturn(List.of(product(true, "FASHION-APPAREL")));

        assertThat(useCase.blanks("es")).hasSize(1);
    }

    @Test
    void elTituloDeLaTarjetaUsaLaTraduccionDelIdiomaPedido() {
        ProductEntity p = product(true, "fashion-apparel");
        p.getTranslations().add(translation("en", "Linen shirt"));
        p.getTranslations().add(translation("es", "Camisa de lino"));
        when(productRepository.findAll()).thenReturn(List.of(p));

        assertThat(useCase.blanks("es").get(0).getTitle()).isEqualTo("Camisa de lino");
    }

    @Test
    void sinTraduccionDelIdiomaSeCaeAInglesYDespuesAlChino() {
        ProductEntity soloIngles = product(true, "fashion-apparel");
        soloIngles.getTranslations().add(translation("en", "Linen shirt"));
        ProductEntity sinTraducciones = product(true, "fashion-apparel");
        when(productRepository.findAll()).thenReturn(List.of(soloIngles));
        assertThat(useCase.blanks("de").get(0).getTitle()).isEqualTo("Linen shirt");

        when(productRepository.findAll()).thenReturn(List.of(sinTraducciones));
        // Último recurso: el título chino original, antes que dejar la tarjeta sin nombre.
        assertThat(useCase.blanks("de").get(0).getTitle()).isEqualTo("亚麻衬衫");
    }

    @Test
    void unaTraduccionSinTituloNoTapaALasDemas() {
        ProductEntity p = product(true, "fashion-apparel");
        p.getTranslations().add(translation("es", null));
        p.getTranslations().add(translation("en", "Linen shirt"));
        when(productRepository.findAll()).thenReturn(List.of(p));

        assertThat(useCase.blanks("es").get(0).getTitle()).isEqualTo("Linen shirt");
    }

    @Test
    void laImagenDeLaTarjetaPrefiereElEspejoYSiNoLaOrigen() {
        ProductEntity conCdn = product(true, "fashion-apparel");
        conCdn.getImages().add(image("http://origen/1.jpg", "http://cdn/1.jpg"));
        ProductEntity soloOrigen = product(true, "fashion-apparel");
        soloOrigen.getImages().add(image("http://origen/2.jpg", "  "));
        ProductEntity sinImagenes = product(true, "fashion-apparel");
        when(productRepository.findAll()).thenReturn(List.of(conCdn, soloOrigen, sinImagenes));

        List<PodBlankProduct> blanks = useCase.blanks("es");

        assertThat(blanks.get(0).getMainImage()).isEqualTo("http://cdn/1.jpg");
        // Un cdnUrl en blanco cuenta como "no espejada": si no, la tarjeta pintaría una imagen rota.
        assertThat(blanks.get(1).getMainImage()).isEqualTo("http://origen/2.jpg");
        assertThat(blanks.get(2).getMainImage()).isNull();
    }

    /* ==================== propiedad de los diseños ==================== */

    @Test
    void nadieRenombraNiBorraUnDisenoAjeno() {
        UUID id = UUID.randomUUID();
        PodDesign ajeno = PodDesign.builder().id(id).userId(UUID.randomUUID()).name("suyo").build();
        when(podDesignRepository.getById(id)).thenReturn(ajeno);
        UUID intruso = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.renameDesign(intruso, id, "mío")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.deleteDesign(intruso, id)).isInstanceOf(NotFoundException.class);
        verify(podDesignRepository, never()).delete(id);
        verify(podDesignRepository, never()).update(ajeno);
    }

    @Test
    void unDisenoInexistenteSeTrataComoNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(podDesignRepository.getById(id)).thenReturn(null);
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.renameDesign(userId, id, "x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.deleteDesign(userId, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void elDuenoPuedeRenombrarYBorrarSuDiseno() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PodDesign propio = PodDesign.builder().id(id).userId(userId).name("viejo").build();
        when(podDesignRepository.getById(id)).thenReturn(propio);
        when(podDesignRepository.update(propio)).thenReturn(propio);

        PodDesign renamed = useCase.renameDesign(userId, id, "nuevo");
        useCase.deleteDesign(userId, id);

        assertThat(renamed.getName()).isEqualTo("nuevo");
        verify(podDesignRepository).delete(id);
    }

    @Test
    void losDisenosPropiosSeConsultanPorUsuario() {
        UUID userId = UUID.randomUUID();
        PodDesign design = PodDesign.builder().id(UUID.randomUUID()).userId(userId).build();
        when(podDesignRepository.findByUserId(userId)).thenReturn(List.of(design));

        assertThat(useCase.myDesigns(userId)).containsExactly(design);
    }

    /* ==================== mockups ==================== */

    @Test
    void elMockupEsElMismoParaElMismoNombreDeDiseno() {
        UUID productId = UUID.randomUUID();
        when(productRepository.existsById(productId)).thenReturn(true);
        when(podDesignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PodDesign uno = useCase.create(UUID.randomUUID(), PodDesign.builder().productId(productId).name("gatos").build());
        PodDesign dos = useCase.create(UUID.randomUUID(), PodDesign.builder().productId(productId).name("gatos").build());

        // Determinista a propósito: el diseño tiene que conservar su mockup entre recargas.
        assertThat(uno.getMockupUrl()).isEqualTo(dos.getMockupUrl()).startsWith("https://images.unsplash.com/");
    }

    @Test
    void laGeneracionPorIaConPromptNuloDevuelveUnPromptVacio() {
        assertThat(useCase.aiGenerate(null).getPrompt()).isEmpty();
        assertThat(useCase.aiGenerate(null).getMockupUrl()).isNotNull();
        assertThat(useCase.aiGenerate("gatos").getMockupUrl())
                .isEqualTo(useCase.aiGenerate("gatos").getMockupUrl());
    }

    /* ==================== helpers ==================== */

    private static ProductEntity product(Boolean podEnabled, String categorySlug) {
        ProductEntity p = ProductEntity.builder().slug("camisa-lino").titleZh("亚麻衬衫")
                .basePrice(new BigDecimal("9.99")).podEnabled(podEnabled).build();
        p.setId(UUID.randomUUID());
        if (categorySlug != null) {
            CategoryEntity category = new CategoryEntity();
            category.setId(UUID.randomUUID());
            category.setSlug(categorySlug);
            p.setCategory(category);
        }
        return p;
    }

    private static ProductTranslationEntity translation(String language, String title) {
        return ProductTranslationEntity.builder().language(language).title(title).build();
    }

    private static ProductImageEntity image(String sourceUrl, String cdnUrl) {
        return ProductImageEntity.builder().position(0).sourceUrl(sourceUrl).cdnUrl(cdnUrl).build();
    }
}
