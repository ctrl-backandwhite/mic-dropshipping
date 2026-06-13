package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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

    private String title;

    private String body;

    /** Idioma de la reseña (es/en/pt/zh). Por defecto el del cliente. */
    private String language;

    /** Nombre a mostrar; si se omite se usa el del usuario autenticado. */
    private String authorName;

    /** País del autor (ISO-2), opcional. */
    private String authorCountry;
}
