package com.nexaplatform.dropshipping.api.dto.in;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Status-update payload for an ODM/OEM project.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OdmStatusUpdateDtoIn {

    private String status;
}
