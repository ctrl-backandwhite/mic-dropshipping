package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductGroupMemberEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductGroupMemberRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Grupos de producto del admin. Un grupo determina qué regla de margen se aplica, así que cada mutación
 * tiene que invalidar la caché de márgenes o los precios se quedarían con el valor anterior.
 */
@ExtendWith(MockitoExtension.class)
class Cov06AdminProductGroupControllerTest {

    @Mock
    ProductGroupRepository groupRepository;
    @Mock
    ProductGroupMemberRepository memberRepository;
    @Mock
    ProductRepository productRepository;
    @Mock
    MarginService marginService;

    @InjectMocks
    AdminProductGroupController controller;

    /* ------------------------------ alta ------------------------------ */

    @Test
    void noSeCreaUnGrupoSinNombre() {
        Map<String, Object> body = new HashMap<>();
        body.put("description", "sin nombre");

        assertThatThrownBy(() -> controller.create(body)).isInstanceOf(BusinessException.class);
        verify(groupRepository, never()).save(any());
    }

    /** Un nombre en blanco es lo mismo que no tener nombre: no puede colarse por el hueco del trim. */
    @Test
    void noSeCreaUnGrupoConNombreEnBlanco() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "   ");

        assertThatThrownBy(() -> controller.create(body)).isInstanceOf(BusinessException.class);
    }

    @Test
    void alCrearSeGuardaElGrupoYSeInvalidaLaCacheDeMargenes() {
        when(groupRepository.save(any(ProductGroupEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.countByIdGroupId(any())).thenReturn(0L);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "  Verano  ");
        body.put("description", "   ");
        body.put("active", "false");

        ResponseEntity<Map<String, Object>> resp = controller.create(body);

        assertThat(resp.getBody()).containsEntry("name", "Verano"); // el nombre se recorta
        assertThat(resp.getBody()).containsEntry("description", null); // descripción en blanco → nula
        assertThat(resp.getBody()).containsEntry("active", false);
        assertThat(resp.getBody()).containsEntry("memberCount", 0L);
        verify(marginService).invalidateCache();
    }

    /* ------------------------------ edición ------------------------------ */

    @Test
    void editarUnGrupoInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(groupRepository.findById(id)).thenReturn(Optional.empty());
        Map<String, Object> body = Map.of("name", "X");

        assertThatThrownBy(() -> controller.update(id, body)).isInstanceOf(NotFoundException.class);
        verify(marginService, never()).invalidateCache();
    }

    /** Lo que no viene en el cuerpo no se toca: una edición parcial no puede borrar campos por omisión. */
    @Test
    void laEdicionParcialNoPisaLosCamposAusentes() {
        UUID id = UUID.randomUUID();
        ProductGroupEntity existente = ProductGroupEntity.builder().name("Verano").description("del año pasado")
                .active(true).build();
        existente.setId(id);
        when(groupRepository.findById(id)).thenReturn(Optional.of(existente));
        when(groupRepository.save(any(ProductGroupEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.update(id, Map.of("name", "Invierno"));

        assertThat(existente.getName()).isEqualTo("Invierno");
        assertThat(existente.getDescription()).isEqualTo("del año pasado");
        assertThat(existente.isActive()).isTrue();
    }

    /* ------------------------------ baja ------------------------------ */

    /**
     * El orden importa: primero las pertenencias y después el grupo. Al revés, la clave foránea de
     * {@code product_group_member} impediría el borrado.
     */
    @Test
    void alBorrarUnGrupoSeQuitanAntesSusPertenencias() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> resp = controller.delete(id);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        InOrder orden = inOrder(memberRepository, groupRepository, marginService);
        orden.verify(memberRepository).deleteByIdGroupId(id);
        orden.verify(groupRepository).deleteById(id);
        orden.verify(marginService).invalidateCache();
    }

    /* ------------------------------ miembros ------------------------------ */

    @Test
    void anadirMiembrosAUnGrupoInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(groupRepository.findById(id)).thenReturn(Optional.empty());
        Map<String, Object> body = Map.of("productIds", List.of(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> controller.addMembers(id, body)).isInstanceOf(NotFoundException.class);
        verify(memberRepository, never()).save(any());
    }

    /**
     * Solo se dan de alta las pertenencias nuevas y de productos que existen: los repetidos romperían la
     * clave primaria compuesta y los inexistentes dejarían filas huérfanas que el pricing no sabe resolver.
     */
    @Test
    void alAnadirMiembrosSeIgnoranLosRepetidosYLosProductosInexistentes() {
        UUID id = UUID.randomUUID();
        UUID nuevo = UUID.randomUUID();
        UUID yaEstaba = UUID.randomUUID();
        UUID inexistente = UUID.randomUUID();
        when(groupRepository.findById(id)).thenReturn(Optional.of(new ProductGroupEntity()));
        when(memberRepository.existsByIdGroupIdAndIdProductId(id, nuevo)).thenReturn(false);
        when(memberRepository.existsByIdGroupIdAndIdProductId(id, yaEstaba)).thenReturn(true);
        when(memberRepository.existsByIdGroupIdAndIdProductId(id, inexistente)).thenReturn(false);
        when(productRepository.existsById(nuevo)).thenReturn(true);
        when(productRepository.existsById(inexistente)).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.addMembers(id,
                Map.of("productIds", List.of(nuevo.toString(), yaEstaba.toString(), inexistente.toString())));

        assertThat(resp.getBody()).containsEntry("added", 1);
        verify(memberRepository).save(any(ProductGroupMemberEntity.class));
        verify(marginService).invalidateCache();
    }

    /** Cuerpo sin lista de productos: no es un error, simplemente no se añade nada. */
    @Test
    void anadirMiembrosSinListaNoAnadeNada() {
        UUID id = UUID.randomUUID();
        when(groupRepository.findById(id)).thenReturn(Optional.of(new ProductGroupEntity()));

        ResponseEntity<Map<String, Object>> resp = controller.addMembers(id, new HashMap<>());

        assertThat(resp.getBody()).containsEntry("added", 0);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void quitarUnMiembroInvalidaLaCacheDeMargenes() {
        UUID id = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        ResponseEntity<Void> resp = controller.removeMember(id, productId);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(memberRepository).deleteByIdGroupIdAndIdProductId(id, productId);
        verify(marginService).invalidateCache();
    }

    /** En la lista de miembros el producto se identifica por su título chino; sin él, por el slug. */
    @Test
    void elMiembroSinTituloChinoSeIdentificaPorElSlug() {
        UUID id = UUID.randomUUID();
        UUID conTitulo = UUID.randomUUID();
        UUID sinTitulo = UUID.randomUUID();
        ProductEntity a = ProductEntity.builder().titleZh("春季外套").slug("chaqueta-primavera").build();
        a.setId(conTitulo);
        ProductEntity b = ProductEntity.builder().slug("solo-slug").build();
        b.setId(sinTitulo);
        List<UUID> ids = List.of(conTitulo, sinTitulo);
        when(memberRepository.findProductIdsByGroupId(id)).thenReturn(ids);
        when(productRepository.findAllById(ids)).thenReturn(List.of(a, b));

        List<Map<String, Object>> miembros = controller.members(id);

        assertThat(miembros).hasSize(2);
        assertThat(miembros.get(0)).containsEntry("title", "春季外套");
        assertThat(miembros.get(1)).containsEntry("title", "solo-slug");
    }

    @Test
    void elListadoDeGruposLlevaElNumeroDeProductosDeCadaUno() {
        UUID id = UUID.randomUUID();
        ProductGroupEntity g = ProductGroupEntity.builder().name("Verano").active(true).build();
        g.setId(id);
        when(groupRepository.findAllByOrderByNameAsc()).thenReturn(List.of(g));
        when(memberRepository.countByIdGroupId(id)).thenReturn(7L);

        List<Map<String, Object>> lista = controller.list();

        assertThat(lista).hasSize(1);
        assertThat(lista.get(0)).containsEntry("memberCount", 7L).containsEntry("name", "Verano");
    }
}
