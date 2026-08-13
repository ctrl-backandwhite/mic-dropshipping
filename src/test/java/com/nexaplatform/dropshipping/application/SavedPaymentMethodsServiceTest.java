package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.application.service.SavedPaymentMethodsService;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PayPalPaymentMethodEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserDefaultPaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PayPalPaymentMethodRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserDefaultPaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SavedPaymentMethodsService}: el correo de PayPal se guarda CIFRADO y se devuelve ENMASCARADO,
 * el único método es el predeterminado de facto, y borrar el predeterminado limpia el puntero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavedPaymentMethodsServiceTest {

    @Mock
    StripeService stripeService;
    @Mock
    PayPalPaymentMethodRepository paypalRepository;
    @Mock
    UserDefaultPaymentRepository defaultRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    TokenCryptoService crypto;

    @InjectMocks
    SavedPaymentMethodsService service;

    private final UUID userId = UUID.randomUUID();

    private void noCards() {
        UserEntity u = new UserEntity();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(stripeService.isEnabled()).thenReturn(false); // solo PayPal en estos tests
    }

    @Test
    void guardarPaypalCifraElCorreoYSiEsElUnicoLoDejaPorDefecto() throws StripeException {
        noCards();
        when(crypto.encrypt("john@example.com")).thenReturn("gcm:ENC");
        when(paypalRepository.countByUserId(userId)).thenReturn(1L);
        when(defaultRepository.findById(userId)).thenReturn(Optional.empty());

        service.addPayPal(userId, "john@example.com");

        // Se persiste el correo CIFRADO, nunca en claro.
        ArgumentCaptor<PayPalPaymentMethodEntity> row = ArgumentCaptor.forClass(PayPalPaymentMethodEntity.class);
        verify(paypalRepository).save(row.capture());
        assertThat(row.getValue().getPaypalEmailEnc()).isEqualTo("gcm:ENC");
        // Único método → se fija como predeterminado.
        verify(defaultRepository).save(any(UserDefaultPaymentEntity.class));
    }

    @Test
    void listarDevuelveElCorreoEnmascaradoYMarcaElUnicoComoPorDefecto() throws StripeException {
        noCards();
        UUID ppId = UUID.randomUUID();
        PayPalPaymentMethodEntity pp = PayPalPaymentMethodEntity.builder().id(ppId).userId(userId)
                .paypalEmailEnc("gcm:ENC").build();
        when(paypalRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(pp));
        when(crypto.decrypt("gcm:ENC")).thenReturn("john.doe@example.com");
        when(defaultRepository.findById(userId)).thenReturn(Optional.empty());

        List<PaymentMethodDtoOut> out = service.list(userId);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getType()).isEqualTo("PAYPAL");
        assertThat(out.get(0).getId()).isEqualTo("paypal:" + ppId);
        // Enmascarado: primera letra + *** + dominio; nunca el correo completo.
        assertThat(out.get(0).getPaypalEmail()).isEqualTo("j***@example.com");
        assertThat(out.get(0).getPaypalEmail()).doesNotContain("ohn.doe");
        // Único método → por defecto aunque no haya puntero explícito.
        assertThat(out.get(0).isDefault()).isTrue();
    }

    @Test
    void borrarElMetodoPredeterminadoLimpiaElPuntero() throws StripeException {
        noCards();
        UUID ppId = UUID.randomUUID();
        String ref = "paypal:" + ppId;
        when(paypalRepository.findById(ppId)).thenReturn(Optional.of(
                PayPalPaymentMethodEntity.builder().id(ppId).userId(userId).build()));
        when(defaultRepository.findById(userId)).thenReturn(Optional.of(
                UserDefaultPaymentEntity.builder().userId(userId).ref(ref).build()));

        service.delete(userId, ref);

        verify(paypalRepository).deleteById(ppId);
        verify(defaultRepository).deleteById(userId);
    }

    @Test
    void noSePuedeBorrarUnPaypalDeOtroUsuario() throws StripeException {
        noCards();
        UUID ppId = UUID.randomUUID();
        when(paypalRepository.findById(ppId)).thenReturn(Optional.of(
                PayPalPaymentMethodEntity.builder().id(ppId).userId(UUID.randomUUID()).build())); // otro dueño

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(userId, "paypal:" + ppId))
                .isInstanceOf(com.nexaplatform.dropshipping.api.exception.NotFoundException.class);
        verify(paypalRepository, never()).deleteById(any());
    }
}
