package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupSync;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsTernaRow;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La siembra de los grupos de declaración: qué crea, qué actualiza y —sobre todo— qué NO toca.
 *
 * <p>Este proceso corre tras cada carga masiva de catálogo, así que se ejecuta sobre una tabla que ya
 * contiene descripciones <b>firmadas</b>: textos que una persona aprobó para declarar ante 27 aduanas.
 * Reescribir uno en silencio cambiaría lo que se declara sin que nadie lo aprobara, que es justo lo que
 * la aprobación existe para impedir. De ahí que la mitad de estas pruebas comprueben inmovilidad.
 */
@ExtendWith(MockitoExtension.class)
class CustomsDeclarationGroupSyncTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    CustomsDeclarationGroupRepository groupRepository;

    @InjectMocks
    CustomsDeclarationGroupSync sync;

    @Test
    void creaLosGruposQueFaltanYNoTocaLosAprobados() {
        // Reaprobar o reescribir un grupo ya firmado cambiaría en silencio lo que se declara en aduana.
        CustomsDeclarationGroupEntity aprobado = CustomsDeclarationGroupEntity.builder().hs6("620443")
                .material("COTTON").usageCode("CASUAL WEAR").ename("Men's woven cotton trousers").cname("男式棉制机织长裤")
                .approvedAt(Instant.now()).approvedBy("admin@nexadrop.com").productCount(1).build();
        when(productRepository.customsTernas())
                .thenReturn(List.of(new CustomsTernaRow("620443", "Cotton", "Casual wear", 728L),
                        new CustomsTernaRow("610990", "Cotton", "Casual wear", 622L)));
        when(groupRepository.findByHs6AndMaterialAndUsageCode("620443", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.of(aprobado));
        when(groupRepository.findByHs6AndMaterialAndUsageCode("610990", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.empty());

        assertThat(sync.sync()).isEqualTo(1);
        assertThat(aprobado.getEname()).isEqualTo("Men's woven cotton trousers");
        assertThat(aprobado.getCname()).isEqualTo("男式棉制机织长裤");
        assertThat(aprobado.getApprovedBy()).isEqualTo("admin@nexadrop.com");
    }

    @Test
    void elBorradorSaleDelTextoOficialDeLaPartidaYLlegaSinAprobar() {
        // El borrador es una propuesta, no una declaración: nace sin firma y por tanto sin agrupar.
        when(productRepository.customsTernas())
                .thenReturn(List.of(new CustomsTernaRow("620443", "Polyester", "Casual wear", 728L)));
        when(groupRepository.findByHs6AndMaterialAndUsageCode(any(), any(), any())).thenReturn(Optional.empty());

        sync.sync();

        CustomsDeclarationGroupEntity creado = guardado();
        assertThat(creado.getEname())
                .isEqualTo("Women's or girls' dresses, of synthetic fibres · Polyester · Casual wear");
        assertThat(creado.getCname()).isEqualTo("女式合成纤维制连衣裙");
        assertThat(creado.getApprovedAt()).isNull();
        assertThat(creado.getProductCount()).isEqualTo(728);
    }

    @Test
    void refrescaElNumeroDeProductosTambienEnLosAprobados() {
        // El panel ordena por tamaño para empezar por las partidas grandes: con la cuenta congelada en el
        // día de la siembra, quien aprueba elige mal por dónde empezar.
        CustomsDeclarationGroupEntity aprobado = CustomsDeclarationGroupEntity.builder().hs6("620443")
                .material("COTTON").usageCode("CASUAL WEAR").ename("Dresses").approvedAt(Instant.now()).productCount(12)
                .build();
        when(productRepository.customsTernas())
                .thenReturn(List.of(new CustomsTernaRow("620443", "Cotton", "Casual wear", 728L)));
        when(groupRepository.findByHs6AndMaterialAndUsageCode("620443", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.of(aprobado));

        assertThat(sync.sync()).isZero();
        assertThat(aprobado.getProductCount()).isEqualTo(728);
    }

    @Test
    void sinPartidaUtilizableNoHayGrupo() {
        // Sin código HS nadie ha verificado la clasificación, y de ella responde el declarante ante la
        // aduana. Ese producto sigue siendo su propia línea: se cobra de más, nunca de menos.
        when(productRepository.customsTernas())
                .thenReturn(List.of(new CustomsTernaRow(null, "Cotton", "Casual wear", 40L),
                        new CustomsTernaRow("6204", "Cotton", "Casual wear", 20L)));

        assertThat(sync.sync()).isZero();
        verify(groupRepository, never()).save(any());
    }

    @Test
    void dosGrafiasDelMismoMaterialSonUnSoloGrupo() {
        // «Cotton» y «  cotton » son el mismo material tecleado por dos personas. Tratarlos como grupos
        // distintos partiría en dos un grupo que la aduana cuenta como uno, y se cobrarían 3 EUR de más.
        when(productRepository.customsTernas())
                .thenReturn(List.of(new CustomsTernaRow("620443", "Cotton", "Casual wear", 700L),
                        new CustomsTernaRow("6204.43", "  cotton ", "CASUAL  WEAR", 28L)));
        when(groupRepository.findByHs6AndMaterialAndUsageCode("620443", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.empty());

        assertThat(sync.sync()).isEqualTo(1);
        assertThat(guardado().getProductCount()).isEqualTo(728);
    }

    @Test
    void noPisaElBorradorQueAlguienYaEstabaEditando() {
        // Un grupo sin aprobar puede estar a medio redactar. La siembra no es quién para deshacerlo: solo
        // rellena huecos y refresca la cuenta.
        CustomsDeclarationGroupEntity aMedias = CustomsDeclarationGroupEntity.builder().hs6("620443").material("COTTON")
                .usageCode("CASUAL WEAR").ename("Ladies dresses, woven").cname("女式连衣裙").productCount(1).build();
        when(productRepository.customsTernas())
                .thenReturn(List.of(new CustomsTernaRow("620443", "Cotton", "Casual wear", 728L)));
        when(groupRepository.findByHs6AndMaterialAndUsageCode("620443", "COTTON", "CASUAL WEAR"))
                .thenReturn(Optional.of(aMedias));

        assertThat(sync.sync()).isZero();
        assertThat(aMedias.getEname()).isEqualTo("Ladies dresses, woven");
        assertThat(aMedias.getCname()).isEqualTo("女式连衣裙");
    }

    @Test
    void unaPartidaDesconocidaCaeEnUnBorradorGenerico() {
        // El catálogo crece: una partida nueva no puede dejar el grupo sin texto ni reventar la siembra.
        // Nace igual de pendiente de aprobación, así que tampoco puede agrupar nada por accidente.
        when(productRepository.customsTernas())
                .thenReturn(List.of(new CustomsTernaRow("847130", "Aluminium", "Office", 3L)));
        when(groupRepository.findByHs6AndMaterialAndUsageCode(any(), any(), any())).thenReturn(Optional.empty());

        sync.sync();

        assertThat(guardado().getEname()).isEqualTo("Goods of HS heading 847130 · Aluminium · Office");
        assertThat(guardado().getCname()).isEqualTo("税则号列 847130 项下货品");
    }

    private CustomsDeclarationGroupEntity guardado() {
        ArgumentCaptor<CustomsDeclarationGroupEntity> captor = ArgumentCaptor
                .forClass(CustomsDeclarationGroupEntity.class);
        verify(groupRepository).save(captor.capture());
        return captor.getValue();
    }
}
