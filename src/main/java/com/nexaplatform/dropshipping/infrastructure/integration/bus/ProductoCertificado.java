package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;

import java.time.Instant;

/**
 * Un producto que ha pasado la verificación y sale hacia el bus.
 *
 * <p>La ficha viaja en el MISMO formato que consume la carga masiva. No es pereza: es lo que
 * garantiza que no se pierda nada por el camino. Ese formato ya lleva los ocho idiomas, las
 * especificaciones, los atributos, los tramos de precio, los ejes de variante y las reseñas —todo
 * lo que ve una persona en la ficha—, y el exportador que lo produce y el importador que lo lee se
 * prueban juntos. Con un formato propio, cada campo nuevo del producto habría que acordarse de
 * añadirlo aquí, y el que se olvidara desaparecería en silencio en el destino.
 *
 * <p><b>Es un contrato INTERNO, entre entornos nuestros.</b> Lleva el precio de origen, que es
 * nuestro coste de compra. Lo que reciben los clientes integrados es otra proyección distinta, con
 * el precio de venta y las imágenes ya espejadas.
 */
public record ProductoCertificado(
        int version,
        String evento,
        Instant ocurrido,
        BulkProductDtoIn ficha) {

    public static ProductoCertificado de(BulkProductDtoIn ficha) {
        return new ProductoCertificado(EventoBus.VERSION, "producto.certificado", Instant.now(), ficha);
    }
}
