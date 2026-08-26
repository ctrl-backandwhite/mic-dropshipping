package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/** Un producto que conviene añadir, con el ahorro ya calculado y escrito por el backend. */
@Value
@Builder
public class CartSuggestionDtoOut {

    UUID id;
    String slug;
    String title;
    String image;

    /** Lo que sube el arancel por llevárselo. Siempre cero: si subiera, no se sugeriría. */
    String dutyExtraFormatted;

    /** Lo que sube el envío por meterlo en el bulto ya pagado. Nulo si no se pudo cotizar. */
    String shippingExtraFormatted;
}
