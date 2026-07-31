package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.PaymentUpdateMapper;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Payment;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La actualización parcial de un pago copia los campos editables (estado, método, importes, proveedor,
 * referencias) y preserva identidad/auditoría.
 */
class PaymentUpdateMapperTest {

    private final PaymentUpdateMapper mapper = Mappers.getMapper(PaymentUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityAndAudit() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        Payment target = Payment.builder().id(id).status(PaymentStatus.PENDING).method(PaymentMethod.CARD)
                .amountDisplay(new BigDecimal("10.00")).currencyDisplay("EUR").provider("stripe")
                .providerRef("ref_old").createdAt(created).createdBy("creator").build();

        Payment source = Payment.builder().status(PaymentStatus.SUCCEEDED).method(PaymentMethod.PAYPAL)
                .amountDisplay(new BigDecimal("25.50")).currencyDisplay("USD").provider("paypal")
                .providerRef("ref_new").build();

        mapper.updateFromModel(source, target);

        // Editables
        assertThat(target.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(target.getMethod()).isEqualTo(PaymentMethod.PAYPAL);
        assertThat(target.getAmountDisplay()).isEqualByComparingTo("25.50");
        assertThat(target.getCurrencyDisplay()).isEqualTo("USD");
        assertThat(target.getProvider()).isEqualTo("paypal");
        assertThat(target.getProviderRef()).isEqualTo("ref_new");

        // Preservados
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getCreatedAt()).isEqualTo(created);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
    }
}
