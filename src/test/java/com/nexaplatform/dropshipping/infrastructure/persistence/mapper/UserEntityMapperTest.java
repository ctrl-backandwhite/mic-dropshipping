package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper User(model) <-> UserEntity. El round-trip Model->Entity->Model conserva los campos escalares;
 * las sub-entidades del agregado ({@code totp}, {@code resetTokens}) y el flag computado
 * {@code totpEnabled} no tienen contraparte en la entidad y se ignoran. La auditoría se ignora en
 * toEntity (la resuelve el auditing de JPA).
 */
class UserEntityMapperTest {

    private final UserEntityMapper mapper = Mappers.getMapper(UserEntityMapper.class);

    @Test
    void roundTrip_preservesScalarFields() {
        User source = User.builder()
                .id(UUID.randomUUID())
                .email("user@nx036.local")
                .passwordHash("HASH")
                .role(UserRole.OPERATOR)
                .active(true)
                .activationCode("ACT-1")
                .activationCodeExpiresAt(Instant.parse("2026-02-01T00:00:00Z"))
                .failedLoginCount(2)
                .lockedUntil(Instant.parse("2026-02-02T00:00:00Z"))
                .lastLogin(Instant.parse("2026-02-03T00:00:00Z"))
                .displayName("Jane")
                .companyName("Acme")
                .country("ES")
                .phone("600100200")
                .avatarUrl("/a.png")
                .language("es")
                .googleLinked(true)
                .build();

        UserEntity entity = mapper.toEntity(source);
        User result = mapper.toDomain(entity);

        assertThat(result).usingRecursiveComparison()
                .ignoringFields(
                        // auditoría resuelta por JPA / ignorada en toEntity
                        "createdAt", "updatedAt", "createdBy", "updatedBy",
                        // sub-entidades / campo computado sin contraparte en la entidad
                        "totp", "resetTokens", "totpEnabled")
                .isEqualTo(source);
    }

    @Test
    void toEntity_ignoresAudit() {
        User source = User.builder()
                .id(UUID.randomUUID())
                .email("audit@nx036.local")
                .role(UserRole.ADMIN)
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .createdBy("creator")
                .updatedBy("editor")
                .build();

        UserEntity entity = mapper.toEntity(source);

        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getUpdatedBy()).isNull();
        // Campos escalares sí se copian
        assertThat(entity.getEmail()).isEqualTo("audit@nx036.local");
        assertThat(entity.getRole()).isEqualTo(UserRole.ADMIN);
    }
}
