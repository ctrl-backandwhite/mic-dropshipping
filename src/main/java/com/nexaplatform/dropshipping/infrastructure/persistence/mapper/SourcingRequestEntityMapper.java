package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.SourcingRequest;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingRequestEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link SourcingRequest} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * domain side carries the flattened {@code userId}; the {@code user} relation is
 * resolved by the repository adapter (which owns the managed entity), so it is
 * ignored on {@code toEntity}. {@code quotesCount} is a read-only field filled by
 * the use case and has no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface SourcingRequestEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "sourceUrl", source = "sourceUrl")
    @Mapping(target = "titleHint", source = "titleHint")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "planQuota", source = "planQuota")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "selectedQuoteId", source = "selectedQuoteId")
    @Mapping(target = "quotesCount", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    SourcingRequest toDomain(SourcingRequestEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "source", source = "source")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "sourceUrl", source = "sourceUrl")
    @Mapping(target = "titleHint", source = "titleHint")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "planQuota", source = "planQuota")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "selectedQuoteId", source = "selectedQuoteId")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    SourcingRequestEntity toEntity(SourcingRequest model);

    List<SourcingRequest> toDomainList(List<SourcingRequestEntity> entities);
}
