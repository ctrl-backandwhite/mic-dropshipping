package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.UserUpdateMapper;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mapper de actualización parcial NUNCA debe tocar los campos sensibles (email, passwordHash, role,
 * active, totp/totpEnabled, identidad/auditoría): solo los datos de perfil editables. Verificación
 * campo a campo.
 */
class UserUpdateMapperTest {

    private final UserUpdateMapper mapper = Mappers.getMapper(UserUpdateMapper.class);

    @Test
    void updateFromModel_copiesProfileFieldsAndPreservesSecuritySensitiveOnes() {
        UUID id = UUID.randomUUID();
        User target = User.builder().id(id).email("real@nx036.local").passwordHash("HASH").role(UserRole.ADMIN)
                .active(true).totpEnabled(true).avatarUrl("/avatar.png").displayName("Old Name")
                .companyName("Old Co").country("ES").phone("000").language("es").createdBy("creator").build();

        User source = User.builder().email("attacker@evil.com").passwordHash("PWNED").role(UserRole.USER)
                .active(false).totpEnabled(false).avatarUrl("/evil.png").displayName("New Name")
                .companyName("New Co").country("FR").phone("123").language("en").build();

        mapper.updateFromModel(source, target);

        // Editables: se actualizan
        assertThat(target.getDisplayName()).isEqualTo("New Name");
        assertThat(target.getCompanyName()).isEqualTo("New Co");
        assertThat(target.getCountry()).isEqualTo("FR");
        assertThat(target.getPhone()).isEqualTo("123");
        assertThat(target.getLanguage()).isEqualTo("en");

        // Sensibles / identidad / auditoría: se preservan
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getEmail()).isEqualTo("real@nx036.local");
        assertThat(target.getPasswordHash()).isEqualTo("HASH");
        assertThat(target.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(target.isActive()).isTrue();
        assertThat(target.isTotpEnabled()).isTrue();
        assertThat(target.getAvatarUrl()).isEqualTo("/avatar.png");
        assertThat(target.getCreatedBy()).isEqualTo("creator");
    }

    @Test
    void updateFromModel_ignoresNullProfileFields() {
        User target = User.builder().displayName("Keep").companyName("KeepCo").build();
        User source = User.builder().displayName(null).companyName("Changed").build();

        mapper.updateFromModel(source, target);

        assertThat(target.getDisplayName()).isEqualTo("Keep"); // null source no pisa
        assertThat(target.getCompanyName()).isEqualTo("Changed");
    }
}
