package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.PaymentEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletJpaRepositoryAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adaptador de persistencia de pagos.
 *
 * <p>Su única responsabilidad propia es resolver las relaciones gestionadas (usuario y monedero) desde
 * los identificadores planos del modelo de dominio. Si esa resolución fallara en silencio, se guardaría
 * un cobro sin dueño: dinero cobrado que no se puede reconciliar ni devolver.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov03PaymentRepositoryImplTest {

    @Mock
    PaymentEntityMapper paymentEntityMapper;
    @Mock
    PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;
    @Mock
    WalletJpaRepositoryAdapter walletJpaRepositoryAdapter;

    @InjectMocks
    PaymentRepositoryImpl repository;

    private final UUID userId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    private UserEntity usuario() {
        UserEntity u = new UserEntity();
        u.setId(userId);
        u.setEmail("cliente@example.com");
        return u;
    }

    private WalletEntity monedero() {
        WalletEntity w = new WalletEntity();
        w.setId(walletId);
        return w;
    }

    private void guardaDevolviendoLoRecibido() {
        when(paymentJpaRepositoryAdapter.save(any(PaymentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentEntityMapper.toDomain(any(PaymentEntity.class))).thenReturn(Payment.builder().build());
    }

    /* ==================== alta ==================== */

    @Test
    void unPagoNuevoSeGuardaConSuUsuarioYSuMonederoYaResueltos() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario()));
        when(walletJpaRepositoryAdapter.findById(walletId)).thenReturn(Optional.of(monedero()));
        guardaDevolviendoLoRecibido();
        Payment model = Payment.builder().userId(userId).walletId(walletId).purpose("ORDER")
                .providerResponse(Map.of("id", "pi_123")).build();

        repository.save(model);

        ArgumentCaptor<PaymentEntity> guardado = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentJpaRepositoryAdapter).save(guardado.capture());
        assertThat(guardado.getValue().getUser().getId()).isEqualTo(userId);
        assertThat(guardado.getValue().getWallet().getId()).isEqualTo(walletId);
        assertThat(guardado.getValue().getPurpose()).isEqualTo("ORDER");
        assertThat(guardado.getValue().getProviderResponse()).containsEntry("id", "pi_123");
        verify(paymentEntityMapper).updateEntity(guardado.getValue(), model);
    }

    @Test
    void unPagoSinMonederoAsociadoEsValidoPorqueNoTodoCobroPasaPorElMonedero() {
        // Un cobro con tarjeta contra un pedido no toca el monedero: exigirlo bloquearía el checkout.
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario()));
        guardaDevolviendoLoRecibido();

        repository.save(Payment.builder().userId(userId).build());

        ArgumentCaptor<PaymentEntity> guardado = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentJpaRepositoryAdapter).save(guardado.capture());
        assertThat(guardado.getValue().getWallet()).isNull();
        verify(walletJpaRepositoryAdapter, never()).findById(any());
    }

    @Test
    void sinPropositoIndicadoSeConservaElQueYaTeniaElPago() {
        // El propósito distingue una recarga de un cobro de pedido; machacarlo con null al actualizar
        // desharía la contabilidad de la operación.
        UUID paymentId = UUID.randomUUID();
        PaymentEntity existente = new PaymentEntity();
        existente.setId(paymentId);
        existente.setPurpose("ORDER_PAYMENT");
        when(paymentJpaRepositoryAdapter.findById(paymentId)).thenReturn(Optional.of(existente));
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario()));
        guardaDevolviendoLoRecibido();

        repository.save(Payment.builder().id(paymentId).userId(userId).purpose(null).build());

        ArgumentCaptor<PaymentEntity> guardado = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentJpaRepositoryAdapter).save(guardado.capture());
        assertThat(guardado.getValue().getPurpose()).isEqualTo("ORDER_PAYMENT");
    }

    @Test
    void unPagoDeUnUsuarioQueNoExisteNoSeGuarda() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        Payment model = Payment.builder().userId(userId).build();

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class);
        verify(paymentJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void unPagoContraUnMonederoQueNoExisteNoSeGuarda() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario()));
        when(walletJpaRepositoryAdapter.findById(walletId)).thenReturn(Optional.empty());
        Payment model = Payment.builder().userId(userId).walletId(walletId).build();

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class);
        verify(paymentJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void unPagoSinUsuarioNoResuelveRelacionAlguna() {
        guardaDevolviendoLoRecibido();

        repository.save(Payment.builder().build());

        verify(userRepository, never()).findById(any());
    }

    /* ==================== modificación ==================== */

    @Test
    void modificarUnPagoTrabajaSobreLaFilaExistenteYNoCreaOtra() {
        // Guardar una fila nueva con los mismos datos duplicaría el cobro en el libro de pagos.
        UUID paymentId = UUID.randomUUID();
        PaymentEntity existente = new PaymentEntity();
        existente.setId(paymentId);
        when(paymentJpaRepositoryAdapter.findById(paymentId)).thenReturn(Optional.of(existente));
        guardaDevolviendoLoRecibido();

        repository.update(Payment.builder().id(paymentId).build());

        ArgumentCaptor<PaymentEntity> guardado = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentJpaRepositoryAdapter).save(guardado.capture());
        assertThat(guardado.getValue()).isSameAs(existente);
    }

    @Test
    void modificarUnPagoQueYaNoExisteDaNoEncontrado() {
        UUID paymentId = UUID.randomUUID();
        when(paymentJpaRepositoryAdapter.findById(paymentId)).thenReturn(Optional.empty());
        Payment model = Payment.builder().id(paymentId).build();

        assertThatThrownBy(() -> repository.update(model)).isInstanceOf(NotFoundException.class);
    }

    /* ==================== lecturas ==================== */

    @Test
    void buscarPorIdentificadorDevuelveNuloOVacioSegunLaFormaDeLaConsulta() {
        // getById se usa donde ya se sabe que existe; findById donde hay que decidir. Confundirlos
        // convierte un "no está" en un NullPointerException.
        UUID id = UUID.randomUUID();
        when(paymentJpaRepositoryAdapter.findById(id)).thenReturn(Optional.empty());

        assertThat(repository.getById(id)).isNull();
        assertThat(repository.findById(id)).isEmpty();
    }

    @Test
    void buscarPorIdentificadorExistenteDevuelveElModeloDeDominio() {
        UUID id = UUID.randomUUID();
        PaymentEntity entity = new PaymentEntity();
        entity.setId(id);
        Payment model = Payment.builder().id(id).build();
        when(paymentJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(entity));
        when(paymentEntityMapper.toDomain(entity)).thenReturn(model);

        assertThat(repository.getById(id)).isSameAs(model);
        assertThat(repository.findById(id)).contains(model);
    }

    @Test
    void laBusquedaPorClaveDeIdempotenciaSeDelegaTalCual() {
        // Es la que impide cobrar dos veces el mismo webhook: no puede perderse por el camino.
        PaymentEntity entity = new PaymentEntity();
        Payment model = Payment.builder().build();
        when(paymentJpaRepositoryAdapter.findByIdempotencyKey("k-1")).thenReturn(Optional.of(entity));
        when(paymentEntityMapper.toDomain(entity)).thenReturn(model);

        assertThat(repository.findByIdempotencyKey("k-1")).contains(model);
        assertThat(repository.findByIdempotencyKey("k-2")).isEmpty();
    }

    @Test
    void laBusquedaPorReferenciaDelProveedorSeDelegaTalCual() {
        PaymentEntity entity = new PaymentEntity();
        Payment model = Payment.builder().build();
        when(paymentJpaRepositoryAdapter.findByProviderAndProviderRef("stripe", "pi_1"))
                .thenReturn(Optional.of(entity));
        when(paymentEntityMapper.toDomain(entity)).thenReturn(model);

        assertThat(repository.findByProviderAndProviderRef("stripe", "pi_1")).contains(model);
    }

    @Test
    void losListadosConservanElOrdenDeMasRecienteAMasAntiguo() {
        UUID orderId = UUID.randomUUID();
        List<PaymentEntity> entities = List.of(new PaymentEntity(), new PaymentEntity());
        List<Payment> models = List.of(Payment.builder().build(), Payment.builder().build());
        when(paymentJpaRepositoryAdapter.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(entities);
        when(paymentJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(entities);
        when(paymentJpaRepositoryAdapter.findAll()).thenReturn(entities);
        when(paymentEntityMapper.toDomainList(entities)).thenReturn(models);

        assertThat(repository.findByOrderIdOrderByCreatedAtDesc(orderId)).isEqualTo(models);
        assertThat(repository.findByUserIdOrderByCreatedAtDesc(userId)).isEqualTo(models);
        assertThat(repository.findAll()).isEqualTo(models);
    }

    @Test
    void borrarYComprobarExistenciaSeDeleganAlAdaptadorDeJpa() {
        UUID id = UUID.randomUUID();
        when(paymentJpaRepositoryAdapter.existsById(id)).thenReturn(true);

        repository.delete(id);

        verify(paymentJpaRepositoryAdapter).deleteById(id);
        assertThat(repository.existsById(id)).isTrue();
    }
}
