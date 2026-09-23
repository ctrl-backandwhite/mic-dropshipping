package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Alta de un dispositivo para recibir avisos del sistema operativo. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegistrationDtoIn {

    @Schema(description = "Token de avisos que devuelve Expo en el dispositivo", example = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]")
    @NotBlank
    @Size(max = 255)
    private String token;

    @Schema(description = "Plataforma del dispositivo", example = "android")
    @Size(max = 16)
    private String platform;
}
