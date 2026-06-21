package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.WalletUpdateMapper;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mapper de actualización parcial de wallet copia los campos editables (moneda por defecto,
 * estado...) y preserva identidad y auditoría.
 */
class WalletUpdateMapperTest {

    private final WalletUpdateMapper mapper = Mappers.getMapper(WalletUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityAndAudit() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        Wallet target = Wallet.builder()
                .id(id).userId(UUID.randomUUID())
                .balanceUsdCents(1000L).holdUsdCents(200L).currencyDefault("USD").status("ACTIVE")
                .createdAt(createdAt).createdBy("creator").updatedBy("editor1")
                .build();

        Wallet source = Wallet.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .balanceUsdCents(5000L).holdUsdCents(0L).currencyDefault("EUR").status("FROZEN")
                .createdAt(Instant.parse("2099-01-01T00:00:00Z")).createdBy("attacker").updatedBy("editor2")
                .build();

        mapper.updateFromModel(source, target);

        // Editables: se actualizan
        assertThat(target.getCurrencyDefault()).isEqualTo("EUR");
        assertThat(target.getStatus()).isEqualTo("FROZEN");

        // Identidad / auditoría: se preservan
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getCreatedAt()).isEqualTo(createdAt);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
        assertThat(target.getUpdatedBy()).isEqualTo("editor1");
    }
}
