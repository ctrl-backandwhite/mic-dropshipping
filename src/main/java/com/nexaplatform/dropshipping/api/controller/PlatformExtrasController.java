package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.OdmProjectCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.OdmStatusUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PodAiGenerateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PodDesignCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ShippingCalculatorDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SupportTicketCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SupportTicketResolveDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CarbonFootprintDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.OdmProjectDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PlatformNotificationDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodAiGenerateDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodBlankProductDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodDesignDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShippingRateDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SupportTicketDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.UnreadCountDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseStockDtoOut;
import com.nexaplatform.dropshipping.api.PlatformExtrasApi;
import com.nexaplatform.dropshipping.application.service.PlatformExtrasService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Hosts the remaining DROP-6 (POD), DROP-7 (ODM), DROP-11 (Tickets / Notifications),
 * DROP-13 (Warehouses / Shipping calculator / ESG) endpoints. Thin controller:
 * delegates all logic to {@link PlatformExtrasService}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlatformExtrasController implements PlatformExtrasApi {

    private final PlatformExtrasService service;

    /* ============================== DROP-6 POD ============================== */

    @Override
    public List<PodBlankProductDtoOut> podBlanks(String lang) {
        return service.podBlanks(lang);
    }

    @Override
    public PodDesignDtoOut createDesign(Authentication auth, PodDesignCreateDtoIn req) {
        return service.createDesign(UUID.fromString(auth.getName()), req);
    }

    @Override
    public List<PodDesignDtoOut> myDesigns(Authentication auth) {
        return service.myDesigns(UUID.fromString(auth.getName()));
    }

    @Override
    public PodAiGenerateDtoOut aiGenerate(PodAiGenerateDtoIn req) {
        return service.aiGenerate(req);
    }

    /* ============================== DROP-7 ODM/OEM ============================== */

    @Override
    public OdmProjectDtoOut createOdm(Authentication auth, OdmProjectCreateDtoIn req) {
        return service.createOdm(UUID.fromString(auth.getName()), req);
    }

    @Override
    public List<OdmProjectDtoOut> myOdm(Authentication auth) {
        return service.myOdm(UUID.fromString(auth.getName()));
    }

    @Override
    public List<OdmProjectDtoOut> adminOdm(String status) {
        return service.adminOdm(status);
    }

    @Override
    public OdmProjectDtoOut setOdmStatus(UUID id, OdmStatusUpdateDtoIn req) {
        return service.setOdmStatus(id, req);
    }

    /* ============================== DROP-11 Tickets ============================== */

    @Override
    public SupportTicketDtoOut openTicket(Authentication auth, SupportTicketCreateDtoIn req) {
        return service.openTicket(UUID.fromString(auth.getName()), req);
    }

    @Override
    public List<SupportTicketDtoOut> myTickets(Authentication auth) {
        return service.myTickets(UUID.fromString(auth.getName()));
    }

    @Override
    public List<SupportTicketDtoOut> adminTickets(String status) {
        return service.adminTickets(status);
    }

    @Override
    public SupportTicketDtoOut resolve(UUID id, SupportTicketResolveDtoIn req) {
        return service.resolve(id, req);
    }

    /* ============================== DROP-11 Notifications ============================== */

    @Override
    public List<PlatformNotificationDtoOut> notifications(Authentication auth) {
        return service.notifications(UUID.fromString(auth.getName()));
    }

    @Override
    public UnreadCountDtoOut unreadCount(Authentication auth) {
        return service.unreadCount(UUID.fromString(auth.getName()));
    }

    @Override
    public void markRead(UUID id) {
        service.markRead(id);
    }

    @Override
    public void markAllRead(Authentication auth) {
        service.markAllRead(UUID.fromString(auth.getName()));
    }

    /* ============================== DROP-13 Warehouses / Shipping calc / ESG ============================== */

    @Override
    public List<WarehouseDtoOut> warehouses() {
        return service.warehouses();
    }

    @Override
    public List<WarehouseStockDtoOut> stockPerWarehouse(UUID id) {
        return service.stockPerWarehouse(id);
    }

    @Override
    public List<ShippingRateDtoOut> shippingCalculator(ShippingCalculatorDtoIn req) {
        return service.shippingCalculator(req);
    }

    @Override
    public CarbonFootprintDtoOut carbonFootprint(ShippingCalculatorDtoIn req) {
        return service.carbonFootprint(req);
    }
}
