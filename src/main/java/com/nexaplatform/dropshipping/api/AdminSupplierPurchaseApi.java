package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AdminPurchaseBoughtDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchasePackedDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchaseShippedDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierPurchaseDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contrato de la cola de compras a proveedores: lo que hay que comprar en 1688, en qué estado va cada
 * bulto y el fichero de re-empaquetado para subir al OMS de Yunfulfillment.
 */
@Tag(name = "Admin Supplier Purchases")
public interface AdminSupplierPurchaseApi {

    @Operation(summary = "Cola de compras pendientes: lo que hay que comprar o esperar")
    @ApiResponse(responseCode = "200", description = "Cola devuelta")
    @GetMapping
    ResponseEntity<List<AdminSupplierPurchaseDtoOut>> queue();

    @Operation(summary = "Compras de un pedido concreto")
    @ApiResponse(responseCode = "200", description = "Compras devueltas")
    @GetMapping("/order/{orderId}")
    ResponseEntity<List<AdminSupplierPurchaseDtoOut>> forOrder(@PathVariable UUID orderId);

    @Operation(summary = "Generar las compras de un pedido ya pagado que aún no las tiene")
    @ApiResponse(responseCode = "200", description = "Compras generadas (o las que ya había)")
    @PostMapping("/order/{orderId}/plan")
    ResponseEntity<List<AdminSupplierPurchaseDtoOut>> plan(@PathVariable UUID orderId);

    @Operation(summary = "Bultos en riesgo de destrucción por llevar demasiado tiempo en el almacén")
    @ApiResponse(responseCode = "200", description = "Bultos en riesgo devueltos")
    @GetMapping("/at-risk")
    ResponseEntity<List<AdminSupplierPurchaseDtoOut>> atRisk();

    @Operation(summary = "Registrar la compra en 1688 con su referencia y coste real")
    @ApiResponse(responseCode = "200", description = "Compra registrada")
    @PostMapping("/{id}/bought")
    ResponseEntity<AdminSupplierPurchaseDtoOut> bought(@PathVariable UUID id,
            @Valid @RequestBody AdminPurchaseBoughtDtoIn body);

    @Operation(summary = "Registrar que el proveedor ha enviado el bulto al almacén")
    @ApiResponse(responseCode = "200", description = "Envío registrado")
    @PostMapping("/{id}/shipped")
    ResponseEntity<AdminSupplierPurchaseDtoOut> shipped(@PathVariable UUID id,
            @Valid @RequestBody AdminPurchaseShippedDtoIn body);

    @Operation(summary = "Registrar que el almacén ha recibido el bulto")
    @ApiResponse(responseCode = "200", description = "Recepción registrada")
    @PostMapping("/{id}/received")
    ResponseEntity<AdminSupplierPurchaseDtoOut> received(@PathVariable UUID id);

    @Operation(summary = "Confirmar la orden de re-empaquetado dada de alta en Yunfulfillment")
    @ApiResponse(responseCode = "200", description = "Orden de re-empaquetado registrada")
    @PostMapping("/{id}/packed")
    ResponseEntity<AdminSupplierPurchaseDtoOut> packed(@PathVariable UUID id,
            @Valid @RequestBody AdminPurchasePackedDtoIn body);

    @Operation(summary = "Cancelar una compra")
    @ApiResponse(responseCode = "200", description = "Compra cancelada")
    @PostMapping("/{id}/cancel")
    ResponseEntity<AdminSupplierPurchaseDtoOut> cancel(@PathVariable UUID id,
            @RequestParam(required = false) String reason);

    @Operation(summary = "Vista previa del fichero de re-empaquetado: qué se exporta y qué falta arreglar")
    @ApiResponse(responseCode = "200", description = "Plan devuelto")
    @GetMapping("/pack-sheet/preview")
    ResponseEntity<Map<String, Object>> packSheetPreview();

    @Operation(summary = "Generar y descargar el .xls de importación masiva para el OMS de Yunfulfillment. "
            + "Marca las compras incluidas como exportadas para que no se repitan en el siguiente fichero.")
    @ApiResponse(responseCode = "200", description = "Fichero generado")
    // POST y no GET: la descarga TIENE efecto (marca las compras como exportadas). Un GET no debe mutar,
    // y un prefetch/retry del navegador consumiría la cola sin que el operador lo pida.
    @PostMapping("/pack-sheet")
    ResponseEntity<byte[]> packSheet();

    @Operation(summary = "Volver a poner una compra en la cola de exportación (re-marca manual del admin)")
    @ApiResponse(responseCode = "200", description = "Compra devuelta a la cola de exportación")
    @PostMapping("/{id}/reexport")
    ResponseEntity<AdminSupplierPurchaseDtoOut> reexport(@PathVariable UUID id);

    @Operation(summary = "Marcar como reempaquetadas las compras del fichero ya subido al OMS")
    @ApiResponse(responseCode = "200", description = "Compras marcadas")
    @PostMapping("/pack-sheet/confirm")
    ResponseEntity<Map<String, Object>> confirmPacked(@RequestBody List<UUID> purchaseIds);
}
