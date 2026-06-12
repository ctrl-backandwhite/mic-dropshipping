package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletTransactionEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link WalletTransaction} domain model
 * and the JPA entity. Builder disabled so MapStruct uses setters. The managed
 * {@code wallet} relation is resolved by the repository adapter from the
 * flattened {@code walletId}, so it is ignored on {@code toEntity}; the
 * auditing-managed {@code createdAt} is ignored on {@code toEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface WalletTransactionEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "walletId", expression = "java(entity.getWallet() != null ? entity.getWallet().getId() : null)")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "balanceAfterCents", source = "balanceAfterCents")
    @Mapping(target = "paymentId", source = "paymentId")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "idempotencyKey", source = "idempotencyKey")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "metadata", source = "metadata")
    @Mapping(target = "createdAt", source = "createdAt")
    WalletTransaction toDomain(WalletTransactionEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "wallet", ignore = true)
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "balanceAfterCents", source = "balanceAfterCents")
    @Mapping(target = "paymentId", source = "paymentId")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "idempotencyKey", source = "idempotencyKey")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "metadata", source = "metadata")
    @Mapping(target = "createdAt", ignore = true)
    WalletTransactionEntity toEntity(WalletTransaction model);

    List<WalletTransaction> toDomainList(List<WalletTransactionEntity> entities);
}
