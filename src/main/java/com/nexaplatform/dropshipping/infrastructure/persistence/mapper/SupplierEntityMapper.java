package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Supplier;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link Supplier} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * read-only/computed fields of the model (product count and KPIs) have no entity
 * counterpart and are filled by the use case, not by this mapper.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface SupplierEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "yearsActive", source = "yearsActive")
    @Mapping(target = "verified", source = "verified")
    @Mapping(target = "trustPass", source = "trustPass")
    @Mapping(target = "profileUrl", source = "profileUrl")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    @Mapping(target = "productCount", ignore = true)
    @Mapping(target = "onTimePct", ignore = true)
    @Mapping(target = "defectRate", ignore = true)
    @Mapping(target = "responseHours", ignore = true)
    @Mapping(target = "leadTimeDays", ignore = true)
    Supplier toDomain(SupplierEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "yearsActive", source = "yearsActive")
    @Mapping(target = "verified", source = "verified")
    @Mapping(target = "trustPass", source = "trustPass")
    @Mapping(target = "profileUrl", source = "profileUrl")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    SupplierEntity toEntity(Supplier model);

    List<Supplier> toDomainList(List<SupplierEntity> entities);
}
