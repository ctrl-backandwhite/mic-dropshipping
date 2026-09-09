package com.nexaplatform.dropshipping.application.notifications;

import java.util.UUID;

/**
 * Ha entrado un aviso nuevo en el buzón de alguien.
 *
 * <p>Se publica al GUARDARLO y lo recoge quien reparte los avisos del sistema operativo. Va por
 * evento y no por llamada directa porque el buzón se escribe desde cuatro sitios distintos —mensajes
 * de administración, tickets de soporte, suscripciones y contacto— y engancharlo en cada uno
 * garantizaba que el quinto se olvidara.
 *
 * @param avisoId    para que la aplicación pueda abrirlo al tocar la notificación
 * @param eventType  qué ha pasado; la aplicación decide con esto a qué pantalla llevar
 */
public record AvisoCreado(UUID userId, UUID avisoId, String titulo, String cuerpo, String eventType) {
}
