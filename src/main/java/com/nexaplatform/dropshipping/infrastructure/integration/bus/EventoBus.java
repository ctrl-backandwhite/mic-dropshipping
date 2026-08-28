package com.nexaplatform.dropshipping.infrastructure.integration.bus;

/**
 * Temas del bus de integración y versión de sus mensajes.
 *
 * <p>El bus es una cuarta instancia de Redpanda, aparte de las tres de entorno.
 * Aquí publica preproducción el catálogo ya certificado, y de aquí consumen
 * producción y —más adelante— el repartidor de webhooks hacia los clientes con
 * plan de pago.
 *
 * <p>Ningún entorno consume del Kafka interno de otro: eso rompería el
 * aislamiento que hace que una prueba en preproducción no pueda tocar la tienda.
 */
public final class EventoBus {

    /** Categorías, antes que los productos: un producto sin su categoría llega huérfano. */
    public static final String CATEGORIA_PUBLICADA = "catalogo.categoria.publicada";
    /** Un producto marcado como verificado en preproducción. */
    public static final String PRODUCTO_CERTIFICADO = "catalogo.producto.certificado";
    /** Un producto que deja de estar certificado o se retira del catálogo. */
    public static final String PRODUCTO_RETIRADO = "catalogo.producto.retirado";
    /**
     * Donde acaban los mensajes que no se han podido aplicar tras varios intentos.
     *
     * <p>Sin él, un solo mensaje ilegible o un producto que siempre falla bloquearían la partición
     * para siempre: el consumidor lo reintentaría sin fin y ningún producto posterior llegaría a la
     * tienda. Apartándolo aquí, la propagación sigue y el mensaje queda entero para repescarlo.
     */
    public static final String DESCARTES = "catalogo.descartes";

    /**
     * Versión del formato de los mensajes.
     *
     * <p>Viaja dentro de cada evento. Un consumidor que reciba una versión que no
     * entiende debe rechazarla y avisar, nunca adivinar: el catálogo mal
     * interpretado se traduce en productos mal publicados en la tienda.
     */
    public static final int VERSION = 1;

    private EventoBus() {
    }
}
