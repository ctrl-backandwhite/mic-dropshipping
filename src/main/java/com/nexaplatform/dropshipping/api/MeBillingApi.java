package com.nexaplatform.dropshipping.api;

import com.stripe.exception.StripeException;
import com.nexaplatform.dropshipping.api.dto.in.SavePayPalDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingConfigDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.BillingInvoiceDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MySubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SetupIntentDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeStatusDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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
    ResponseEntity<BillingConfigDtoOut> billingConfig(Authentication auth);

    @Operation(summary = "Crea un SetupIntent para guardar una tarjeta con Stripe Elements")
    @ApiResponse(responseCode = "200", description = "client_secret devuelto")
    @PostMapping("/payment-methods/setup-intent")
    ResponseEntity<SetupIntentDtoOut> createSetupIntent(Authentication auth) throws StripeException;

    @Operation(summary = "Lista las tarjetas guardadas del usuario autenticado")
    @ApiResponse(responseCode = "200", description = "Métodos listados (tarjetas + PayPal)")
    @GetMapping("/payment-methods")
    ResponseEntity<List<PaymentMethodDtoOut>> listPaymentMethods(Authentication auth) throws StripeException;

    @Operation(summary = "Guarda una cuenta PayPal como método de pago (correo cifrado)")
    @ApiResponse(responseCode = "204", description = "PayPal guardado")
    @PostMapping("/payment-methods/paypal")
    ResponseEntity<Void> savePayPal(Authentication auth, @Valid @RequestBody SavePayPalDtoIn req);

    @Operation(summary = "Marca un método guardado (tarjeta o PayPal) como el predeterminado")
    @ApiResponse(responseCode = "204", description = "Tarjeta por defecto fijada")
    @PostMapping("/payment-methods/{id}/default")
    ResponseEntity<Void> setDefault(Authentication auth, @PathVariable String id) throws StripeException;

    @Operation(summary = "Envía por correo un código para confirmar la eliminación de un método de pago")
    @ApiResponse(responseCode = "204", description = "Código enviado")
    @PostMapping("/payment-methods/{id}/delete-code")
    ResponseEntity<Void> requestDeleteCode(Authentication auth, @PathVariable String id) throws StripeException;

    @Operation(summary = "Borra (desvincula) un método de pago confirmando con el código enviado por correo")
    @ApiResponse(responseCode = "204", description = "Método borrado")
    @DeleteMapping("/payment-methods/{id}")
    ResponseEntity<Void> delete(Authentication auth, @PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(value = "code", required = false) String code)
            throws StripeException;

    @Operation(summary = "Contrata un plan cobrando con la tarjeta guardada por defecto")
    @ApiResponse(responseCode = "200", description = "Suscripción creada")
    @PostMapping("/subscription")
    ResponseEntity<SubscribeStatusDtoOut> subscribe(Authentication auth, @Valid @RequestBody SubscribeDtoIn req)
            throws StripeException;

    @Operation(summary = "Suscripción vigente del usuario (o 204 si no tiene)")
    @ApiResponse(responseCode = "200", description = "Suscripción vigente")
    @GetMapping("/subscription")
    ResponseEntity<MySubscriptionDtoOut> currentSubscription(Authentication auth);

    @Operation(summary = "Cancela la suscripción vigente al final del periodo")
    @ApiResponse(responseCode = "204", description = "Cancelación programada")
    @PostMapping("/subscription/cancel")
    ResponseEntity<Void> cancelSubscription(Authentication auth) throws StripeException;

    @Operation(summary = "Historial de facturas del usuario (de Stripe)")
    @ApiResponse(responseCode = "200", description = "Facturas listadas")
    @GetMapping("/billing/invoices")
    ResponseEntity<List<BillingInvoiceDtoOut>> invoices(Authentication auth) throws StripeException;
}
