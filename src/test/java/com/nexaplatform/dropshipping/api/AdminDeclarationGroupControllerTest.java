package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.controller.AdminDeclarationGroupController;
import com.nexaplatform.dropshipping.api.dto.in.AdminDeclarationGroupUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminDeclarationGroupDtoOut;
import com.nexaplatform.dropshipping.api.exception.ArgumentException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupSync;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El panel donde se aprueban las descripciones con las que se declara ante 27 aduanas.
 *
 * <p>Aprobar aquí no es marcar una casilla: es firmar el texto que viajará en la declaración de todos
 * los productos de esa terna. De ahí lo que se fija en estas pruebas — que quede constancia de quién
 * firma, que editar el texto obligue a volver a firmarlo, y que no se pueda firmar un texto vacío.
 */
@ExtendWith(MockitoExtension.class)
class AdminDeclarationGroupControllerTest {

    private static final UUID ID = UUID.randomUUID();

    @Mock
    CustomsDeclarationGroupRepository groupRepository;
    @Mock
    CustomsDeclarationGroupSync sync;

    @InjectMocks
    AdminDeclarationGroupController controller;

    @Test
    void aprobarDejaConstanciaDeQuienFirma() {
        // La aprobación es la firma de lo que se declara en 27 aduanas: sin saber quién firmó no hay a
        // quién preguntar cuando una aduana discrepe.
        CustomsDeclarationGroupEntity g = grupo("Men's woven cotton trousers", null);
        when(groupRepository.findById(ID)).thenReturn(Optional.of(g));
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.approve(ID, autenticacionDe("admin@nexadrop.com"));

        assertThat(g.getApprovedAt()).isNotNull();
        assertThat(g.getApprovedBy()).isEqualTo("admin@nexadrop.com");
    }

    @Test
    void editarLaDescripcionDeUnGrupoAprobadoLoDESAPRUEBA() {
        // Cambiar el texto es cambiar lo que se declara: tiene que volver a firmarse.
        CustomsDeclarationGroupEntity g = grupo("Men's woven cotton trousers", Instant.now());
        when(groupRepository.findById(ID)).thenReturn(Optional.of(g));
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.update(ID, new AdminDeclarationGroupUpdateDtoIn("Men's cotton trousers", "男式棉质长裤"));

        assertThat(g.getApprovedAt()).isNull();
        assertThat(g.getApprovedBy()).isNull();
        assertThat(g.getEname()).isEqualTo("Men's cotton trousers");
        assertThat(g.getCname()).isEqualTo("男式棉质长裤");
    }

    @Test
    void noSeFirmaUnTextoEnBlanco() {
        // Un EName vacío no es una descripción: el transportista rechaza la guía y, si la aceptara, se
        // estaría declarando «nada» para toda una terna del catálogo.
        CustomsDeclarationGroupEntity g = grupo("   ", null);
        when(groupRepository.findById(ID)).thenReturn(Optional.of(g));

        assertThatThrownBy(() -> controller.approve(ID, autenticacionDe("admin@nexadrop.com")))
                .isInstanceOf(ArgumentException.class);
        verify(groupRepository, never()).save(any());
    }

    @Test
    void retirarLaAprobacionDevuelveCadaProductoASuPropiaLinea() {
        // Es el freno de mano: si una aduana discrepa del texto, se retira la firma y esa terna vuelve a
        // declararse producto a producto. Se cobra de más, que es el lado seguro.
        CustomsDeclarationGroupEntity g = grupo("Men's woven cotton trousers", Instant.now());
        g.setApprovedBy("admin@nexadrop.com");
        when(groupRepository.findById(ID)).thenReturn(Optional.of(g));
        when(groupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminDeclarationGroupDtoOut vista = controller.unapprove(ID).getBody();

        assertThat(g.getApprovedAt()).isNull();
        assertThat(vista).isNotNull();
        assertThat(vista.approved()).isFalse();
    }

    @Test
    void elListadoPoneDelanteLasPartidasMasGrandes() {
        // Aprobar 185 descripciones de golpe no es realista: empezar por las partidas grandes cubre la
        // mayor parte del catálogo con las primeras.
        when(groupRepository.findAllByOrderByProductCountDesc())
                .thenReturn(List.of(grupo("Dresses", Instant.now()), grupo("Gloves", null)));

        List<AdminDeclarationGroupDtoOut> vista = controller.list().getBody();

        assertThat(vista).extracting(AdminDeclarationGroupDtoOut::ename).containsExactly("Dresses", "Gloves");
        assertThat(vista).extracting(AdminDeclarationGroupDtoOut::approved).containsExactly(true, false);
    }

    @Test
    void unGrupoQueNoExisteEs404() {
        when(groupRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.approve(ID, autenticacionDe("admin@nexadrop.com")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void sembrarDevuelveCuantosGruposSeHanCreado() {
        when(sync.sync()).thenReturn(185);

        assertThat(controller.sync().getBody()).containsEntry("created", 185);
    }

    private static CustomsDeclarationGroupEntity grupo(String ename, Instant aprobado) {
        return CustomsDeclarationGroupEntity.builder().id(ID).hs6("620443").material("COTTON")
                .usageCode("CASUAL WEAR").ename(ename).cname("男式棉制机织长裤").productCount(728)
                .approvedAt(aprobado).build();
    }

    private static Authentication autenticacionDe(String email) {
        return new UsernamePasswordAuthenticationToken(email, "n/a", List.of());
    }
}
