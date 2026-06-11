package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DROP-586: partial-update payload for inline editing of a user's basic fields
 * from the admin panel. All fields are optional; null fields are ignored by the
 * MapStruct update mapper (no overwrite).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserEditDtoIn {

    @Size(max = 120)
    private String displayName;

    @Size(max = 180)
    private String companyName;

    @Size(max = 60)
    private String country;

    @Size(max = 8)
    private String language;

    private Boolean active;
}
