package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Affiliate;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link Affiliate} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * {@code user} relation is resolved by the repository adapter (which owns the
 * managed entity), so it is ignored on {@code toEntity}; the domain side carries
 * the flattened {@code userId}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface AffiliateEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "earningsUsdCents", source = "earningsUsdCents")
    @Mapping(target = "payoutUsdCents", source = "payoutUsdCents")
    @Mapping(target = "referralsCount", source = "referralsCount")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    Affiliate toDomain(AffiliateEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "earningsUsdCents", source = "earningsUsdCents")
    @Mapping(target = "payoutUsdCents", source = "payoutUsdCents")
    @Mapping(target = "referralsCount", source = "referralsCount")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    AffiliateEntity toEntity(Affiliate model);

    List<Affiliate> toDomainList(List<AffiliateEntity> entities);
}
