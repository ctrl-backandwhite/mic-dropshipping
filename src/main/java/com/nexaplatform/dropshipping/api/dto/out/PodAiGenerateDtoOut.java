package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

/**
 * Result of a (mocked) POD AI image generation request.
 * Field names preserve the previous Map keys: mockupUrl, prompt, provider.
 */
@Value
@Builder
public class PodAiGenerateDtoOut {

    String mockupUrl;
    String prompt;
    String provider;
}
