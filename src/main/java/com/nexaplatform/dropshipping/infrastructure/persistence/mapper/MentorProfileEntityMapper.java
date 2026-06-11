package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.MentorProfile;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorProfileEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link MentorProfile} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * {@code user} relation is resolved by the repository adapter (which owns the
 * managed entity), so it is ignored on {@code toEntity}; the domain side carries
 * the flattened {@code userId} and the read-only {@code displayName}/{@code email}
 * (the use case applies the seed-account filter on {@code email}).
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface MentorProfileEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "displayName", expression = "java(entity.getUser() != null ? entity.getUser().getDisplayName() : null)")
    @Mapping(target = "email", expression = "java(entity.getUser() != null ? entity.getUser().getEmail() : null)")
    @Mapping(target = "headline", source = "headline")
    @Mapping(target = "bio", source = "bio")
    @Mapping(target = "expertise", source = "expertise")
    @Mapping(target = "languages", source = "languages")
    @Mapping(target = "hourlyRateUsdCents", source = "hourlyRateUsdCents")
    @Mapping(target = "timezone", source = "timezone")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    MentorProfile toDomain(MentorProfileEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "headline", source = "headline")
    @Mapping(target = "bio", source = "bio")
    @Mapping(target = "expertise", source = "expertise")
    @Mapping(target = "languages", source = "languages")
    @Mapping(target = "hourlyRateUsdCents", source = "hourlyRateUsdCents")
    @Mapping(target = "timezone", source = "timezone")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    MentorProfileEntity toEntity(MentorProfile model);

    List<MentorProfile> toDomainList(List<MentorProfileEntity> entities);
}
