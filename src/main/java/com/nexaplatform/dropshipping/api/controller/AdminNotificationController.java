package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminNotificationApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminNotificationSendDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminNotificationSentDtoOut;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin Notifications controller — pure delegation to {@link NotificationUseCase}. */
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController implements AdminNotificationApi {

    private final NotificationUseCase useCase;

    @Override
    public ResponseEntity<AdminNotificationSentDtoOut> send(AdminNotificationSendDtoIn body) {
        int sent = useCase.sendAdminNotification(body.getTarget(), body.getTitle(), body.getBody());
        return ResponseEntity.ok(new AdminNotificationSentDtoOut(sent));
    }
}
