package com.nexaplatform.dropshipping.application.chat;

import java.util.List;

/**
 * Lo que se devuelve al escaparate.
 *
 * @param conversationId identificador con el que seguir la conversación
 * @param reply          texto para la persona
 * @param products       productos encontrados durante el turno, para que el
 *                       escaparate pinte sus fichas con el componente de
 *                       siempre — que es quien sabe calcular y mostrar el precio
 * @param degraded       true si el motor falló y esto es un mensaje de cortesía
 * @param searchQuery    términos con los que se buscó en el catálogo, si hubo búsqueda; nulo si
 *                       la conversación no la necesitó (un pedido, una condición de envío). Es lo
 *                       que permite al escaparate repetir la búsqueda en su rejilla
 * @param searchTotal    cuántos productos encontró esa búsqueda en total, no cuántos se enseñan
 * @param reason         por qué no hay respuesta, para que el escaparate elija el aviso:
 *                       {@code OK}, {@code UNAVAILABLE} (el motor falló o está apagado) o
 *                       {@code QUOTA} (se agotó el cupo de mensajes)
 */
public record ChatAnswer(String conversationId, String reply, List<ChatProductRef> products, boolean degraded,
        String searchQuery, long searchTotal, String reason) {

    public static final String OK = "OK";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String QUOTA = "QUOTA";
}
