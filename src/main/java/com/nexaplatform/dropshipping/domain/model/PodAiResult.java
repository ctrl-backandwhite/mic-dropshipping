package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Small domain result of a (mocked) POD AI image generation command. Carries the
 * generated mockup URL, the echoed prompt and the provider; the API maps it to
 * the transport {@code PodAiGenerateDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodAiResult {

    private String mockupUrl;
    private String prompt;
    private String provider;
}
