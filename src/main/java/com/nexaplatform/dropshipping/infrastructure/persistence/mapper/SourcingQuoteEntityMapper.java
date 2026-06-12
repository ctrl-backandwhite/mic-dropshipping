package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.SourcingQuote;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingQuoteEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link SourcingQuote} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * domain side carries the flattened {@code requestId} and the nested
 * {@link com.nexaplatform.dropshipping.domain.model.SourcingAgent}; the
 * {@code request} and {@code agent} relations are resolved by the repository
 * adapter (which owns the managed entity), so they are ignored on {@code toEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true), uses = SourcingAgentEntityMapper.class)
public interface SourcingQuoteEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "requestId", expression = "java(entity.getRequest() != null ? entity.getRequest().getId() : null)")
    @Mapping(target = "agent", source = "agent")
    @Mapping(target = "priceUsdCents", source = "priceUsdCents")
    @Mapping(target = "etaDays", source = "etaDays")
    @Mapping(target = "moq", source = "moq")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    SourcingQuote toDomain(SourcingQuoteEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "request", ignore = true)
    @Mapping(target = "agent", ignore = true)
    @Mapping(target = "priceUsdCents", source = "priceUsdCents")
    @Mapping(target = "etaDays", source = "etaDays")
    @Mapping(target = "moq", source = "moq")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    SourcingQuoteEntity toEntity(SourcingQuote model);

    List<SourcingQuote> toDomainList(List<SourcingQuoteEntity> entities);
}
