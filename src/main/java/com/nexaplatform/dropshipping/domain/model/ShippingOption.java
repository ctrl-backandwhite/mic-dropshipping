package com.nexaplatform.dropshipping.domain.model;

/**
 * Una forma de envío que el cliente puede elegir en el checkout.
 *
 * <p>Detrás de cada opción hay un canal del transportista, pero su código NO se le enseña al cliente:
 * «云途全球服装专线挂号» no le dice nada a nadie. Lo que se muestra es el nombre comercial, el plazo y
 * el precio, y el código viaja solo para poder emitir la guía por el mismo canal que se cotizó.
 *
 * <p>El importe va en céntimos USD, la moneda canónica, como el resto de precios.
 *
 * @param code       código del canal en el transportista (no se muestra al cliente)
 * @param name       nombre comercial de la opción
 * @param amountUsdCents precio del envío en céntimos USD
 * @param etaMinDays plazo mínimo estimado, en días
 * @param etaMaxDays plazo máximo estimado, en días
 */
public record ShippingOption(String code, String name, int amountUsdCents, int etaMinDays,
        int etaMaxDays) {
}
