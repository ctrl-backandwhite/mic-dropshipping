package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * De dónde sale la descripción con la que se declara un producto en la aduana.
 *
 * <p>El derecho de 3 EUR se cobra por LÍNEA de declaración, y lo que separa una línea de otra es la
 * terna clasificación + descripción + origen. Hasta ahora cada producto viajaba con su propio título,
 * así que dos productos distintos eran siempre dos líneas aunque compartieran partida: unas zapatillas
 * y unos boxers pagaban 5,99 EUR sobre 9,90 EUR de mercancía.
 *
 * <p>Lo que se prueba aquí es <b>cuándo se agrupa y cuándo no</b>, que es dinero en las dos direcciones:
 * agrupar sin permiso cobraría de menos y la diferencia la pondría el comercio al despachar; no agrupar
 * cuando se debe cobra de más al cliente.
 */
@ExtendWith(MockitoExtension.class)
class CustomsDeclarationGroupServiceTest {

    @Mock
    CustomsDeclarationGroupRepository groupRepository;

    @InjectMocks
    CustomsDeclarationGroupService service;

    @Test
    void usaLaDescripcionDelGrupoCuandoEstaAprobado() {
        ProductEntity p = productoCon("620443", "Cotton", "Casual wear", "Blue denim jeans");
        when(groupRepository.findByHs6AndMaterialAndUsageCode("620443", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.of(CustomsDeclarationGroupEntity.builder()
                        .ename("Men's woven cotton trousers").approvedAt(Instant.now()).build()));

        assertThat(service.describeFor(p)).isEqualTo("Men's woven cotton trousers");
    }

    @Test
    void sinAprobarSigueUsandoElTituloDelProducto() {
        // Un grupo sin aprobar NO agrupa: cada producto es su propia línea y se cobra de más, nunca de
        // menos. Es la salvaguarda que impide que cargar productos abarate el arancel por accidente.
        ProductEntity p = productoCon("620443", "Cotton", "Casual wear", "Blue denim jeans");
        when(groupRepository.findByHs6AndMaterialAndUsageCode("620443", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.of(CustomsDeclarationGroupEntity.builder()
                        .ename("Men's woven cotton trousers").approvedAt(null).build()));

        assertThat(service.describeFor(p)).isEqualTo("Blue denim jeans");
    }

    @Test
    void sinGrupoUsaElTituloDelProducto() {
        ProductEntity p = productoCon("620443", "Cotton", "Casual wear", "Blue denim jeans");
        when(groupRepository.findByHs6AndMaterialAndUsageCode(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.describeFor(p)).isEqualTo("Blue denim jeans");
    }

    @Test
    void sinPartidaValidaNiSiquieraConsultaElGrupo() {
        // Sin código HS no se agrupa con nadie: agruparlo sería atribuirle una clasificación que nadie
        // ha verificado, y de eso responde el declarante ante la aduana.
        ProductEntity p = productoCon("62", "Cotton", "Casual wear", "Blue denim jeans");

        assertThat(service.describeFor(p)).isEqualTo("Blue denim jeans");
        verifyNoInteractions(groupRepository);
    }

    @Test
    void laTernaSeNormalizaAntesDeBuscar() {
        // «Cotton» y «  cotton » son el mismo material descrito por dos personas distintas. Tratarlos
        // como grupos separados partiría en dos un grupo que la aduana cuenta como uno.
        ProductEntity p = productoCon("6204431234", "  cotton ", "casual  wear", "Blue denim jeans");
        when(groupRepository.findByHs6AndMaterialAndUsageCode("620443", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.of(CustomsDeclarationGroupEntity.builder()
                        .ename("Men's woven cotton trousers").approvedAt(Instant.now()).build()));

        assertThat(service.describeFor(p)).isEqualTo("Men's woven cotton trousers");
    }

    private static ProductEntity productoCon(String hs, String material, String uso, String tituloEn) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setHsCode(hs);
        p.setCountryOfOrigin("CN");
        p.setCustomsMaterial(material);
        p.setCustomsUsage(uso);
        if (tituloEn != null) {
            ProductTranslationEntity en = new ProductTranslationEntity();
            en.setLanguage("en");
            en.setTitle(tituloEn);
            p.setTranslations(List.of(en));
        }
        return p;
    }
}
