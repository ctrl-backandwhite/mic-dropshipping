package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.out.BillingConfigDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SetupIntentDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

/**
 * API contract for the authenticated user's billing/payment-methods (tarjeta guardada en el perfil).
 * Stripe Elements en el frontend usa la publishable key de {@code /billing/config} y un SetupIntent
 * para guardar la tarjeta; el backend nunca ve el PAN (solo el {@code pm_...} resultante).
 */
@Tag(name = "Billing (me)")
public interface MeBillingApi {

    @Operation(summary = "Config pública de Stripe (publishable key) para el UI de facturación del perfil")
    @ApiResponse(responseCode = "200", description = "Config devuelta")
    @GetMapping("/billing/config")
    ResponseEntity<BillingConfigDtoOut> billingConfig();

    @Operation(summary = "Crea un SetupIntent para guardar una tarjeta con Stripe Elements")
    @ApiResponse(responseCode = "200", description = "client_secret devuelto")
    @PostMapping("/payment-methods/setup-intent")
    ResponseEntity<SetupIntentDtoOut> createSetupIntent(Authentication auth) throws Exception;

    @Operation(summary = "Lista las tarjetas guardadas del usuario autenticado")
    @ApiResponse(responseCode = "200", description = "Tarjetas listadas")
    @GetMapping("/payment-methods")
    ResponseEntity<List<PaymentMethodDtoOut>> listPaymentMethods(Authentication auth) throws Exception;

    @Operation(summary = "Marca una tarjeta guardada como la predeterminada (la que cobra las suscripciones)")
    @ApiResponse(responseCode = "204", description = "Tarjeta por defecto fijada")
    @PostMapping("/payment-methods/{id}/default")
    ResponseEntity<Void> setDefault(Authentication auth, @PathVariable String id) throws Exception;

    @Operation(summary = "Borra (desvincula) una tarjeta guardada")
    @ApiResponse(responseCode = "204", description = "Tarjeta borrada")
    @DeleteMapping("/payment-methods/{id}")
    ResponseEntity<Void> delete(Authentication auth, @PathVariable String id) throws Exception;
}
