package com.nexaplatform.dropshipping.api.dto.in;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resolve-ticket payload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportTicketResolveDtoIn {

    private String resolution;
}
