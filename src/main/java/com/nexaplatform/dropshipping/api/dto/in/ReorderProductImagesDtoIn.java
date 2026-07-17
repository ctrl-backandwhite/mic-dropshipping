package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Reorders a product's gallery: the image ids in the desired order (first becomes MAIN). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReorderProductImagesDtoIn {

    @NotEmpty
    private List<UUID> imageIds;
}
