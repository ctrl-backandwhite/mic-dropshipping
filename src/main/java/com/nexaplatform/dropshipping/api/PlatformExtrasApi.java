package com.nexaplatform.dropshipping.api;

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
import com.nexaplatform.dropshipping.api.dto.out.SupportReplyDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SupportTicketDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.UnreadCountDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseStockDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the platform extras endpoints:
 * DROP-6 (POD), DROP-7 (ODM), DROP-11 (Tickets / Notifications),
 * DROP-13 (Warehouses / Shipping calculator / ESG). The controller only
 * implements these methods; all routing and Swagger documentation live here
 * (springdoc "API interface" pattern).
 */
@Tag(name = "Platform extras")
public interface PlatformExtrasApi {

    @Operation(summary = "List POD blank products in the requested language")
    @GetMapping("/pod/blank-products")
    List<PodBlankProductDtoOut> podBlanks(@RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Create a POD design for the current user")
    @PostMapping("/me/pod/designs")
    PodDesignDtoOut createDesign(Authentication auth, @Valid @RequestBody PodDesignCreateDtoIn req);

    @Operation(summary = "List the current user's POD designs")
    @GetMapping("/me/pod/designs")
    List<PodDesignDtoOut> myDesigns(Authentication auth);

    @Operation(summary = "Generate a POD design via AI")
    @PostMapping("/me/pod/ai-generate")
    PodAiGenerateDtoOut aiGenerate(@RequestBody PodAiGenerateDtoIn req);

    @Operation(summary = "Rename one of the current user's POD designs")
    @PutMapping("/me/pod/designs/{id}")
    PodDesignDtoOut renameDesign(Authentication auth, @PathVariable UUID id, @RequestParam String name);

    @Operation(summary = "Delete one of the current user's POD designs")
    @DeleteMapping("/me/pod/designs/{id}")
    ResponseEntity<Void> deleteDesign(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Create an ODM/OEM project for the current user")
    @PostMapping("/me/odm/projects")
    OdmProjectDtoOut createOdm(Authentication auth, @Valid @RequestBody OdmProjectCreateDtoIn req);

    @Operation(summary = "List the current user's ODM/OEM projects")
    @GetMapping("/me/odm/projects")
    List<OdmProjectDtoOut> myOdm(Authentication auth);

    @Operation(summary = "List all ODM/OEM projects (admin), optionally filtered by status")
    @GetMapping("/admin/odm/projects")
    List<OdmProjectDtoOut> adminOdm(@RequestParam(required = false) String status);

    @Operation(summary = "Update the status of an ODM/OEM project (admin)")
    @PutMapping("/admin/odm/projects/{id}/status")
    OdmProjectDtoOut setOdmStatus(@PathVariable UUID id, @RequestBody OdmStatusUpdateDtoIn req);

    @Operation(summary = "Get one of the current user's ODM/OEM projects")
    @GetMapping("/me/odm/projects/{id}")
    OdmProjectDtoOut getOdm(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Update editable fields of the current user's ODM/OEM project")
    @PutMapping("/me/odm/projects/{id}")
    OdmProjectDtoOut updateOdm(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody OdmProjectCreateDtoIn req);

    @Operation(summary = "Delete one of the current user's ODM/OEM projects")
    @DeleteMapping("/me/odm/projects/{id}")
    ResponseEntity<Void> deleteOdm(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Open a support ticket for the current user")
    @PostMapping("/me/tickets")
    SupportTicketDtoOut openTicket(Authentication auth, @Valid @RequestBody SupportTicketCreateDtoIn req);

    @Operation(summary = "List the current user's support tickets")
    @GetMapping("/me/tickets")
    List<SupportTicketDtoOut> myTickets(Authentication auth);

    @Operation(summary = "List all support tickets (admin), optionally filtered by status")
    @GetMapping("/admin/tickets")
    List<SupportTicketDtoOut> adminTickets(@RequestParam(required = false) String status);

    @Operation(summary = "Resolve a support ticket (admin)")
    @PutMapping("/admin/tickets/{id}/resolve")
    SupportTicketDtoOut resolve(@PathVariable UUID id, @RequestBody SupportTicketResolveDtoIn req);

    @Operation(summary = "List messages of a support ticket (owner)")
    @GetMapping("/me/tickets/{id}/replies")
    List<SupportReplyDtoOut> myTicketReplies(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Post a message to a support ticket (owner)")
    @PostMapping("/me/tickets/{id}/replies")
    SupportReplyDtoOut myTicketReply(Authentication auth, @PathVariable UUID id, @RequestBody Map<String, String> body);

    @Operation(summary = "List messages of any support ticket (admin)")
    @GetMapping("/admin/tickets/{id}/replies")
    List<SupportReplyDtoOut> adminTicketReplies(@PathVariable UUID id);

    @Operation(summary = "Post a message to any support ticket (admin/support)")
    @PostMapping("/admin/tickets/{id}/replies")
    SupportReplyDtoOut adminTicketReply(Authentication auth, @PathVariable UUID id,
            @RequestBody Map<String, String> body);

    @Operation(summary = "List the current user's notifications by folder (inbox/archived/trash), newest first")
    @GetMapping("/me/notifications")
    List<PlatformNotificationDtoOut> notifications(Authentication auth,
            @RequestParam(defaultValue = "inbox") String folder);

    @Operation(summary = "Get the current user's unread notification count")
    @GetMapping("/me/notifications/unread-count")
    UnreadCountDtoOut unreadCount(Authentication auth);

    @Operation(summary = "Mark a notification as read")
    @PostMapping("/me/notifications/{id}/read")
    void markRead(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Mark all of the current user's notifications as read")
    @PostMapping("/me/notifications/read-all")
    void markAllRead(Authentication auth);

    @Operation(summary = "Archive a notification (moves it out of the inbox)")
    @PostMapping("/me/notifications/{id}/archive")
    void archiveNotification(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Move an archived notification back to the inbox")
    @PostMapping("/me/notifications/{id}/unarchive")
    void unarchiveNotification(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Move a notification to the trash (soft delete)")
    @DeleteMapping("/me/notifications/{id}")
    void trashNotification(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Restore a notification from the trash")
    @PostMapping("/me/notifications/{id}/restore")
    void restoreNotification(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Permanently delete a notification (only from the trash)")
    @DeleteMapping("/me/notifications/{id}/permanent")
    void deleteNotificationPermanently(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Set the management status of a notification (RECEIVED/IN_PROGRESS/WAITING/RESOLVED)")
    @PostMapping("/me/notifications/{id}/status")
    void setNotificationStatus(Authentication auth, @PathVariable UUID id, @RequestParam String value);

    @Operation(summary = "List available warehouses")
    @GetMapping("/warehouses")
    List<WarehouseDtoOut> warehouses();

    @Operation(summary = "Get per-warehouse stock for a product")
    @GetMapping("/catalog/products/{id}/warehouse-stock")
    List<WarehouseStockDtoOut> stockPerWarehouse(@PathVariable UUID id);

    @Operation(summary = "Calculate shipping rates")
    @PostMapping("/shipping/calculator")
    List<ShippingRateDtoOut> shippingCalculator(@RequestBody ShippingCalculatorDtoIn req);

    @Operation(summary = "Estimate the carbon footprint of a shipment")
    @PostMapping("/shipping/carbon-footprint")
    CarbonFootprintDtoOut carbonFootprint(@RequestBody ShippingCalculatorDtoIn req);
}
