package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService;
import com.nexaplatform.dropshipping.application.service.WelcomeExamplesService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WelcomeExampleSettingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WelcomeExampleSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los tres productos con los que la guía de bienvenida enseña a comprar en la Unión Europea.
 *
 * <p>Lo que se fija aquí no es un detalle de presentación: si los tres ejemplos cayeran en la misma
 * partida arancelaria, al añadirlos el arancel nunca subiría y el visitante se llevaría la idea
 * equivocada —que da igual mezclar artículos—. Hacen falta DOS que compartan partida y UNO de otra.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WelcomeExamplesServiceTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    WelcomeExampleSettingRepository settingRepository;
    @Mock
    CustomsDeclarationGroupService declarationGroups;

    @InjectMocks
    WelcomeExamplesService service;

    private ProductEntity producto(String hs, String descripcion) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setHsCode(hs);
        p.setCountryOfOrigin("CN");
        p.setStatus(ProductStatus.ACTIVE);
        when(declarationGroups.describeFor(p)).thenReturn(descripcion);
        return p;
    }

    private void sinAjusteManual() {
        when(settingRepository.findById((short) 1)).thenReturn(Optional.empty());
    }

    @Test
    void eligeDosDeLaMismaPartidaYUnoDeOtra() {
        sinAjusteManual();
        ProductEntity camiseta1 = producto("610990", "Knitted polyester and cotton");
        ProductEntity camiseta2 = producto("610990", "Knitted polyester and cotton");
        ProductEntity camiseta3 = producto("610990", "Knitted polyester and cotton");
        ProductEntity zapatilla = producto("640411", "Textile upper and rubber sole");
        when(productRepository.findWelcomeExampleCandidates(any(), any(Pageable.class)))
                .thenReturn(List.of(camiseta1, camiseta2, camiseta3, zapatilla));

        List<ProductEntity> elegidos = service.examples();

        assertThat(elegidos).hasSize(3);
        // Dos de la partida más poblada...
        assertThat(service.dutyGroupOf(elegidos.get(0))).isEqualTo(service.dutyGroupOf(elegidos.get(1)));
        // ...y el tercero de otra: es el que hace subir el arancel al añadirlo, que es la mitad de la lección.
        assertThat(service.dutyGroupOf(elegidos.get(2))).isNotEqualTo(service.dutyGroupOf(elegidos.get(0)));
    }

    /**
     * La pareja sale de la partida MÁS POBLADA del catálogo, no de la primera que aparezca: es la que el
     * comprador se va a encontrar de verdad al navegar.
     */
    @Test
    void laParejaSaleDeLaPartidaConMasProductos() {
        sinAjusteManual();
        ProductEntity raro = producto("420222", "Synthetic leather");
        ProductEntity comun1 = producto("620443", "Polyester woven fabric");
        ProductEntity comun2 = producto("620443", "Polyester woven fabric");
        // El raro va primero en la lista; aun así la pareja tiene que formarse con los comunes.
        when(productRepository.findWelcomeExampleCandidates(any(), any(Pageable.class)))
                .thenReturn(List.of(raro, comun1, comun2));

        List<ProductEntity> elegidos = service.examples();

        assertThat(service.dutyGroupOf(elegidos.get(0))).contains("620443");
        assertThat(service.dutyGroupOf(elegidos.get(1))).contains("620443");
        assertThat(service.dutyGroupOf(elegidos.get(2))).contains("420222");
    }

    @Test
    void loQueFijaElAdminMandaSobreLaEleccionAutomatica() {
        ProductEntity a = producto("610990", "Knitted");
        ProductEntity b = producto("610990", "Knitted");
        ProductEntity c = producto("640411", "Textile");
        WelcomeExampleSettingEntity ajuste = new WelcomeExampleSettingEntity();
        ajuste.setId((short) 1);
        ajuste.setProductId1(a.getId());
        ajuste.setProductId2(b.getId());
        ajuste.setProductId3(c.getId());
        when(settingRepository.findById((short) 1)).thenReturn(Optional.of(ajuste));
        when(productRepository.findAllById(anyList())).thenReturn(List.of(c, a, b));

        List<ProductEntity> elegidos = service.examples();

        // En el orden que grabó el admin, no en el que los devuelva la consulta.
        assertThat(elegidos).containsExactly(a, b, c);
    }

    /**
     * Un ejemplo que ya no se puede comprar es peor que ninguno: se vuelve a la elección automática en
     * bloque, porque quedarse con dos de tres rompería el emparejamiento de partidas que el admin montó.
     */
    @Test
    void siUnFijadoDejaDeEstarDisponibleSeVuelveALaAutomatica() {
        ProductEntity a = producto("610990", "Knitted");
        ProductEntity b = producto("610990", "Knitted");
        ProductEntity automatico1 = producto("620443", "Woven");
        ProductEntity automatico2 = producto("620443", "Woven");
        ProductEntity automatico3 = producto("420222", "Leather");
        WelcomeExampleSettingEntity ajuste = new WelcomeExampleSettingEntity();
        ajuste.setId((short) 1);
        ajuste.setProductId1(a.getId());
        ajuste.setProductId2(b.getId());
        ajuste.setProductId3(UUID.randomUUID()); // este ya no existe
        when(settingRepository.findById((short) 1)).thenReturn(Optional.of(ajuste));
        when(productRepository.findAllById(anyList())).thenReturn(List.of(a, b));
        when(productRepository.findWelcomeExampleCandidates(any(), any(Pageable.class)))
                .thenReturn(List.of(automatico1, automatico2, automatico3));

        List<ProductEntity> elegidos = service.examples();

        assertThat(elegidos).containsExactly(automatico1, automatico2, automatico3);
    }

    /** Un producto retirado tampoco vale aunque siga en la tabla: la guía solo enseña lo comprable. */
    @Test
    void unFijadoQueYaNoEstaActivoNoSeUsa() {
        ProductEntity retirado = producto("610990", "Knitted");
        retirado.setStatus(ProductStatus.DRAFT);
        WelcomeExampleSettingEntity ajuste = new WelcomeExampleSettingEntity();
        ajuste.setId((short) 1);
        ajuste.setProductId1(retirado.getId());
        when(settingRepository.findById((short) 1)).thenReturn(Optional.of(ajuste));
        when(productRepository.findAllById(anyList())).thenReturn(List.of(retirado));
        ProductEntity vivo = producto("620443", "Woven");
        when(productRepository.findWelcomeExampleCandidates(any(), any(Pageable.class))).thenReturn(List.of(vivo));

        assertThat(service.examples()).containsExactly(vivo);
    }

    /** Sin catálogo declarable no hay ejemplos, pero tampoco excepción: la guía sabe pintar menos de tres. */
    @Test
    void sinCandidatosDevuelveVacioSinReventar() {
        sinAjusteManual();
        when(productRepository.findWelcomeExampleCandidates(any(), any(Pageable.class))).thenReturn(List.of());

        assertThat(service.examples()).isEmpty();
    }

    @Test
    void fijarConListaVaciaDevuelveElControlALaEleccionAutomatica() {
        when(settingRepository.findById((short) 1)).thenReturn(Optional.empty());

        service.fijar(List.of(), "admin@nx036.local");

        ArgumentCaptor<WelcomeExampleSettingEntity> captor = ArgumentCaptor.forClass(WelcomeExampleSettingEntity.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getProductId1()).isNull();
        assertThat(captor.getValue().getProductId2()).isNull();
        assertThat(captor.getValue().getProductId3()).isNull();
    }
}
