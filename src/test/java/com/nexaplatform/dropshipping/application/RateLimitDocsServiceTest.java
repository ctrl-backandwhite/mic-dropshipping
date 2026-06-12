package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.RateLimitPolicyDtoOut;
import com.nexaplatform.dropshipping.application.service.RateLimitDocsService;
import com.nexaplatform.dropshipping.infrastructure.security.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitDocsServiceTest {

    @Mock
    RateLimitFilter filter;

    @Test
    void listPolicies_mapsSimpleAndTieredPoliciesPreservingKeys() {
        Map<String, Object> simple = Map.of("name", "storefront", "path", "/api/v1/storefront/**", "scope", "per IP",
                "capacity", 60, "period", "1m");
        Map<String, Object> tiered = Map.of("name", "partner.catalog.read", "path", "/api/v1/partner/catalog/**",
                "scope", "per client_id (JWT sub)", "period", "1m", "tiers", Map.of("sandbox", 1, "paid", 5));
        when(filter.policies()).thenReturn(List.of(simple, tiered));

        List<RateLimitPolicyDtoOut> result = new RateLimitDocsService(filter).listPolicies();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("storefront");
        assertThat(result.get(0).getCapacity()).isEqualTo(60);
        assertThat(result.get(0).getTiers()).isNull();
        assertThat(result.get(1).getCapacity()).isNull();
        assertThat(result.get(1).getTiers()).containsEntry("paid", 5);
    }
}
