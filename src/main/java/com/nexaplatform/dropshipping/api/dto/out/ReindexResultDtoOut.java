package com.nexaplatform.dropshipping.api.dto.out;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of a manual catalog → OpenSearch reindex. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReindexResultDtoOut {

    private int indexed;
}
