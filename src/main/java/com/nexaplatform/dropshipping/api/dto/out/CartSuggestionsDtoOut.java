package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Lo que el asistente sabe del carrito: qué conviene añadir y cuánto sitio queda en el paquete.
 *
 * <p>El hueco viaja aparte de las sugerencias porque es útil aunque no haya ninguna: saber que
 * quedan 40 g antes de pagar otro paquete cambia la decisión de quien compra, haya algo que
 * sugerirle o no.
 */
@Value
@Builder
public class CartSuggestionsDtoOut {

    List<CartSuggestionDtoOut> items;

    /** Gramos que caben todavía sin abrir otro bulto. Nulo si ese destino no parte envíos. */
    Integer gramosLibres;

    /** Lo que costaría de aduana ese segundo bulto, ya formateado. */
    String otroBultoFormatted;
}
