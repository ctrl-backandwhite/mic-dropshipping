package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.UserAddress;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link UserAddress} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * {@code user} relation is flattened to {@code userId} on the way out and resolved
 * by the repository adapter on the way in (so it is ignored here on {@code toEntity}).
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface UserAddressEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "label", source = "label")
    @Mapping(target = "fullName", source = "fullName")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "line1", source = "line1")
    @Mapping(target = "line2", source = "line2")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "state", source = "state")
    @Mapping(target = "postalCode", source = "postalCode")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "default", source = "default")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    UserAddress toDomain(UserAddressEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "label", source = "label")
    @Mapping(target = "fullName", source = "fullName")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "line1", source = "line1")
    @Mapping(target = "line2", source = "line2")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "state", source = "state")
    @Mapping(target = "postalCode", source = "postalCode")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "default", source = "default")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    UserAddressEntity toEntity(UserAddress model);

    List<UserAddress> toDomainList(List<UserAddressEntity> entities);
}
