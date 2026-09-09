package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link Payment} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * managed {@code user}/{@code wallet} relations are resolved by the repository
 * adapter from the flattened {@code userId}/{@code walletId}, so they are
 * ignored on {@code toEntity}; {@code userEmail} is read-only enrichment filled
 * by the use case and has no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface PaymentEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "walletId", expression = "java(entity.getWallet() != null ? entity.getWallet().getId() : null)")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "purpose", source = "purpose")
    @Mapping(target = "clientTarget", source = "clientTarget")
    @Mapping(target = "method", source = "method")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "amountDisplay", source = "amountDisplay")
    @Mapping(target = "currencyDisplay", source = "currencyDisplay")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "settlementCurrency", source = "settlementCurrency")
    @Mapping(target = "settlementAmount", source = "settlementAmount")
    @Mapping(target = "provider", source = "provider")
    @Mapping(target = "providerRef", source = "providerRef")
    @Mapping(target = "providerResponse", source = "providerResponse")
    @Mapping(target = "idempotencyKey", source = "idempotencyKey")
    @Mapping(target = "cryptoAddress", source = "cryptoAddress")
    @Mapping(target = "cryptoChain", source = "cryptoChain")
    @Mapping(target = "cryptoExpiresAt", source = "cryptoExpiresAt")
    @Mapping(target = "qrUrl", source = "qrUrl")
    @Mapping(target = "errorMessage", source = "errorMessage")
    @Mapping(target = "userEmail", expression = "java(entity.getUser() != null ? entity.getUser().getEmail() : null)")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    Payment toDomain(PaymentEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "wallet", ignore = true)
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "purpose", source = "purpose")
    @Mapping(target = "clientTarget", source = "clientTarget")
    @Mapping(target = "method", source = "method")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "amountDisplay", source = "amountDisplay")
    @Mapping(target = "currencyDisplay", source = "currencyDisplay")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "settlementCurrency", source = "settlementCurrency")
    @Mapping(target = "settlementAmount", source = "settlementAmount")
    @Mapping(target = "provider", source = "provider")
    @Mapping(target = "providerRef", source = "providerRef")
    @Mapping(target = "providerResponse", source = "providerResponse")
    @Mapping(target = "idempotencyKey", source = "idempotencyKey")
    @Mapping(target = "cryptoAddress", source = "cryptoAddress")
    @Mapping(target = "cryptoChain", source = "cryptoChain")
    @Mapping(target = "cryptoExpiresAt", source = "cryptoExpiresAt")
    @Mapping(target = "qrUrl", source = "qrUrl")
    @Mapping(target = "errorMessage", source = "errorMessage")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    PaymentEntity toEntity(Payment model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización).
     * Las relaciones gestionadas ({@code user}/{@code wallet}) y la auditoría las resuelve el
     * repositorio, por eso se ignoran. {@code providerResponse} es un Map/JSON con converter especial
     * y {@code purpose} conserva null-handling condicional; ambos se dejan manuales. MapStruct
     * auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "wallet", ignore = true)
    @Mapping(target = "purpose", ignore = true)
    @Mapping(target = "providerResponse", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget PaymentEntity entity, Payment model);

    List<Payment> toDomainList(List<PaymentEntity> entities);
}
