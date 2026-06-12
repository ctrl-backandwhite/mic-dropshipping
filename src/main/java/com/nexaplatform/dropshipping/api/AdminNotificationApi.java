package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AdminNotificationSendDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminNotificationSentDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Admin Notifications API: lets staff send an in-app notification to a single
 * user (by email) or broadcast it to everyone. Routing/docs live here; the
 * controller only delegates to the use case.
 */
@Tag(name = "Admin Notifications")
public interface AdminNotificationApi {

    @Operation(summary = "Send/broadcast an in-app notification")
    @PostMapping("/send")
    ResponseEntity<AdminNotificationSentDtoOut> send(@Valid @RequestBody AdminNotificationSendDtoIn body);
}
