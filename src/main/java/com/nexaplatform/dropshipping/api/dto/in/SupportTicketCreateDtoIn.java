package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Open-ticket payload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportTicketCreateDtoIn {

    @NotBlank
    private String kind;

    @NotBlank
    private String subject;

    private String body;

    private UUID orderId;

    private String priority;
}
