package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.infrastructure.integration.locale.LocaleHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Localización de las descripciones de los movimientos del wallet: el texto se guarda en inglés y se
 * traduce al LEER según el idioma activo (X-Lang → {@link LocaleHolder}).
 */
class MeWalletDtoMapperTest {

    private final MeWalletDtoMapper mapper = Mappers.getMapper(MeWalletDtoMapper.class);

    @AfterEach
    void reset() {
        LocaleHolder.clear();
    }

    @Test
    void rechargeDescription_localizedWithTranslatedMethod() {
        LocaleHolder.set("es");
        assertThat(mapper.localizedDescription("Wallet recharge via CARD")).isEqualTo("Recarga con Tarjeta");
        LocaleHolder.set("fr");
        assertThat(mapper.localizedDescription("Wallet recharge via CARD")).isEqualTo("Recharge par Carte");
    }

    @Test
    void refundAndOrderDescriptions_localizedKeepingOrderNumber() {
        LocaleHolder.set("es");
        assertThat(mapper.localizedDescription("Refund order NX-123")).isEqualTo("Reembolso del pedido NX-123");
        assertThat(mapper.localizedDescription("Order NX-123")).isEqualTo("Pedido NX-123");
        LocaleHolder.set("de");
        assertThat(mapper.localizedDescription("Refund order NX-123"))
                .isEqualTo("Rückerstattung für Bestellung NX-123");
        assertThat(mapper.localizedDescription("Order NX-123")).isEqualTo("Bestellung NX-123");
    }

    @Test
    void unknownDescription_returnedAsIs() {
        LocaleHolder.set("es");
        assertThat(mapper.localizedDescription("Ajuste manual del operador")).isEqualTo("Ajuste manual del operador");
        assertThat(mapper.localizedDescription(null)).isNull();
    }
}
