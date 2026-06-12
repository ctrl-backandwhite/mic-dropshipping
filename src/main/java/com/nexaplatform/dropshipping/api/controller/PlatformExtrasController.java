package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PlatformExtrasApi;
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
import com.nexaplatform.dropshipping.api.mapper.NotificationDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.OdmProjectDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.PodDesignDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.ShippingDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.SupportTicketDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.WarehouseDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.application.usecase.OdmProjectUseCase;
import com.nexaplatform.dropshipping.application.usecase.PodDesignUseCase;
import com.nexaplatform.dropshipping.application.usecase.ShippingUseCase;
import com.nexaplatform.dropshipping.application.usecase.SupportTicketUseCase;
import com.nexaplatform.dropshipping.application.usecase.WarehouseUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Hosts the remaining DROP-6 (POD), DROP-7 (ODM), DROP-11 (Tickets / Notifications),
 * DROP-13 (Warehouses / Shipping calculator / ESG) endpoints. Pure implementation
 * of {@link PlatformExtrasApi}: no routing/documentation annotations and no
 * business logic here — each endpoint maps DtoIn -> domain -> DtoOut and delegates
 * to the per-aggregate use case.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlatformExtrasController implements PlatformExtrasApi {

    private final PodDesignDtoMapper podMapper;
    private final PodDesignUseCase podUseCase;

    private final OdmProjectDtoMapper odmMapper;
    private final OdmProjectUseCase odmUseCase;

    private final SupportTicketDtoMapper ticketMapper;
    private final SupportTicketUseCase ticketUseCase;

    private final NotificationDtoMapper notificationMapper;
    private final NotificationUseCase notificationUseCase;

    private final WarehouseDtoMapper warehouseMapper;
    private final WarehouseUseCase warehouseUseCase;

    private final ShippingDtoMapper shippingMapper;
    private final ShippingUseCase shippingUseCase;

    /* ============================== DROP-6 POD ============================== */

    @Override
    public List<PodBlankProductDtoOut> podBlanks(String lang) {
        return podMapper.toBlankDtoOutList(podUseCase.blanks(lang));
    }

    @Override
    public PodDesignDtoOut createDesign(Authentication auth, PodDesignCreateDtoIn req) {
        return podMapper.toDtoOut(podUseCase.create(UUID.fromString(auth.getName()), podMapper.toDomain(req)));
    }

    @Override
    public List<PodDesignDtoOut> myDesigns(Authentication auth) {
        return podMapper.toDtoOutList(podUseCase.myDesigns(UUID.fromString(auth.getName())));
    }

    @Override
    public PodAiGenerateDtoOut aiGenerate(PodAiGenerateDtoIn req) {
        return podMapper.toAiDtoOut(podUseCase.aiGenerate(req.getPrompt()));
    }

    @Override
    public PodDesignDtoOut renameDesign(Authentication auth, UUID id, String name) {
        return podMapper.toDtoOut(podUseCase.renameDesign(UUID.fromString(auth.getName()), id, name));
    }

    @Override
    public org.springframework.http.ResponseEntity<Void> deleteDesign(Authentication auth, UUID id) {
        podUseCase.deleteDesign(UUID.fromString(auth.getName()), id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /* ============================== DROP-7 ODM/OEM ============================== */

    @Override
    public OdmProjectDtoOut createOdm(Authentication auth, OdmProjectCreateDtoIn req) {
        return odmMapper.toDtoOut(odmUseCase.create(UUID.fromString(auth.getName()), odmMapper.toDomain(req)));
    }

    @Override
    public List<OdmProjectDtoOut> myOdm(Authentication auth) {
        return odmMapper.toDtoOutList(odmUseCase.myProjects(UUID.fromString(auth.getName())));
    }

    @Override
    public List<OdmProjectDtoOut> adminOdm(String status) {
        return odmMapper.toDtoOutList(odmUseCase.adminList(status));
    }

    @Override
    public OdmProjectDtoOut setOdmStatus(UUID id, OdmStatusUpdateDtoIn req) {
        return odmMapper.toDtoOut(odmUseCase.setStatus(id, req.getStatus()));
    }

    @Override
    public OdmProjectDtoOut getOdm(Authentication auth, UUID id) {
        return odmMapper.toDtoOut(odmUseCase.getById(UUID.fromString(auth.getName()), id));
    }

    @Override
    public OdmProjectDtoOut updateOdm(Authentication auth, UUID id, OdmProjectCreateDtoIn req) {
        return odmMapper.toDtoOut(odmUseCase.update(UUID.fromString(auth.getName()), id, odmMapper.toDomain(req)));
    }

    @Override
    public org.springframework.http.ResponseEntity<Void> deleteOdm(Authentication auth, UUID id) {
        odmUseCase.delete(UUID.fromString(auth.getName()), id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /* ============================== DROP-11 Tickets ============================== */

    @Override
    public SupportTicketDtoOut openTicket(Authentication auth, SupportTicketCreateDtoIn req) {
        return ticketMapper.toDtoOut(ticketUseCase.open(UUID.fromString(auth.getName()), ticketMapper.toDomain(req)));
    }

    @Override
    public List<SupportTicketDtoOut> myTickets(Authentication auth) {
        return ticketMapper.toDtoOutList(ticketUseCase.myTickets(UUID.fromString(auth.getName())));
    }

    @Override
    public List<SupportTicketDtoOut> adminTickets(String status) {
        return ticketMapper.toDtoOutList(ticketUseCase.adminList(status));
    }

    @Override
    public SupportTicketDtoOut resolve(UUID id, SupportTicketResolveDtoIn req) {
        return ticketMapper.toDtoOut(ticketUseCase.resolve(id, req.getResolution()));
    }

    /* ============================== DROP-11 Notifications ============================== */

    @Override
    public List<PlatformNotificationDtoOut> notifications(Authentication auth) {
        return notificationMapper.toDtoOutList(notificationUseCase.myNotifications(UUID.fromString(auth.getName())));
    }

    @Override
    public UnreadCountDtoOut unreadCount(Authentication auth) {
        return notificationMapper.toUnreadDtoOut(notificationUseCase.unreadCount(UUID.fromString(auth.getName())));
    }

    @Override
    public void markRead(UUID id) {
        notificationUseCase.markRead(id);
    }

    @Override
    public void markAllRead(Authentication auth) {
        notificationUseCase.markAllRead(UUID.fromString(auth.getName()));
    }

    /* ============================== DROP-13 Warehouses / Shipping calc / ESG ============================== */

    @Override
    public List<WarehouseDtoOut> warehouses() {
        return warehouseMapper.toDtoOutList(warehouseUseCase.listActive());
    }

    @Override
    public List<WarehouseStockDtoOut> stockPerWarehouse(UUID id) {
        return warehouseMapper.toStockDtoOutList(warehouseUseCase.stockPerWarehouse(id));
    }

    @Override
    public List<ShippingRateDtoOut> shippingCalculator(ShippingCalculatorDtoIn req) {
        return shippingMapper.toRateDtoOutList(shippingUseCase.calculate(req.effectiveWeight(), req.effectiveQty()));
    }

    @Override
    public CarbonFootprintDtoOut carbonFootprint(ShippingCalculatorDtoIn req) {
        return shippingMapper
                .toCarbonDtoOut(shippingUseCase.carbonFootprint(req.effectiveWeight(), req.effectiveQty()));
    }
}
