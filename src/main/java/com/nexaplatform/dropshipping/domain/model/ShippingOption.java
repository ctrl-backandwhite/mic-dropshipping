package com.nexaplatform.dropshipping.domain.model;

/**
 * Una forma de envío que se le ofrece al cliente en el checkout.
 *
 * <p>El {@code carrier} entró aquí el 18-ago-2026, al añadir CJ Dropshipping junto a YunExpress. Sin él
 * la opción no se puede despachar: al cobrar hay que saber a quién pedirle la guía, y dos transportistas
 * pueden devolver códigos que no se parecen en nada —{@code FZZXR} contra
 * {@code 1868922929754472449}— sin que el código diga de quién es cada uno. El cliente no lo ve: la
 * lista se le enseña mezclada y ordenada por precio, que es lo que se decidió.
 *
 * @param code           identificador de la línea EN SU transportista, tal y como él la nombra
 * @param name           nombre con el que la anuncia el transportista, no el que ve el cliente
 * @param amountUsdCents porte en céntimos de dólar, con los recargos ya dentro
 * @param carrier        quién la lleva ({@code YUNEXPRESS}, {@code CJ}); nulo si no se sabe
 */
public record ShippingOption(String code, String name, int amountUsdCents, int etaMinDays,
        int etaMaxDays, String carrier) {

    /**
     * Opción sin transportista asignado.
     *
     * <p>Existe porque cada transportista construye sus opciones sin saber cómo se llama a sí mismo
     * dentro de la plataforma: es el enrutador, que los conoce a todos, quien las sella. Mantiene
     * además funcionando todo lo escrito cuando solo había un transportista.
     */
    public ShippingOption(String code, String name, int amountUsdCents, int etaMinDays, int etaMaxDays) {
        this(code, name, amountUsdCents, etaMinDays, etaMaxDays, null);
    }

    /** La misma opción, sellada con el transportista que la ofrece. */
    public ShippingOption con(String carrier) {
        return new ShippingOption(code, name, amountUsdCents, etaMinDays, etaMaxDays, carrier);
    }
}
