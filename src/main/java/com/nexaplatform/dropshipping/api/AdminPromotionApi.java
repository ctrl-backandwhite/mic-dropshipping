package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AdminPromotionDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPromotionDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rebajas, cupones y promociones desde el admin. */
@Tag(name = "Admin Promotions")
public interface AdminPromotionApi {

    @Operation(summary = "Listar todas las promociones")
    @ApiResponse(responseCode = "200", description = "Promociones devueltas")
    @GetMapping
    ResponseEntity<List<AdminPromotionDtoOut>> list();

    @Operation(summary = "Crear una rebaja, cupón o promoción")
    @ApiResponse(responseCode = "200", description = "Promoción creada")
    @PostMapping
    ResponseEntity<AdminPromotionDtoOut> create(@Valid @RequestBody AdminPromotionDtoIn body);

    @Operation(summary = "Editar una promoción")
    @ApiResponse(responseCode = "200", description = "Promoción actualizada")
    @PutMapping("/{id}")
    ResponseEntity<AdminPromotionDtoOut> update(@PathVariable UUID id,
            @Valid @RequestBody AdminPromotionDtoIn body);

    @Operation(summary = "Activar o pausar una promoción")
    @ApiResponse(responseCode = "200", description = "Estado cambiado")
    @PostMapping("/{id}/toggle")
    ResponseEntity<AdminPromotionDtoOut> toggle(@PathVariable UUID id);

    @Operation(summary = "Anunciar la promoción a los usuarios de la plataforma")
    @ApiResponse(responseCode = "200", description = "Aviso enviado")
    @PostMapping("/{id}/announce")
    ResponseEntity<Map<String, Object>> announce(@PathVariable UUID id);

    @Operation(summary = "Borrar una promoción")
    @ApiResponse(responseCode = "204", description = "Promoción borrada")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id);
}
