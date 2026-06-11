package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardMetricsDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardRecentOrderDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardSeriesDtoOut;
import com.nexaplatform.dropshipping.domain.model.DashboardMetrics;
import com.nexaplatform.dropshipping.domain.model.DashboardRecentOrder;
import com.nexaplatform.dropshipping.domain.model.DashboardSeries;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * API-layer mapper for the admin dashboard: translates the domain projection models
 * ({@link DashboardMetrics}, {@link DashboardSeries}, {@link DashboardRecentOrder})
 * into the transport DtoOuts. Injected in the controller. All fields are 1:1, so
 * MapStruct maps them by name; DtoOut field names preserve the exact JSON keys the
 * frontend already consumes.
 */
@Mapper(componentModel = "spring")
public interface AdminDashboardMapper {

    AdminDashboardMetricsDtoOut toDtoOut(DashboardMetrics model);

    AdminDashboardSeriesDtoOut toDtoOut(DashboardSeries model);

    AdminDashboardRecentOrderDtoOut toDtoOut(DashboardRecentOrder model);

    List<AdminDashboardRecentOrderDtoOut> toRecentOrderDtoList(List<DashboardRecentOrder> models);
}
