package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.api.dto.in.CreateAdminUserDtoIn;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserRoleTest {

    @Test
    void authority_uses_role_prefix() {
        assertThat(UserRole.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
        assertThat(UserRole.USER.authority()).isEqualTo("ROLE_USER");
        assertThat(UserRole.PARTNER.authority()).isEqualTo("ROLE_PARTNER");
        assertThat(UserRole.OPERATOR.authority()).isEqualTo("ROLE_OPERATOR");
        assertThat(UserRole.REVIEWER.authority()).isEqualTo("ROLE_REVIEWER");
    }

    /**
     * El patrón de {@code CreateAdminUserDtoIn} repite los valores del enum a mano porque
     * {@code @Pattern} exige una constante de compilación. Si alguien añade un rol y se olvida de la
     * anotación, la cuenta no se puede crear desde el panel y el error que se ve es un 400 genérico de
     * validación, que no dice qué falta. Esta prueba lo dice.
     */
    @Test
    void createAdminUserDto_pattern_accepts_every_role() throws Exception {
        String patron = CreateAdminUserDtoIn.class.getDeclaredField("role")
                .getAnnotation(Pattern.class).regexp();
        for (UserRole rol : UserRole.values()) {
            assertThat(rol.name()).as("el rol %s falta en el patrón %s", rol, patron).matches(patron);
        }
    }
}
