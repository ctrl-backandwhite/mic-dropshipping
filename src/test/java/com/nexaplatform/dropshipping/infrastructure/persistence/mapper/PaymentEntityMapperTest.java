package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper Payment(model) <-> PaymentEntity. El round-trip Model->Entity->Model conserva los campos
 * escalares; las relaciones gestionadas se ignoran en toEntity, por lo que {@code userId},
 * {@code walletId} y el enriquecimiento {@code userEmail} (derivados de user/wallet) no vuelven. La
 * auditoría se ignora en toEntity.
 */
class PaymentEntityMapperTest {

    private final PaymentEntityMapper mapper = Mappers.getMapper(PaymentEntityMapper.class);

    @Test
    void roundTrip_preservesScalarFields() {
        Map<String, Object> providerResponse = new HashMap<>();
        providerResponse.put("intent", "pi_123");

        Payment source = Payment.builder().id(UUID.randomUUID()).orderId(UUID.randomUUID()).purpose("ORDER_PAYMENT")
                .method(PaymentMethod.CARD).status(PaymentStatus.SUCCEEDED).amountDisplay(new BigDecimal("12.3400"))
                .currencyDisplay("EUR").amountUsdCents(1334L).settlementCurrency("USD")
                .settlementAmount(new BigDecimal("13.3400")).provider("stripe").providerRef("pi_123")
                .providerResponse(providerResponse).idempotencyKey("idem-1").cryptoAddress(null).cryptoChain(null)
                .cryptoExpiresAt(null).qrUrl(null).errorMessage(null).build();

        PaymentEntity entity = mapper.toEntity(source);
        Payment result = mapper.toDomain(entity);

        assertThat(result).usingRecursiveComparison().ignoringFields(
                // auditoría resuelta por JPA / ignorada en toEntity
                "createdAt", "updatedAt", "createdBy", "updatedBy",
                // derivados de relaciones gestionadas (user/wallet) ignoradas en toEntity
                "userId", "walletId", "userEmail").isEqualTo(source);
    }

    @Test
    void toEntity_ignoresAuditAndManagedRelations() {
        Payment source = Payment.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).walletId(UUID.randomUUID())
                .method(PaymentMethod.PAYPAL).status(PaymentStatus.PENDING).settlementCurrency("USD")
                .userEmail("buyer@nx036.local").createdAt(Instant.now()).updatedAt(Instant.now()).createdBy("creator")
                .updatedBy("editor").build();

        PaymentEntity entity = mapper.toEntity(source);

        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getUpdatedBy()).isNull();
        // user/wallet las resuelve el repositorio desde userId/walletId
        assertThat(entity.getUser()).isNull();
        assertThat(entity.getWallet()).isNull();
    }
}
