package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SupportTicketEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupportTicketJpaRepositoryAdapter;
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
 * Adaptador de persistencia de tickets de soporte. Fija quién es el dueño de un ticket (no se puede
 * cambiar por un parche), qué pasa con el pedido asociado y qué campos se conservan cuando el parche
 * no los trae.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10SupportTicketRepositoryImplTest {

    @Mock
    SupportTicketEntityMapper supportTicketEntityMapper;
    @Mock
    SupportTicketJpaRepositoryAdapter supportTicketJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;
    @Mock
    OrderRepository orderRepository;

    @InjectMocks
    SupportTicketRepositoryImpl repository;

    @Test
    void unTicketNuevoSeEngranchaAlUsuarioQueLoAbre() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().email("a@b.com").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(supportTicketJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.save(SupportTicket.builder().userId(userId).subject("No me llega").build());

        assertThat(guardado().getUser()).isSameAs(user);
    }

    @Test
    void unTicketDeUnUsuarioInexistenteNoSeGuarda() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        SupportTicket model = SupportTicket.builder().userId(userId).build();

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class);
        verify(supportTicketJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void elDuenoDeUnTicketYaAbiertoNoSePuedeCambiarConUnParche() {
        UUID id = UUID.randomUUID();
        UserEntity dueno = UserEntity.builder().email("dueno@b.com").build();
        SupportTicketEntity gestionado = SupportTicketEntity.builder().user(dueno).build();
        when(supportTicketJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(gestionado));
        when(supportTicketJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.update(SupportTicket.builder().id(id).userId(UUID.randomUUID()).build());

        // Si se reasignara, cualquiera podría llevarse el ticket (y su historial) de otro usuario.
        assertThat(guardado().getUser()).isSameAs(dueno);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void modificarUnTicketQueYaNoExisteFallaEnVezDeCrearOtro() {
        UUID id = UUID.randomUUID();
        when(supportTicketJpaRepositoryAdapter.findById(id)).thenReturn(Optional.empty());
        SupportTicket model = SupportTicket.builder().id(id).build();

        assertThatThrownBy(() -> repository.update(model)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void elPedidoAsociadoSeResuelveCuandoElParcheTraeSuIdentificador() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        CustomerOrderEntity order = new CustomerOrderEntity();
        when(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity.builder().build()));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(supportTicketJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.save(SupportTicket.builder().userId(userId).orderId(orderId).build());

        assertThat(guardado().getOrder()).isSameAs(order);
    }

    @Test
    void unPedidoQueYaNoExisteDejaElTicketSinPedidoEnVezDeFallar() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity.builder().build()));
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());
        when(supportTicketJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.save(SupportTicket.builder().userId(userId).orderId(orderId).build());

        // Comportamiento heredado a propósito: un pedido borrado no puede bloquear la atención al cliente.
        assertThat(guardado().getOrder()).isNull();
    }

    @Test
    void sinIdentificadorDePedidoNiSeConsultaLaTablaDePedidos() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity.builder().build()));
        when(supportTicketJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.save(SupportTicket.builder().userId(userId).build());

        verify(orderRepository, never()).findById(any());
        assertThat(guardado().getOrder()).isNull();
    }

    @Test
    void unParcheSinEstadoNiPrioridadConservaLosQueYaTeniaElTicket() {
        UUID id = UUID.randomUUID();
        SupportTicketEntity gestionado = SupportTicketEntity.builder().user(UserEntity.builder().build())
                .status("IN_PROGRESS").priority("HIGH").build();
        when(supportTicketJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(gestionado));
        when(supportTicketJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.update(SupportTicket.builder().id(id).build());

        // Editar el asunto no puede reabrir un ticket ya en curso ni bajarle la prioridad.
        assertThat(guardado().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(guardado().getPriority()).isEqualTo("HIGH");
    }

    @Test
    void unParcheConEstadoYPrioridadLosAplica() {
        UUID id = UUID.randomUUID();
        SupportTicketEntity gestionado = SupportTicketEntity.builder().user(UserEntity.builder().build()).status("OPEN")
                .priority("NORMAL").build();
        when(supportTicketJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(gestionado));
        when(supportTicketJpaRepositoryAdapter.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repository.update(SupportTicket.builder().id(id).status("CLOSED").priority("LOW").build());

        assertThat(guardado().getStatus()).isEqualTo("CLOSED");
        assertThat(guardado().getPriority()).isEqualTo("LOW");
    }

    @Test
    void buscarPorIdDevuelveNuloCuandoNoExisteEnVezDeReventar() {
        UUID id = UUID.randomUUID();
        when(supportTicketJpaRepositoryAdapter.findById(id)).thenReturn(Optional.empty());

        assertThat(repository.getById(id)).isNull();
    }

    @Test
    void losListadosUsanLasConsultasOrdenadasDelMasNuevoAlMasViejo() {
        UUID userId = UUID.randomUUID();
        when(supportTicketJpaRepositoryAdapter.findAll()).thenReturn(List.of());
        when(supportTicketJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(supportTicketJpaRepositoryAdapter.findByStatusOrderByCreatedAtDesc("OPEN")).thenReturn(List.of());
        when(supportTicketEntityMapper.toDomainList(List.of())).thenReturn(List.of());

        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.findByUserId(userId)).isEmpty();
        assertThat(repository.findByStatus("OPEN")).isEmpty();

        verify(supportTicketJpaRepositoryAdapter).findByUser_IdOrderByCreatedAtDesc(userId);
        verify(supportTicketJpaRepositoryAdapter).findByStatusOrderByCreatedAtDesc("OPEN");
    }

    @Test
    void borrarYComprobarExistenciaDeleganEnElAdaptador() {
        UUID id = UUID.randomUUID();
        when(supportTicketJpaRepositoryAdapter.existsById(id)).thenReturn(false);

        repository.delete(id);

        verify(supportTicketJpaRepositoryAdapter).deleteById(id);
        assertThat(repository.existsById(id)).isFalse();
    }

    private SupportTicketEntity guardado() {
        ArgumentCaptor<SupportTicketEntity> captor = ArgumentCaptor.forClass(SupportTicketEntity.class);
        verify(supportTicketJpaRepositoryAdapter).save(captor.capture());
        return captor.getValue();
    }
}
