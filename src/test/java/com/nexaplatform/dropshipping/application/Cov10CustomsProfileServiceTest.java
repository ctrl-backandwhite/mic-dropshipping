package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryCustomsProfileEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryCustomsProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de la siembra aduanera: sin partida arancelaria ni medidas de paquete el transportista no puede
 * cotizar ni declarar el envío, y un {@code batteryType} nulo rompe la inserción (columna obligatoria).
 */
@ExtendWith(MockitoExtension.class)
class Cov10CustomsProfileServiceTest {

    @Mock
    CategoryCustomsProfileRepository profileRepository;

    @InjectMocks
    CustomsProfileService service;

    @Test
    void slugVacioNoConsultaLaTablaDePerfiles() {
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("   ")).isEmpty();
        verify(profileRepository, never()).findByCategorySlug(anyString());
    }

    @Test
    void elPerfilPropioDeLaCategoriaGanaAlDeSuFamilia() {
        CategoryCustomsProfileEntity propio = CategoryCustomsProfileEntity.builder().categorySlug("moda-muj-01")
                .hsCode("6104.43").build();
        when(profileRepository.findByCategorySlug("moda-muj-01")).thenReturn(Optional.of(propio));

        assertThat(service.resolve(" moda-muj-01 ")).contains(propio);
        // Si consultara además la familia, un perfil genérico podría acabar pisando al específico.
        verify(profileRepository, never()).findByCategorySlug("moda-muj-*");
    }

    @Test
    void sinPerfilPropioSeHeredaElDeLaFamilia() {
        CategoryCustomsProfileEntity familia = CategoryCustomsProfileEntity.builder().categorySlug("moda-muj-*")
                .hsCode("6104.43").build();
        when(profileRepository.findByCategorySlug("moda-muj-01")).thenReturn(Optional.empty());
        when(profileRepository.findByCategorySlug("moda-muj-*")).thenReturn(Optional.of(familia));

        assertThat(service.resolve("moda-muj-01")).contains(familia);
    }

    @Test
    void unSlugSinDosTramosNoTieneFamiliaQueHeredar() {
        when(profileRepository.findByCategorySlug("moda")).thenReturn(Optional.empty());

        assertThat(service.resolve("moda")).isEmpty();
        verify(profileRepository, never()).findByCategorySlug("moda-*");
    }

    @Test
    void sinPerfilElProductoQuedaConBateriaNoneYSinTocarNadaMas() {
        ProductEntity product = new ProductEntity();
        product.setBatteryType(null);
        product.setExternalId("1688-1");
        when(profileRepository.findByCategorySlug("rara")).thenReturn(Optional.empty());

        boolean touched = service.applyDefaults(product, "rara");

        assertThat(touched).isFalse();
        // battery_type es NOT NULL: dejarlo nulo reventaría la inserción del producto.
        assertThat(product.getBatteryType()).isEqualTo("NONE");
        assertThat(product.getHsCode()).isNull();
    }

    @Test
    void elPerfilSoloRellenaHuecosYNuncaPisaLoQueTraeLaCarga() {
        ProductEntity product = ProductEntity.builder().hsCode("9999.99").customsMaterial("Cotton")
                .customsUsage("Clothing").batteryType("BUILT_IN").lengthMm(100).widthMm(200).heightMm(300).build();
        when(profileRepository.findByCategorySlug("moda-muj-01")).thenReturn(Optional.of(perfilCompleto()));

        boolean touched = service.applyDefaults(product, "moda-muj-01");

        assertThat(touched).isFalse();
        assertThat(product.getHsCode()).isEqualTo("9999.99");
        assertThat(product.getCustomsMaterial()).isEqualTo("Cotton");
        assertThat(product.getCustomsUsage()).isEqualTo("Clothing");
        assertThat(product.getBatteryType()).isEqualTo("BUILT_IN");
        assertThat(product.getLengthMm()).isEqualTo(100);
    }

    @Test
    void elPerfilCompletaAduanaYMedidasDePaqueteALaVez() {
        ProductEntity product = new ProductEntity();
        when(profileRepository.findByCategorySlug("moda-muj-01")).thenReturn(Optional.of(perfilCompleto()));

        boolean touched = service.applyDefaults(product, "moda-muj-01");

        assertThat(touched).isTrue();
        assertThat(product.getHsCode()).isEqualTo("6104.43");
        assertThat(product.getCustomsMaterial()).isEqualTo("Polyester");
        assertThat(product.getCustomsUsage()).isEqualTo("Daily wear");
        assertThat(product.getBatteryType()).isEqualTo("BUILT_IN");
        assertThat(product.getLengthMm()).isEqualTo(300);
        assertThat(product.getWidthMm()).isEqualTo(200);
        assertThat(product.getHeightMm()).isEqualTo(50);
    }

    @Test
    void elEmbalajeSeRellenaAunqueLaAduanaYaEsteCompleta() {
        // La segunda mitad no puede quedar sin evaluar por un cortocircuito: sin medidas no hay tarifa.
        ProductEntity product = ProductEntity.builder().hsCode("6104.43").customsMaterial("Polyester")
                .customsUsage("Daily wear").batteryType("BUILT_IN").build();
        when(profileRepository.findByCategorySlug("moda-muj-01")).thenReturn(Optional.of(perfilCompleto()));

        boolean touched = service.applyDefaults(product, "moda-muj-01");

        assertThat(touched).isTrue();
        assertThat(product.getLengthMm()).isEqualTo(300);
        assertThat(product.getHeightMm()).isEqualTo(50);
    }

    @Test
    void unaMedidaDePerfilCeroONegativaNoSeCopia() {
        ProductEntity product = new ProductEntity();
        CategoryCustomsProfileEntity profile = CategoryCustomsProfileEntity.builder().categorySlug("moda-muj-*")
                .packLengthMm(0).packWidthMm(-5).packHeightMm(40).build();
        when(profileRepository.findByCategorySlug("moda-muj-01")).thenReturn(Optional.of(profile));

        boolean touched = service.applyDefaults(product, "moda-muj-01");

        assertThat(touched).isTrue();
        assertThat(product.getLengthMm()).isNull();
        assertThat(product.getWidthMm()).isNull();
        assertThat(product.getHeightMm()).isEqualTo(40);
    }

    @Test
    void unaBateriaNoneEnElPerfilNoDegradaLaDelProducto() {
        // "NONE" cuenta como hueco tanto en el producto como en el perfil: no hay nada que heredar.
        ProductEntity product = new ProductEntity();
        CategoryCustomsProfileEntity profile = CategoryCustomsProfileEntity.builder().categorySlug("moda-muj-*")
                .batteryType("none").build();
        when(profileRepository.findByCategorySlug("moda-muj-01")).thenReturn(Optional.of(profile));

        boolean touched = service.applyDefaults(product, "moda-muj-01");

        assertThat(touched).isFalse();
        assertThat(product.getBatteryType()).isEqualTo("NONE");
    }

    private static CategoryCustomsProfileEntity perfilCompleto() {
        return CategoryCustomsProfileEntity.builder().categorySlug("moda-muj-*").hsCode("6104.43").material("Polyester")
                .usageText("Daily wear").batteryType("BUILT_IN").packLengthMm(300).packWidthMm(200).packHeightMm(50)
                .build();
    }
}
