package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Admin "send notification" payload: target ("all" or an email), title and body. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminNotificationSendDtoIn {

    /** "all" (or blank) to broadcast, or a recipient email. */
    private String target;

    @NotBlank
    private String title;

    @NotBlank
    private String body;
}
