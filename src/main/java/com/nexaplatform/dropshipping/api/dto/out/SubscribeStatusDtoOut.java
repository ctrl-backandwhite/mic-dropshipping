package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Resultado de contratar un plan con la tarjeta guardada. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscribeStatusDtoOut {

    @Schema(description = "Id de la suscripción (Stripe sub_… o local si el plan es gratis)")
    private String subscriptionId;

    @Schema(description = "Estado: active / incomplete / past_due / …")
    private String status;
}
