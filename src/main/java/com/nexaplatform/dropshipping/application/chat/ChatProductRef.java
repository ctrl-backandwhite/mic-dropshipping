package com.nexaplatform.dropshipping.application.chat;

/**
 * Producto sugerido durante la conversación. Lleva lo justo para pintar un
 * enlace: identificador de ruta, título e imagen. <b>Sin precio</b> — el precio
 * lo calcula y lo muestra la ficha, que es quien sabe aplicar margen, impuestos
 * y envío del país de quien mira.
 */
public record ChatProductRef(String slug, String title, String image) {
}
