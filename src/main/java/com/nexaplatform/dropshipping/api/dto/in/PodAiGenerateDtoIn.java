package com.nexaplatform.dropshipping.api.dto.in;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prompt payload for the (mocked) POD AI image generation endpoint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PodAiGenerateDtoIn {

    private String prompt;
}
