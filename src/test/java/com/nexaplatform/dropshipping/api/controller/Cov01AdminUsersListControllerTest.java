package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserInviteDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserRoleUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas del listado y las acciones masivas de usuarios del admin: el total del filtro se pide aparte
 * de la página, y ningún id malo puede abortar un lote.
 */
@ExtendWith(MockitoExtension.class)
class Cov01AdminUsersListControllerTest {

    private static final UUID ID_OK = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_KO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    UserDtoMapper mapper;
    @Mock
    UserUseCase useCase;

    @InjectMocks
    AdminUsersListController controller;

    @SuppressWarnings("unchecked")
    private static List<String> errorsOf(ResponseEntity<Map<String, Object>> resp) {
        assertThat(resp.getBody()).isNotNull();
        return (List<String>) resp.getBody().get("errors");
    }

    /* ============ listado ============ */

    @Test
    void elTotalDelListadoSePideConLosMismosFiltrosQueLaPagina() {
        // Si el conteo usara otros filtros, el paginador mostraría páginas que no existen.
        List<User> page = List.of(User.builder().build());
        AdminUserPageDtoOut expected = AdminUserPageDtoOut.builder().items(List.of()).totalElements(87).build();
        when(useCase.listUsers("ADMIN", "ana", "ES", 1, 25)).thenReturn(page);
        when(useCase.countUsers("ADMIN", "ana", "ES")).thenReturn(87);
        when(mapper.toAdminPageDtoOut(page, 87, 1, 25)).thenReturn(expected);

        ResponseEntity<AdminUserPageDtoOut> resp = controller.list("ADMIN", "ana", "ES", 1, 25);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isSameAs(expected);
    }

    /* ============ acciones individuales ============ */

