package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.user.DropshippingUserDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceTest {

    @Mock
    UserRepository userRepository;

    @Test
    void loads_active_admin_with_role_authority() {
        UserEntity u = UserEntity.builder().email("admin@x.com").role(UserRole.ADMIN).active(true)
                .passwordHash("$2a$10$abcdef").build();
        u.setId(UUID.randomUUID());
        when(userRepository.findByEmail("admin@x.com")).thenReturn(Optional.of(u));

        UserDetails details = new DropshippingUserDetailsService(userRepository).loadUserByUsername("admin@x.com");
        assertThat(details.getUsername()).isEqualTo(u.getId().toString());
        assertThat(details.getAuthorities()).extracting(Object::toString).contains("ROLE_ADMIN");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void disabled_when_inactive_user() {
        UserEntity u = UserEntity.builder().email("u@x.com").role(UserRole.USER).active(false).passwordHash("$2a$10$x")
                .build();
        u.setId(UUID.randomUUID());
        when(userRepository.findByEmail("u@x.com")).thenReturn(Optional.of(u));
        var d = new DropshippingUserDetailsService(userRepository).loadUserByUsername("u@x.com");
        assertThat(d.isEnabled()).isFalse();
    }

    @Test
    void locked_when_lockedUntil_in_future() {
        UserEntity u = UserEntity.builder().email("u@x.com").role(UserRole.USER).active(true).passwordHash("$2a$10$x")
                .lockedUntil(Instant.now().plusSeconds(600)).build();
        u.setId(UUID.randomUUID());
        when(userRepository.findByEmail("u@x.com")).thenReturn(Optional.of(u));
        var d = new DropshippingUserDetailsService(userRepository).loadUserByUsername("u@x.com");
        assertThat(d.isAccountNonLocked()).isFalse();
    }

    @Test
    void throws_when_unknown() {
        when(userRepository.findByEmail("missing@x.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new DropshippingUserDetailsService(userRepository).loadUserByUsername("missing@x.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
