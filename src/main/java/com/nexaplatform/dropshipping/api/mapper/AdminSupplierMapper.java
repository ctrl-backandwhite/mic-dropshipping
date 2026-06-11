package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for the Admin Suppliers API boundary.
 * Assembles the supplier view from the entity plus an externally computed product
 * count and derived performance KPIs.
 * Combined with Lombok: entity getters and DtoOut builders are Lombok-generated.
 */
@Mapper(componentModel = "spring")
public interface AdminSupplierMapper {

    /**
     * Assembles the admin supplier view. Product count and derived KPIs are computed
     * by the service (aggregate query + rating-based formula) and supplied here.
     */
    default AdminSupplierDtoOut toView(SupplierEntity s, long productCount,
                                       long onTimePct, double defectRate,
                                       int responseHours, int leadTimeDays) {
        return AdminSupplierDtoOut.builder()
                .id(s.getId())
                .externalId(s.getExternalId())
                .source(s.getSource())
                .name(s.getName())
                .nameZh(s.getNameZh())
                .country(s.getCountry())
                .city(s.getCity())
                .rating(s.getRating())
                .yearsActive(s.getYearsActive())
                .verified(s.isVerified())
                .trustPass(s.isTrustPass())
                .profileUrl(s.getProfileUrl())
                .productCount(productCount)
                .onTimePct(onTimePct)
                .defectRate(defectRate)
                .responseHours(responseHours)
                .leadTimeDays(leadTimeDays)
                .build();
    }

    default AdminSupplierToggleDtoOut toVerifiedToggle(SupplierEntity s) {
        return AdminSupplierToggleDtoOut.builder().id(s.getId()).verified(s.isVerified()).build();
    }

    default AdminSupplierToggleDtoOut toTrustPassToggle(SupplierEntity s) {
        return AdminSupplierToggleDtoOut.builder().id(s.getId()).trustPass(s.isTrustPass()).build();
    }
}
