package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** client_secret de un SetupIntent de Stripe para confirmar el guardado de tarjeta con Elements. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetupIntentDtoOut {

    @Schema(description = "client_secret del SetupIntent; el frontend lo confirma con Stripe Elements")
    private String clientSecret;
}
