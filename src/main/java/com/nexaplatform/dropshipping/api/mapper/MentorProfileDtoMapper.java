package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MentorDtoOut;
import com.nexaplatform.dropshipping.domain.model.MentorProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for mentor profiles: translates the {@link MentorProfile}
 * domain model into the transport DTO. Injected in the controller. The
 * {@code displayName} carried by the model already falls back to the email, so
 * it is exposed verbatim. DtoOut field names preserve the exact JSON keys the
 * frontend already consumes (mirroring the legacy {@code MentorView} record).
 */
@Mapper(componentModel = "spring")
public interface MentorProfileDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "displayName", expression = "java(model.getDisplayName() != null ? model.getDisplayName() : model.getEmail())")
    @Mapping(target = "headline", source = "headline")
    @Mapping(target = "bio", source = "bio")
    @Mapping(target = "expertise", source = "expertise")
    @Mapping(target = "languages", source = "languages")
    @Mapping(target = "hourlyRateUsdCents", source = "hourlyRateUsdCents")
    @Mapping(target = "timezone", source = "timezone")
    @Mapping(target = "active", source = "active")
    MentorDtoOut toDtoOut(MentorProfile model);

    List<MentorDtoOut> toDtoOutList(List<MentorProfile> models);
}
