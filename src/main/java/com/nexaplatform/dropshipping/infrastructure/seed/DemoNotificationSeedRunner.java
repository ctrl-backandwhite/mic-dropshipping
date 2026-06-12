package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds a few in-app notifications for every user on first start so the notification
 * centre is not empty out of the box (DROP-609). Idempotent: skipped once any
 * notification already exists. Disable with {@code nexadrop.demo-seed.enabled=false}.
 */
@Slf4j
@Component
@Order(200)
@ConditionalOnProperty(prefix = "nexadrop.demo-seed", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DemoNotificationSeedRunner {

    private final NotificationRepository notificationRepository;
    private final NotificationUseCase notificationUseCase;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (notificationRepository.count() > 0) {
            return;
        }
        notificationUseCase.sendAdminNotification("all", "Bienvenido a NexaDrop",
                "Tu centro de notificaciones está activo. Aquí verás pedidos, pagos y avisos de la plataforma.");
        notificationUseCase.sendAdminNotification("all", "Catálogo actualizado",
                "Se han añadido nuevos productos al catálogo. Échales un vistazo en Explorar catálogo.");
        notificationUseCase.sendAdminNotification("all", "Consejo",
                "Conecta tu tienda en 'Mis tiendas' para sincronizar productos y pedidos automáticamente.");
        log.info("Demo notifications seeded for all users");
    }
}
