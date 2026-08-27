package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AdminDeclarationGroupUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminDeclarationGroupDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contrato del panel de grupos de declaración: qué ternas hay, con qué texto se declaran y cuáles están
 * firmadas.
 *
 * <p>Vive bajo {@code /api/admin/**}, así que exige rol ADMIN por configuración, no por anotación.
 */
@Tag(name = "Admin Declaration Groups")
public interface AdminDeclarationGroupApi {

    @Operation(summary = "Grupos de declaración, los que más productos abarcan primero")
    @ApiResponse(responseCode = "200", description = "Grupos devueltos")
    @GetMapping
    ResponseEntity<List<AdminDeclarationGroupDtoOut>> list();

    @Operation(summary = "Editar la descripción de un grupo (lo desaprueba: hay que volver a firmarla)")
    @ApiResponse(responseCode = "200", description = "Descripción guardada")
    @PutMapping("/{id}")
    ResponseEntity<AdminDeclarationGroupDtoOut> update(@PathVariable UUID id,
            @Valid @RequestBody AdminDeclarationGroupUpdateDtoIn body);

    @Operation(summary = "Aprobar el texto: a partir de aquí la terna se declara como UNA línea")
    @ApiResponse(responseCode = "200", description = "Grupo aprobado")
    @PostMapping("/{id}/approve")
    ResponseEntity<AdminDeclarationGroupDtoOut> approve(@PathVariable UUID id, Authentication auth);

    @Operation(summary = "Retirar la aprobación: cada producto vuelve a ser su propia línea")
    @ApiResponse(responseCode = "200", description = "Aprobación retirada")
    @PostMapping("/{id}/unapprove")
    ResponseEntity<AdminDeclarationGroupDtoOut> unapprove(@PathVariable UUID id);

    @Operation(summary = "Sembrar los grupos que falten a partir del catálogo")
    @ApiResponse(responseCode = "200", description = "Siembra ejecutada")
    @PostMapping("/sync")
    ResponseEntity<Map<String, Integer>> sync();
}
