package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.RateLimitPolicyDtoOut;
import com.nexaplatform.dropshipping.infrastructure.security.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Use-case service that adapts the raw rate-limit policy snapshot exposed by
 * {@link RateLimitFilter} into a typed DTO list for the public docs endpoint.
 * The filter remains the single source of truth; this service only maps shapes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitDocsService {

    private final RateLimitFilter filter;

    /** Return one typed entry per declared rate-limit policy. */
    public List<RateLimitPolicyDtoOut> listPolicies() {
        return filter.policies().stream()
                .map(RateLimitDocsService::toDto)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static RateLimitPolicyDtoOut toDto(Map<String, Object> p) {
        Object capacity = p.get("capacity");
        return RateLimitPolicyDtoOut.builder()
                .name((String) p.get("name"))
                .path((String) p.get("path"))
                .scope((String) p.get("scope"))
                .capacity(capacity instanceof Number n ? n.intValue() : null)
                .period((String) p.get("period"))
                .tiers((Map<String, Object>) p.get("tiers"))
                .build();
    }
}
