package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Método de pago guardado del usuario (tarjeta Stripe o PayPal); nunca datos sensibles completos. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodDtoOut {

    @Schema(description = "Referencia unificada del método: pm_... (tarjeta Stripe) o 'paypal:<uuid>'")
    private String id;

    @Schema(description = "Tipo de método: CARD | PAYPAL")
    private String type;

    @Schema(description = "Correo de PayPal ENMASCARADO (solo PAYPAL). El correo real se guarda cifrado.")
    private String paypalEmail;

    @Schema(description = "Marca de la tarjeta (visa, mastercard, ...)")
    private String brand;

    @Schema(description = "Últimos 4 dígitos")
    private String last4;

    @Schema(description = "Mes de expiración")
    private Long expMonth;

    @Schema(description = "Año de expiración")
    private Long expYear;

    // Sin @JsonProperty, Lombok+Jackson publican este booleano como "default" (quita el "is"), pero el
    // front lee "isDefault"; se fija el nombre del campo JSON para que coincidan y salga el predeterminado.
    @JsonProperty("isDefault")
    @Schema(description = "true si es el método por defecto (tarjeta o PayPal)")
    private boolean isDefault;
}
