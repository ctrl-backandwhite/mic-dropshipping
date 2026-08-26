package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** Lo que la persona lleva en el carrito, para saber qué puede añadir casi gratis. */
@Data
public class CartSuggestionsDtoIn {

    /** Tope de 50 líneas: es un carrito, no una importación. */
    @Valid
    @NotNull
    @Size(max = 50)
    private List<Linea> items;

    @Size(max = 8)
    private String lang;

    @Data
    public static class Linea {

        @NotNull
        private UUID productId;

        private UUID variantId;

        @Positive
        private int quantity = 1;
    }
}
