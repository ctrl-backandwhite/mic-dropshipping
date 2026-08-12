package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Payload to create a product review (storefront). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewDtoIn {

    @NotNull
    @Min(1)
    @Max(5)
    private Short rating;

    @Size(max = 150)
    private String title;

    @Size(max = 2000)
    private String body;

    /** Idioma de la reseña (es/en/pt/zh). Por defecto el del cliente. */
    @Size(max = 8)
    private String language;

    /** Nombre a mostrar; si se omite se usa el del usuario autenticado. */
    @Size(max = 80)
    private String authorName;

    /** País del autor (ISO-2), opcional. */
    @Size(max = 2)
    private String authorCountry;
}
