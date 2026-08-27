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

    /** Lo que sube el arancel. Solo viene cuando la sugerencia es por arancel y vale cero. */
    String dutyExtraFormatted;

    /** Lo que sube el envío por meterlo en el bulto ya pagado. Nulo si no se pudo cotizar. */
    String shippingExtraFormatted;

    /** Lo que costaría enviarlo suelto. Es la referencia que hace entendible el ahorro. */
    String shippingAloneFormatted;

    /** Por qué se sugiere: DUTY (no suma arancel) o SHIPPING (viaja en el mismo paquete). */
    String motivo;
}
