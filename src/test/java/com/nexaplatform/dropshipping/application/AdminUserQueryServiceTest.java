package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserRoleUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminUserMapper;
import com.nexaplatform.dropshipping.application.service.AdminUserQueryService;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserQueryServiceTest {

    @Mock UserRepository userRepository;
    @Mock AdminUserMapper adminUserMapper;

    @Captor ArgumentCaptor<List<UserEntity>> usersCaptor;

    private AdminUserQueryService service() {
        return new AdminUserQueryService(userRepository, adminUserMapper);
    }

    @Test
    void listUsers_filtersByRolePaginatesAndDelegatesToMapper() {
        var admin = UserEntity.builder().email("a@x.com").role(UserRole.ADMIN).build();
        var user = UserEntity.builder().email("u@x.com").role(UserRole.USER).build();
        when(userRepository.findAll()).thenReturn(List.of(admin, user));
        when(adminUserMapper.toDtos(any())).thenReturn(List.of());

        AdminUserPageDtoOut result = service().listUsers("admin", null, null, 0, 25);

        verify(adminUserMapper).toDtos(usersCaptor.capture());
        assertThat(usersCaptor.getValue()).containsExactly(admin);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getPage()).isZero();
        assertThat(result.getSize()).isEqualTo(25);
    }

    @Test
    void changeRole_updatesRoleAndSaves() {
        UUID id = UUID.randomUUID();
        var u = UserEntity.builder().email("u@x.com").role(UserRole.USER).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(adminUserMapper.toDto(u)).thenReturn(AdminUserDtoOut.builder().build());

        service().changeRole(id, new AdminUserRoleUpdateDtoIn("admin"));

        assertThat(u.getRole()).isEqualTo(UserRole.ADMIN);
        verify(userRepository).save(u);
    }

    @Test
    void editUser_appliesPartialUpdateAndActiveFlagThenSaves() {
        UUID id = UUID.randomUUID();
        var u = UserEntity.builder().email("u@x.com").role(UserRole.USER).active(false).build();
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(adminUserMapper.toDto(u)).thenReturn(AdminUserDtoOut.builder().build());
        var dto = new AdminUserEditDtoIn("New Name", null, null, null, true);

        service().editUser(id, dto);

        verify(adminUserMapper).updateUserFromDto(dto, u);
        assertThat(u.isActive()).isTrue();
        verify(userRepository).save(u);
    }
}
