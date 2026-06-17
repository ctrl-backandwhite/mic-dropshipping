package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AdminSupplierUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import com.nexaplatform.dropshipping.domain.model.Supplier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the Admin Suppliers boundary: translates the
 * {@link Supplier} domain model into the transport DTOs. Injected in the
 * controller. The model already carries the product count and rating-derived
 * KPIs (filled by the use case); the toggle outputs expose only the flipped flag
 * so each endpoint serializes exactly {@code {id, verified}} or {@code {id, trustPass}}.
 */
@Mapper(componentModel = "spring")
public interface AdminSupplierMapper {

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
    @Mapping(target = "productCount", source = "productCount")
    @Mapping(target = "onTimePct", source = "onTimePct")
    @Mapping(target = "defectRate", source = "defectRate")
    @Mapping(target = "responseHours", source = "responseHours")
    @Mapping(target = "leadTimeDays", source = "leadTimeDays")
    AdminSupplierDtoOut toDtoOut(Supplier model);

    List<AdminSupplierDtoOut> toDtoOutList(List<Supplier> models);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "verified", source = "verified")
    @Mapping(target = "trustPass", ignore = true)
    AdminSupplierToggleDtoOut toVerifiedToggle(Supplier model);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "verified", ignore = true)
    @Mapping(target = "trustPass", source = "trustPass")
    AdminSupplierToggleDtoOut toTrustPassToggle(Supplier model);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "externalId", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "nameZh", source = "nameZh")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "yearsActive", source = "yearsActive")
    @Mapping(target = "verified", source = "verified")
    @Mapping(target = "trustPass", source = "trustPass")
    @Mapping(target = "profileUrl", source = "profileUrl")
    Supplier toDomain(AdminSupplierUpsertDtoIn req);

}
