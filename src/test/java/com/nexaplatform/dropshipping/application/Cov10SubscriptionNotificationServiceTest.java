package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aviso de cobro fallido del plan. Se dispara desde el webhook de Stripe: nada de lo que pase aquí puede
 * abortar el procesamiento del webhook (Stripe lo reintentaría en bucle), y un evento de una suscripción
 * que no es nuestra tiene que pasar de largo en silencio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10SubscriptionNotificationServiceTest {

    @Mock
    CustomerSubscriptionRepository customerSubscriptionRepository;
    @Mock
    NotificationRepository notificationRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    EmailQueueService emailQueue;

    @InjectMocks
    SubscriptionNotificationService service;

    @BeforeEach
    void setUp() throws Exception {
        Field baseUrl = SubscriptionNotificationService.class.getDeclaredField("baseUrl");
        baseUrl.setAccessible(true);
        baseUrl.set(service, "https://tienda.example");
    }

    @Test
    void unIdDeSuscripcionVacioNiSiquieraConsultaLaBaseDeDatos() {
        service.planPaymentFailed(null);
        service.planPaymentFailed("  ");
        // Stripe manda literalmente la cadena "null" cuando el evento no lleva suscripción asociada.
        service.planPaymentFailed("NULL");

        verify(customerSubscriptionRepository, never()).findByStripeSubscriptionId(anyString());
    }

    @Test
    void unaSuscripcionQueNoGestionamosSePasaPorAlto() {
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_ajena")).thenReturn(Optional.empty());

        service.planPaymentFailed("sub_ajena");

        verify(notificationRepository, never()).save(any());
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unaSuscripcionSinDuenoNoGeneraAvisoHuerfano() {
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1"))
                .thenReturn(Optional.of(CustomerSubscription.builder().userId(null).build()));

        service.planPaymentFailed("sub_1");

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void elDuenoRecibeCampanaYCorreoConElEnlaceASuPerfil() {
        UUID userId = UUID.randomUUID();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1"))
                .thenReturn(Optional.of(CustomerSubscription.builder().userId(userId).build()));
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(UserEntity.builder().email("cliente@example.com").build()));

        service.planPaymentFailed("sub_1");

        ArgumentCaptor<PlatformNotification> aviso = ArgumentCaptor.forClass(PlatformNotification.class);
        verify(notificationRepository).save(aviso.capture());
        assertThat(aviso.getValue().getUserId()).isEqualTo(userId);
        assertThat(aviso.getValue().getEventType()).isEqualTo("PLAN_PAYMENT_FAILED");
        assertThat(aviso.getValue().getChannel()).isEqualTo("IN_APP");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("cliente@example.com"), anyString(), eq("emails/notification"), vars.capture());
        // El enlace tiene que apuntar al dominio público configurado, no a localhost.
        assertThat(vars.getValue()).containsEntry("ctaUrl", "https://tienda.example/profile");
        assertThat(vars.getValue()).containsKey("bodyHtml").containsKey("ctaLabel");
    }

    @Test
    void siLaCampanaFallaElCorreoSeEnviaIgual() {
        UUID userId = UUID.randomUUID();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1"))
                .thenReturn(Optional.of(CustomerSubscription.builder().userId(userId).build()));
        when(notificationRepository.save(any())).thenThrow(new IllegalStateException("tabla bloqueada"));
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(UserEntity.builder().email("cliente@example.com").build()));

        service.planPaymentFailed("sub_1");

        // Los dos canales son independientes: que caiga uno no puede dejar al cliente sin enterarse.
        verify(emailQueue).enqueue(eq("cliente@example.com"), anyString(), anyString(), anyMap());
    }

    @Test
    void sinCorreoDelUsuarioNoSeIntentaEnviarNada() {
        UUID userId = UUID.randomUUID();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1"))
                .thenReturn(Optional.of(CustomerSubscription.builder().userId(userId).build()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity.builder().email("  ").build()));

        service.planPaymentFailed("sub_1");

        verify(notificationRepository).save(any());
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unUsuarioBorradoNoRompeElProcesadoDelWebhook() {
        UUID userId = UUID.randomUUID();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1"))
                .thenReturn(Optional.of(CustomerSubscription.builder().userId(userId).build()));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatCode(() -> service.planPaymentFailed("sub_1")).doesNotThrowAnyException();
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void siLaColaDeCorreoFallaElWebhookNoSeCae() {
        UUID userId = UUID.randomUUID();
        when(customerSubscriptionRepository.findByStripeSubscriptionId("sub_1"))
                .thenReturn(Optional.of(CustomerSubscription.builder().userId(userId).build()));
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(UserEntity.builder().email("cliente@example.com").build()));
        when(emailQueue.enqueue(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("SMTP caído"));

        // Si esto propagara, Stripe reintentaría el webhook indefinidamente.
        assertThatCode(() -> service.planPaymentFailed("sub_1")).doesNotThrowAnyException();
    }
}
