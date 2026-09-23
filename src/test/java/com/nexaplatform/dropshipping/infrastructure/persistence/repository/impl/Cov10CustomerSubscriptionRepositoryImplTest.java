package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.CustomerSubscriptionEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adaptador de persistencia de suscripciones. Su único trabajo propio es resolver las relaciones
 * gestionadas (usuario y plan) a partir de los ids planos del modelo: si eso falla en silencio, la
 * suscripción se guarda huérfana y el cobro recurrente deja de saber a quién pertenece.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10CustomerSubscriptionRepositoryImplTest {

    @Mock
    CustomerSubscriptionEntityMapper customerSubscriptionEntityMapper;
    @Mock
    CustomerSubscriptionJpaRepositoryAdapter customerSubscriptionJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;
    @Mock
    SubscriptionPlanRepository subscriptionPlanRepository;

    @InjectMocks
    CustomerSubscriptionRepositoryImpl repository;

    @Test
    void unAltaNuevaArrancaDeUnaFilaLimpiaYEngranchaUsuarioYPlan() {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().email("a@b.com").build();
        SubscriptionPlanEntity plan = new SubscriptionPlanEntity();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(subscriptionPlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(customerSubscriptionJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.save(CustomerSubscription.builder().userId(userId).planId(planId).build());

        ArgumentCaptor<CustomerSubscriptionEntity> captor = ArgumentCaptor.forClass(CustomerSubscriptionEntity.class);
        verify(customerSubscriptionJpaRepositoryAdapter).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getPlan()).isSameAs(plan);
        // Un alta no debe leer una fila previa: no la hay.
        verify(customerSubscriptionJpaRepositoryAdapter, never()).findById(any());
    }

    @Test
    void guardarSobreUnIdInexistenteFallaEnVezDeCrearUnaFilaNueva() {
        UUID id = UUID.randomUUID();
        when(customerSubscriptionJpaRepositoryAdapter.findById(id)).thenReturn(Optional.empty());
        CustomerSubscription model = CustomerSubscription.builder().id(id).build();

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class);
        verify(customerSubscriptionJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void unaModificacionReutilizaLaFilaGestionadaEnVezDeDuplicarla() {
        UUID id = UUID.randomUUID();
        CustomerSubscriptionEntity gestionada = new CustomerSubscriptionEntity();
        when(customerSubscriptionJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(gestionada));
        when(customerSubscriptionJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.update(CustomerSubscription.builder().id(id).build());

        verify(customerSubscriptionEntityMapper).updateEntity(gestionada,
                CustomerSubscription.builder().id(id).build());
        verify(customerSubscriptionJpaRepositoryAdapter).save(gestionada);
    }

    @Test
    void unUsuarioInexistenteImpideGuardarLaSuscripcion() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        CustomerSubscription model = CustomerSubscription.builder().userId(userId).build();

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User");
        verify(customerSubscriptionJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void unPlanInexistenteImpideGuardarLaSuscripcion() {
        UUID planId = UUID.randomUUID();
        when(subscriptionPlanRepository.findById(planId)).thenReturn(Optional.empty());
        CustomerSubscription model = CustomerSubscription.builder().planId(planId).build();

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Plan");
    }

    @Test
    void sinIdsDeUsuarioNiPlanLasRelacionesQuedanVaciasSinConsultarNada() {
        when(customerSubscriptionJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.save(CustomerSubscription.builder().build());

        verify(userRepository, never()).findById(any());
        verify(subscriptionPlanRepository, never()).findById(any());
    }

    @Test
    void buscarPorIdDevuelveNuloCuandoNoExisteEnVezDeReventar() {
        UUID id = UUID.randomUUID();
        when(customerSubscriptionJpaRepositoryAdapter.findById(id)).thenReturn(Optional.empty());

        assertThat(repository.getById(id)).isNull();
    }

    @Test
    void laBusquedaPorSuscripcionDeStripeDevuelveVacioSiNoEsNuestra() {
        when(customerSubscriptionJpaRepositoryAdapter.findByStripeSubscriptionId("sub_ajena"))
                .thenReturn(Optional.empty());

        assertThat(repository.findByStripeSubscriptionId("sub_ajena")).isEmpty();
    }

    @Test
    void losListadosDeleganEnElAdaptadorYPasanPorElMapeador() {
        UUID userId = UUID.randomUUID();
        when(customerSubscriptionJpaRepositoryAdapter.findAll()).thenReturn(List.of());
        when(customerSubscriptionJpaRepositoryAdapter.findByUserId(userId)).thenReturn(List.of());
        when(customerSubscriptionEntityMapper.toDomainList(List.of())).thenReturn(List.of());

        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.findByUserId(userId)).isEmpty();
        verify(customerSubscriptionJpaRepositoryAdapter).findByUserId(userId);
    }

    @Test
    void borrarYComprobarExistenciaDeleganEnElAdaptador() {
        UUID id = UUID.randomUUID();
        when(customerSubscriptionJpaRepositoryAdapter.existsById(id)).thenReturn(true);

        repository.delete(id);

        verify(customerSubscriptionJpaRepositoryAdapter).deleteById(id);
        assertThat(repository.existsById(id)).isTrue();
    }
}
