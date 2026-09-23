package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.UserEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.UserEntityMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
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
 * Reglas del adaptador {@link UserRepositoryImpl}: qué se conserva al guardar sobre una entidad ya
 * gestionada (auditoría, marketingOptOut) y qué se copia siempre a mano (credenciales y rol), más el
 * orden del listado de admin.
 *
 * <p>Se usa el mapper de MapStruct REAL: el valor de estos tests está justamente en comprobar qué
 * campos deja intactos el auto-mapeo, y un mapper simulado no probaría nada de eso.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov02UserRepositoryImplTest {

    @Mock
    private com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userJpaRepository;

    private UserRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        UserEntityMapper mapper = new UserEntityMapperImpl();
        repository = new UserRepositoryImpl(mapper, userJpaRepository);
    }

    private static User model(UUID id, String email) {
        return User.builder().id(id).email(email).role(UserRole.USER).active(true).passwordHash("$2a$10$hash")
                .displayName("Ana").build();
    }

    @Test
    void guardarUsuarioNuevoCreaEntidadDesdeCero() {
        when(userJpaRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = repository.save(model(null, "nuevo@nx.com"));

        assertThat(saved.getEmail()).isEqualTo("nuevo@nx.com");
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        // Un alta no debe consultar la BD buscando una entidad que todavía no existe.
        verify(userJpaRepository, never()).findById(any());
    }

    @Test
    void guardarUsuarioConIdInexistenteFallaEnVezDeInsertarloDeNuevo() {
        // Si el id llega de fuera y ya no está en BD, insertar en silencio duplicaría cuentas borradas.
        UUID id = UUID.randomUUID();
        when(userJpaRepository.findById(id)).thenReturn(Optional.empty());
        User user = model(id, "fantasma@nx.com");

        assertThatThrownBy(() -> repository.save(user)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void guardarSobreUsuarioExistenteConservaLaAuditoriaYLaPreferenciaDeMarketing() {
        UUID id = UUID.randomUUID();
        Instant creado = Instant.parse("2024-01-01T00:00:00Z");
        UserEntity gestionada = new UserEntity();
        gestionada.setId(id);
        gestionada.setEmail("viejo@nx.com");
        gestionada.setCreatedAt(creado);
        gestionada.setCreatedBy("registro");
        gestionada.setMarketingOptOut(true);
        when(userJpaRepository.findById(id)).thenReturn(Optional.of(gestionada));
        when(userJpaRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        repository.save(model(id, "nuevo@nx.com"));

        assertThat(gestionada.getEmail()).isEqualTo("nuevo@nx.com");
        assertThat(gestionada.getCreatedAt()).isEqualTo(creado);
        assertThat(gestionada.getCreatedBy()).isEqualTo("registro");
        // La baja de los correos de campaña la gestiona su propio flujo: editar el perfil no la revierte.
        assertThat(gestionada.isMarketingOptOut()).isTrue();
    }

    @Test
    void guardarCopiaSiempreCredencialYRolPorqueElAutoMapeoLosIgnora() {
        UUID id = UUID.randomUUID();
        UserEntity gestionada = new UserEntity();
        gestionada.setId(id);
        gestionada.setPasswordHash("hash-viejo");
        gestionada.setRole(UserRole.USER);
        when(userJpaRepository.findById(id)).thenReturn(Optional.of(gestionada));
        when(userJpaRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        User promocionado = model(id, "admin@nx.com").withRole(UserRole.ADMIN).withPasswordHash("hash-nuevo");
        repository.save(promocionado);

        assertThat(gestionada.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(gestionada.getPasswordHash()).isEqualTo("hash-nuevo");
    }

    @Test
    void actualizarEsElMismoCaminoQueGuardar() {
        UUID id = UUID.randomUUID();
        UserEntity gestionada = new UserEntity();
        gestionada.setId(id);
        when(userJpaRepository.findById(id)).thenReturn(Optional.of(gestionada));
        when(userJpaRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        User actualizado = repository.update(model(id, "update@nx.com"));

        assertThat(actualizado.getEmail()).isEqualTo("update@nx.com");
        verify(userJpaRepository).findById(id);
    }

    @Test
    void listadoDeAdminDevuelveLosMasRecientesPrimeroYLosSinFechaAlFinal() {
        UserEntity viejo = new UserEntity();
        viejo.setId(UUID.randomUUID());
        viejo.setEmail("viejo@nx.com");
        viejo.setCreatedAt(Instant.parse("2023-01-01T00:00:00Z"));
        UserEntity nuevo = new UserEntity();
        nuevo.setId(UUID.randomUUID());
        nuevo.setEmail("nuevo@nx.com");
        nuevo.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        UserEntity sinFecha = new UserEntity();
        sinFecha.setId(UUID.randomUUID());
        sinFecha.setEmail("sinfecha@nx.com");
        when(userJpaRepository.findAll()).thenReturn(List.of(viejo, sinFecha, nuevo));

        List<User> todos = repository.findAll();

        assertThat(todos).extracting(User::getEmail).containsExactly("nuevo@nx.com", "viejo@nx.com", "sinfecha@nx.com");
    }

    @Test
    void getByIdDevuelveNuloSiNoExisteEnVezDeLanzar() {
        // Contrato del puerto: quien llama decide si un usuario ausente es error (login) o no (limpiezas).
        UUID id = UUID.randomUUID();
        when(userJpaRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(repository.getById(id)).isNull();
    }

    @Test
    void buscarPorEmailYPorCodigoDeActivacionDevuelvenVacioSiNoHayCoincidencia() {
        when(userJpaRepository.findByEmail("nadie@nx.com")).thenReturn(Optional.empty());
        when(userJpaRepository.findByActivationCode("XXX")).thenReturn(Optional.empty());

        assertThat(repository.findByEmail("nadie@nx.com")).isEmpty();
        assertThat(repository.findByActivationCode("XXX")).isEmpty();
    }

    @Test
    void buscarPorEmailMapeaAlModeloDeDominio() {
        UserEntity entidad = new UserEntity();
        entidad.setId(UUID.randomUUID());
        entidad.setEmail("ana@nx.com");
        entidad.setRole(UserRole.ADMIN);
        when(userJpaRepository.findByEmail("ana@nx.com")).thenReturn(Optional.of(entidad));

        Optional<User> encontrado = repository.findByEmail("ana@nx.com");

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void existenciaYBorradoDeleganEnElRepositorioJpa() {
        UUID id = UUID.randomUUID();
        when(userJpaRepository.existsByEmail("ana@nx.com")).thenReturn(true);
        when(userJpaRepository.existsById(id)).thenReturn(true);

        assertThat(repository.existsByEmail("ana@nx.com")).isTrue();
        assertThat(repository.existsById(id)).isTrue();
        repository.delete(id);
        verify(userJpaRepository).deleteById(id);
    }
}
