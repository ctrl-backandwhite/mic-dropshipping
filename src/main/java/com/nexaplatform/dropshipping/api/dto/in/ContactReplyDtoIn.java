package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Respuesta del admin a una solicitud de contacto: destinatario, asunto y mensaje. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactReplyDtoIn {

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @NotBlank
    @Size(max = 254)
    private String subject;

    @NotBlank
    @Size(max = 4000)
    private String message;
}
