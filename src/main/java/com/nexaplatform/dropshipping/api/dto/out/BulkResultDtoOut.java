package com.nexaplatform.dropshipping.api.dto.out;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Outcome of a bulk import: how many rows were created and per-row error messages. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkResultDtoOut {

    private int created;

    private int failed;

    private List<String> errors;
}
