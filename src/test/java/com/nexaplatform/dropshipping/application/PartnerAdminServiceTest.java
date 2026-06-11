package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.AdminOAuthClientDtoOut;
import com.nexaplatform.dropshipping.application.service.PartnerAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnerAdminServiceTest {

    @Mock
    JdbcTemplate jdbc;

    private PartnerAdminService service() {
        return new PartnerAdminService(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listOAuthClients_delegatesToJdbcAndReturnsTypedDtos() {
        var dto = AdminOAuthClientDtoOut.builder().clientId("admin-spa").build();
        when(jdbc.query(contains("oauth2_registered_client"), any(RowMapper.class)))
                .thenReturn(List.of(dto));

        List<AdminOAuthClientDtoOut> result = service().listOAuthClients();

        assertThat(result).containsExactly(dto);
    }
}
