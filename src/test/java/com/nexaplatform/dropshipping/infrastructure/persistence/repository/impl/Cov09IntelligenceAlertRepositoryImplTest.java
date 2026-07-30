package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.IntelligenceAlertEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.IntelligenceAlertEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.IntelligenceAlertJpaRepositoryAdapter;
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
 * Adaptador de persistencia de las alertas de inteligencia: resuelve contra la base de datos las
 * relaciones que el modelo de dominio lleva aplanadas (el dueño y la categoría, por id).
 *
 * <p>Asimetría deliberada: el DUEÑO es obligatorio (sin él la alerta no se puede guardar), mientras que
 * una categoría que ya no existe se tolera y se deja a nulo — la alerta sigue sirviendo sin ella.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09IntelligenceAlertRepositoryImplTest {

    @Mock
    IntelligenceAlertEntityMapper intelligenceAlertEntityMapper;
    @Mock
    IntelligenceAlertJpaRepositoryAdapter intelligenceAlertJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;
    @Mock
    CategoryRepository categoryRepository;
    @InjectMocks
    IntelligenceAlertRepositoryImpl subject;

    private final UUID alertId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final UUID userId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final UUID categoryId = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private IntelligenceAlert model(UUID id, UUID userId, UUID categoryId) {
        return IntelligenceAlert.builder().id(id).userId(userId).categoryId(categoryId).keyword("bolso")
                .channel("EMAIL").active(true).build();
    }

    private IntelligenceAlertEntity guardada() {
        ArgumentCaptor<IntelligenceAlertEntity> captor = ArgumentCaptor.forClass(IntelligenceAlertEntity.class);
        verify(intelligenceAlertJpaRepositoryAdapter).save(captor.capture());
        return captor.getValue();
    }

    private void usuarioExiste(UserEntity user) {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    // ---------------------------------------------------------------- dueño de la alerta

    @Test
    void laAlertaSeGuardaColgandoDelUsuarioResueltoPorSuIdentificador() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        usuarioExiste(user);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        subject.save(model(null, userId, categoryId));

        assertThat(guardada().getUser()).isSameAs(user);
    }

    @Test
    void unaAlertaDeUnUsuarioQueNoExisteNoSeGuarda() {
        // Guardarla sin dueño reventaría más tarde contra la columna NOT NULL, lejos de la causa.
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        IntelligenceAlert modelo = model(null, userId, null);

        assertThatThrownBy(() -> subject.save(modelo)).isInstanceOf(NotFoundException.class);

        verify(intelligenceAlertJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void sinIdentificadorDeUsuarioLaAlertaSeQuedaSinDuenoYNoSeConsultaNada() {
        subject.save(model(null, null, null));

        assertThat(guardada().getUser()).isNull();
        verify(userRepository, never()).findById(any());
    }

    // ---------------------------------------------------------------- categoría opcional

    @Test
    void unaCategoriaQueYaNoExisteSeToleraYLaAlertaSeGuardaSinElla() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        usuarioExiste(user);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        subject.save(model(null, userId, categoryId));

        assertThat(guardada().getCategory()).isNull();
    }

    @Test
    void laCategoriaSeResuelveCuandoExiste() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        usuarioExiste(user);
        CategoryEntity categoria = new CategoryEntity();
        categoria.setId(categoryId);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(categoria));

        subject.save(model(null, userId, categoryId));

        assertThat(guardada().getCategory()).isSameAs(categoria);
    }

    @Test
    void sinCategoriaNiSeConsultaElCatalogo() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        usuarioExiste(user);

        subject.save(model(null, userId, null));

        assertThat(guardada().getCategory()).isNull();
        verify(categoryRepository, never()).findById(any());
    }

    // ---------------------------------------------------------------- entidad gestionada

    @Test
    void editarUnaAlertaExistenteReutilizaLaFilaGestionada() {
        // Reutilizar la entidad gestionada es lo que hace que sea un UPDATE y no un INSERT duplicado.
        UserEntity user = new UserEntity();
        user.setId(userId);
        usuarioExiste(user);
        IntelligenceAlertEntity gestionada = new IntelligenceAlertEntity();
        gestionada.setId(alertId);
        when(intelligenceAlertJpaRepositoryAdapter.findById(alertId)).thenReturn(Optional.of(gestionada));

        subject.save(model(alertId, userId, null));

        assertThat(guardada()).isSameAs(gestionada);
    }

    @Test
    void unaAlertaCuyoIdentificadorYaNoExisteNoSeRecrea() {
        // Antes se caía a una entidad limpia: editar una alerta borrada entre medias la RECREABA con otro
        // id, así que el usuario acababa con una copia que ya no era la que tenía delante.
        UserEntity user = new UserEntity();
        user.setId(userId);
        usuarioExiste(user);
        when(intelligenceAlertJpaRepositoryAdapter.findById(alertId)).thenReturn(Optional.empty());

        IntelligenceAlert modelo = model(alertId, userId, null);
        assertThatThrownBy(() -> subject.save(modelo)).isInstanceOf(NotFoundException.class);

        verify(intelligenceAlertJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void actualizarEsExactamenteLoMismoQueGuardar() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        usuarioExiste(user);
        IntelligenceAlertEntity gestionada = new IntelligenceAlertEntity();
        gestionada.setId(alertId);
        when(intelligenceAlertJpaRepositoryAdapter.findById(alertId)).thenReturn(Optional.of(gestionada));

        subject.update(model(alertId, userId, null));

        assertThat(guardada()).isSameAs(gestionada);
    }

    // ---------------------------------------------------------------- lecturas

    @Test
    void elListadoDeAlertasActivasSePideSoloParaEseUsuario() {
        // Devolver las de otro usuario filtraría qué vigila la competencia.
        IntelligenceAlertEntity entidad = new IntelligenceAlertEntity();
        when(intelligenceAlertJpaRepositoryAdapter.findByUser_IdAndActiveTrue(userId)).thenReturn(List.of(entidad));
        IntelligenceAlert esperada = model(alertId, userId, null);
        when(intelligenceAlertEntityMapper.toDomainList(List.of(entidad))).thenReturn(List.of(esperada));

        assertThat(subject.findActiveByUser(userId)).containsExactly(esperada);
    }

    @Test
    void elListadoCompletoDelegaEnElAdaptador() {
        when(intelligenceAlertJpaRepositoryAdapter.findAll()).thenReturn(List.of());
        when(intelligenceAlertEntityMapper.toDomainList(List.of())).thenReturn(List.of());

        assertThat(subject.findAll()).isEmpty();
    }

    @Test
    void pedirUnaAlertaQueNoExisteDevuelveNuloYNoRevienta() {
        when(intelligenceAlertJpaRepositoryAdapter.findById(alertId)).thenReturn(Optional.empty());

        assertThat(subject.getById(alertId)).isNull();
    }

    @Test
    void borrarYComprobarExistenciaDeleganEnElAdaptador() {
        when(intelligenceAlertJpaRepositoryAdapter.existsById(alertId)).thenReturn(true);

        subject.delete(alertId);

        assertThat(subject.existsById(alertId)).isTrue();
        verify(intelligenceAlertJpaRepositoryAdapter).deleteById(alertId);
    }
}
