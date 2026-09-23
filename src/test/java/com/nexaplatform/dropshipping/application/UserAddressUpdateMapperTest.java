package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.UserAddressUpdateMapper;
import com.nexaplatform.dropshipping.domain.model.UserAddress;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mapper de actualización parcial de dirección copia los campos editables (label, destinatario,
 * líneas, ciudad...) y preserva identidad, propiedad (userId), el flag default (invariante de único
 * predeterminado gestionada por el caso de uso) y la auditoría.
 */
class UserAddressUpdateMapperTest {

    private final UserAddressUpdateMapper mapper = Mappers.getMapper(UserAddressUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesOwnershipDefaultAndAudit() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        UserAddress target = UserAddress.builder().id(id).userId(userId).label("Home").fullName("Old Name").phone("000")
                .line1("Old 1").line2("Old 2").city("OldCity").state("OldState").postalCode("00000").country("ES")
                .isDefault(true).createdAt(createdAt).createdBy("creator").updatedBy("editor1").build();

        UserAddress source = UserAddress.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).label("Work")
                .fullName("New Name").phone("123").line1("New 1").line2("New 2").city("NewCity").state("NewState")
                .postalCode("11111").country("FR").isDefault(false).createdAt(Instant.parse("2099-01-01T00:00:00Z"))
                .createdBy("attacker").updatedBy("editor2").build();

        mapper.updateFromModel(source, target);

        // Editables: se actualizan
        assertThat(target.getLabel()).isEqualTo("Work");
        assertThat(target.getFullName()).isEqualTo("New Name");
        assertThat(target.getPhone()).isEqualTo("123");
        assertThat(target.getLine1()).isEqualTo("New 1");
        assertThat(target.getLine2()).isEqualTo("New 2");
        assertThat(target.getCity()).isEqualTo("NewCity");
        assertThat(target.getState()).isEqualTo("NewState");
        assertThat(target.getPostalCode()).isEqualTo("11111");
        assertThat(target.getCountry()).isEqualTo("FR");

        // Identidad / propiedad / flag default / auditoría: se preservan
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getUserId()).isEqualTo(userId);
        assertThat(target.isDefault()).isTrue();
        assertThat(target.getCreatedAt()).isEqualTo(createdAt);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
        assertThat(target.getUpdatedBy()).isEqualTo("editor1");
    }
}
