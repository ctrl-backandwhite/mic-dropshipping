package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserRoleTest {

    @Test
    void authority_uses_role_prefix() {
        assertThat(UserRole.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
        assertThat(UserRole.USER.authority()).isEqualTo("ROLE_USER");
        assertThat(UserRole.PARTNER.authority()).isEqualTo("ROLE_PARTNER");
        assertThat(UserRole.OPERATOR.authority()).isEqualTo("ROLE_OPERATOR");
    }
}
