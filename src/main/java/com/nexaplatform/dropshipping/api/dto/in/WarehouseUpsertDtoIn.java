package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Create/update payload for a warehouse. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseUpsertDtoIn {

    @NotBlank
    private String code;

    @NotBlank
    private String name;

    private String country;

    private String city;

    private boolean active;
}
