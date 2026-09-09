package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminDeclarationGroupApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminDeclarationGroupUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminDeclarationGroupDtoOut;
import com.nexaplatform.dropshipping.api.exception.ArgumentException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupSync;
import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupSync;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * El panel donde se firman las descripciones con las que se declara ante 27 aduanas.
 *
 * <p>Aprobar aquí no es marcar una casilla: es decidir que todos los productos de una terna viajarán con
 * el mismo texto y que la aduana los contará como <b>una sola línea</b> de 3 EUR en vez de una por
 * producto. De ahí las tres reglas que implementa este controlador:
 *
 * <ol>
 *   <li><b>Queda constancia de quién firma.</b> Cuando una aduana discrepe del texto habrá a quién
 *       preguntar.</li>
 *   <li><b>Editar el texto desaprueba el grupo.</b> Cambiar la descripción es cambiar lo que se declara,
 *       así que vuelve a necesitar firma. Mientras tanto, cada producto es su propia línea: se cobra de
 *       más, nunca de menos.</li>
 *   <li><b>No se firma un texto en blanco.</b> Un {@code EName} vacío no es una descripción; el
 *       transportista rechazaría la guía y, si la aceptara, se estaría declarando «nada» para toda una
 *       terna del catálogo.</li>
 * </ol>
 *
 * <p>Retirar la aprobación es el freno de mano: devuelve esa terna al comportamiento de siempre sin
 * borrar el texto y sin tocar los pedidos ya emitidos, que cuentan por su snapshot.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/declaration-groups")
@RequiredArgsConstructor
public class AdminDeclarationGroupController implements AdminDeclarationGroupApi {

    private final CustomsDeclarationGroupRepository groupRepository;
    private final CustomsDeclarationGroupSync sync;

    @Override
    public ResponseEntity<List<AdminDeclarationGroupDtoOut>> list() {
        return ResponseEntity.ok(groupRepository.findAllByOrderByProductCountDesc().stream()
                .map(AdminDeclarationGroupController::vista).toList());
    }

    @Override
    public ResponseEntity<AdminDeclarationGroupDtoOut> update(UUID id, AdminDeclarationGroupUpdateDtoIn body) {
        CustomsDeclarationGroupEntity grupo = buscar(id);
        grupo.setEname(body.ename().trim());
        grupo.setCname(body.cname() == null ? "" : body.cname().trim());
        // Cambiar el texto es cambiar lo que se declara: la firma anterior ya no ampara esto.
        grupo.setApprovedAt(null);
        grupo.setApprovedBy(null);
        grupo.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(vista(groupRepository.save(grupo)));
    }

    @Override
    public ResponseEntity<AdminDeclarationGroupDtoOut> approve(UUID id, Authentication auth) {
        CustomsDeclarationGroupEntity grupo = buscar(id);
        if (grupo.getEname() == null || grupo.getEname().isBlank()) {
            throw new ArgumentException("No se puede aprobar un grupo sin descripción en inglés");
        }
        // Y tampoco con la descripción de relleno: «Goods of HS heading 611212» no describe una
        // mercancía, describe un número, y firmarlo lo pone tal cual en la declaración ante la aduana
        // del destino. Sin aprobar, ese relleno es inofensivo —cada producto va en su línea y se paga
        // de más—; aprobado, es una declaración vaga de las que retienen el paquete. Hay que redactar
        // primero la descripción, aquí mismo, y luego firmar.
        if (CustomsDeclarationGroupSync.esRellenoSinRedactar(grupo)) {
            throw new ArgumentException(
                    "Este grupo todavía tiene la descripción de relleno de su partida: redáctala antes de aprobarlo");
        }
        grupo.setApprovedAt(Instant.now());
        grupo.setApprovedBy(auth != null ? auth.getName() : "admin");
        grupo.setUpdatedAt(Instant.now());
        log.info("Grupo de declaración {} ({}) aprobado por {}", grupo.getHs6(), id, grupo.getApprovedBy());
        return ResponseEntity.ok(vista(groupRepository.save(grupo)));
    }

    @Override
    public ResponseEntity<AdminDeclarationGroupDtoOut> unapprove(UUID id) {
        CustomsDeclarationGroupEntity grupo = buscar(id);
        grupo.setApprovedAt(null);
        grupo.setApprovedBy(null);
        grupo.setUpdatedAt(Instant.now());
        log.info("Grupo de declaración {} ({}) sin aprobación: vuelve a una línea por producto",
                grupo.getHs6(), id);
        return ResponseEntity.ok(vista(groupRepository.save(grupo)));
    }

    @Override
    public ResponseEntity<Map<String, Integer>> sync() {
        return ResponseEntity.ok(Map.of("created", this.sync.sync()));
    }

    private CustomsDeclarationGroupEntity buscar(UUID id) {
        return groupRepository.findById(id).orElseThrow(() -> new NotFoundException("DeclarationGroup"));
    }

    private static AdminDeclarationGroupDtoOut vista(CustomsDeclarationGroupEntity g) {
        return new AdminDeclarationGroupDtoOut(g.getId(), g.getHs6(), g.getMaterial(), g.getUsageCode(),
                g.getEname(), g.getCname(), g.getProductCount(), g.getApprovedAt() != null, g.getApprovedAt(),
                g.getApprovedBy(), CustomsDeclarationGroupSync.esRellenoSinRedactar(g));
    }
}