    @Test
    void cambiarElRolDelegaConElRolDelCuerpo() {
        User user = User.builder().build();
        AdminUserDtoOut out = AdminUserDtoOut.builder().build();
        when(useCase.changeRole(ID_OK, "OPERATOR")).thenReturn(user);
        when(mapper.toAdminDtoOut(user)).thenReturn(out);

        ResponseEntity<AdminUserDtoOut> resp = controller.changeRole(ID_OK, new AdminUserRoleUpdateDtoIn("OPERATOR"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isSameAs(out);
    }

    @Test
    void editarUnUsuarioPasaElParcheYElIndicadorDeActivoPorSeparado() {
        // `active` viaja aparte del parche porque el mapper de actualización ignora los nulos: si fuera
        // parte del parche, desactivar a alguien (false) sería indistinguible de "no tocar".
        AdminUserEditDtoIn body = new AdminUserEditDtoIn("Ana", "Acme", "ES", "es", Boolean.FALSE);
        User patch = User.builder().build();
        User updated = User.builder().build();
        when(mapper.toDomain(body)).thenReturn(patch);
        when(useCase.editUser(ID_OK, patch, Boolean.FALSE)).thenReturn(updated);
        when(mapper.toAdminDtoOut(updated)).thenReturn(AdminUserDtoOut.builder().build());

        controller.editUser(ID_OK, body);

        verify(useCase).editUser(ID_OK, patch, Boolean.FALSE);
    }

    @Test
    void bloquearYDesbloquearDeleganConSusMinutos() {
        User user = User.builder().build();
        when(useCase.lock(ID_OK, 15)).thenReturn(user);
        when(useCase.unlock(ID_OK)).thenReturn(user);
        when(useCase.forceActivate(ID_OK)).thenReturn(user);
        when(mapper.toAdminDtoOut(user)).thenReturn(AdminUserDtoOut.builder().build());

        assertThat(controller.lock(ID_OK, 15).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.unlock(ID_OK).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.forceActivate(ID_OK).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void resetearContrasenaYBorrarUsuarioRespondenSinCuerpo() {
        ResponseEntity<Void> reset = controller.resetPassword(ID_OK);
        ResponseEntity<Void> deleted = controller.delete(ID_OK);

        assertThat(reset.getStatusCode().value()).isEqualTo(204);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        verify(useCase).adminResetPassword(ID_OK);
        verify(useCase).deleteUser(ID_OK);
    }

    @Test
    void invitarCreaUnUsuarioYDevuelve201() {
        User invited = User.builder().build();
        when(useCase.inviteUser("nuevo@test.com", "OPERATOR")).thenReturn(invited);
        when(mapper.toAdminDtoOut(invited)).thenReturn(AdminUserDtoOut.builder().build());

        ResponseEntity<AdminUserDtoOut> resp = controller
                .invite(new AdminUserInviteDtoIn("nuevo@test.com", "OPERATOR"));

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    /* ============ acciones en lote ============ */

    @Test
    void unUsuarioQueFallaEnUnLoteNoImpideProcesarElResto() {
        when(useCase.forceActivate(ID_OK)).thenReturn(User.builder().build());
        when(useCase.forceActivate(ID_KO)).thenThrow(new BusinessException("La cuenta está eliminada"));

        ResponseEntity<Map<String, Object>> resp = controller.bulkActivate(List.of(ID_KO, ID_OK));

        assertThat(resp.getBody()).containsEntry("succeeded", 1).containsEntry("failed", 1);
        assertThat(errorsOf(resp)).containsExactly(ID_KO + ": La cuenta está eliminada");
    }

    @Test
    void elBloqueoMasivoUsaLos60MinutosPorDefecto() {
        // Es el mismo plazo que el bloqueo individual del panel: si el lote usara otro, dos rutas de la
        // misma pantalla dejarían al usuario bloqueado tiempos distintos.
        when(useCase.lock(ID_OK, 60)).thenReturn(User.builder().build());

        controller.bulkLock(List.of(ID_OK));

        verify(useCase).lock(ID_OK, 60);
    }

    @Test
    void elCambioDeRolMasivoAplicaElMismoRolATodosLosIds() {
        when(useCase.changeRole(any(), any())).thenReturn(User.builder().build());

        ResponseEntity<Map<String, Object>> resp = controller
                .bulkRole(new AdminUsersListController.BulkRoleRequest(List.of(ID_OK, ID_KO), "OPERATOR"));

        assertThat(resp.getBody()).containsEntry("succeeded", 2);
        verify(useCase).changeRole(ID_OK, "OPERATOR");
        verify(useCase).changeRole(ID_KO, "OPERATOR");
    }

    @Test
    void elDesbloqueoYElBorradoMasivosDeleganEnSuOperacion() {
        when(useCase.unlock(any())).thenReturn(User.builder().build());

        controller.bulkUnlock(List.of(ID_OK, ID_KO));
        controller.bulkDelete(List.of(ID_OK));

        verify(useCase, times(2)).unlock(any());
        verify(useCase).deleteUser(ID_OK);
    }

    @Test
    void unLoteVacioDevuelveCeroYNoTocaElCasoDeUso() {
        ResponseEntity<Map<String, Object>> resp = controller.bulkDelete(List.of());

        assertThat(resp.getBody()).containsEntry("succeeded", 0).containsEntry("failed", 0);
        assertThat(errorsOf(resp)).isEmpty();
        verifyNoInteractions(useCase);
    }

    @Test
    void siFallanTodosLosIdsElLoteReportaCeroAciertosYNoSePierdeNingunMensaje() {
        doThrow(new BusinessException("Tiene pedidos asociados")).when(useCase).deleteUser(any());

        ResponseEntity<Map<String, Object>> resp = controller.bulkDelete(List.of(ID_OK, ID_KO));

        assertThat(resp.getBody()).containsEntry("succeeded", 0).containsEntry("failed", 2);
        assertThat(errorsOf(resp)).hasSize(2);
        verify(mapper, never()).toAdminDtoOut(any());
    }
}
