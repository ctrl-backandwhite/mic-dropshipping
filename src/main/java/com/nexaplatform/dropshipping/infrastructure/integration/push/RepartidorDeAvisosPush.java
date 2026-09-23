package com.nexaplatform.dropshipping.infrastructure.integration.push;

import com.nexaplatform.dropshipping.application.notifications.AvisoCreado;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Lleva al teléfono los avisos que entran en el buzón.
 *
 * <p>Escucha <b>después del commit</b> a propósito: un aviso en el móvil no se puede retirar, y
 * mandarlo antes de que la transacción cierre significaría avisar de pedidos que luego se deshacen.
 *
 * <p>Y va en otro hilo porque hablar con el servicio de Expo puede tardar segundos: quien guardó el
 * aviso —la persona que respondió un ticket, el proceso que cobró una suscripción— no tiene por qué
 * esperar a que suene un teléfono.
 */
@Component
@RequiredArgsConstructor
public class RepartidorDeAvisosPush {

    private final ExpoPushSender sender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alEntrarUnAviso(AvisoCreado aviso) {
        // El identificador y el tipo viajan como datos para que la aplicación pueda abrir el aviso
        // —o la pantalla que le corresponda— al tocarlo, en vez de limitarse a abrirse por la portada.
        sender.reparte(aviso.userId(), aviso.titulo(), aviso.cuerpo(), Map.of("avisoId",
                String.valueOf(aviso.avisoId()), "eventType", aviso.eventType() == null ? "" : aviso.eventType()));
    }
}
