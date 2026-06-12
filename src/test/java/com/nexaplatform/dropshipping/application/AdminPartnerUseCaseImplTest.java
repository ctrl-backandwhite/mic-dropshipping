package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.AdminPartnerUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.AdminOAuthClient;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerApp;
import com.nexaplatform.dropshipping.domain.model.AdminShopConnection;
import com.nexaplatform.dropshipping.domain.model.AdminPartnerWebhook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class AdminPartnerUseCaseImplTest {

    @Mock
    JdbcTemplate jdbc;

    @InjectMocks
    AdminPartnerUseCaseImpl useCase;

    @Test
    @SuppressWarnings("unchecked")
    void listOAuthClients_delegatesToJdbcAndReturnsProjectionModels() {
        var model = AdminOAuthClient.builder().clientId("admin-spa").build();
        when(jdbc.query(contains("oauth2_registered_client"), any(RowMapper.class))).thenReturn(List.of(model));

        List<AdminOAuthClient> result = useCase.listOAuthClients();

        assertThat(result).containsExactly(model);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listWebhooks_delegatesToJdbcAndReturnsProjectionModels() {
        var model = AdminPartnerWebhook.builder().eventType("ORDER_CREATED").build();
        when(jdbc.query(contains("partner_webhook_delivery"), any(RowMapper.class))).thenReturn(List.of(model));

        List<AdminPartnerWebhook> result = useCase.listWebhooks();

        assertThat(result).containsExactly(model);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPartnerApps_delegatesToJdbcAndReturnsProjectionModels() {
        var model = AdminPartnerApp.builder().name("Demo Partner").build();
        when(jdbc.query(contains("partner_app"), any(RowMapper.class))).thenReturn(List.of(model));

        List<AdminPartnerApp> result = useCase.listPartnerApps();

        assertThat(result).containsExactly(model);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listShopConnections_delegatesToJdbcAndReturnsProjectionModels() {
        var model = AdminShopConnection.builder().platform("shopify").build();
        when(jdbc.query(contains("shop_connection"), any(RowMapper.class))).thenReturn(List.of(model));

        List<AdminShopConnection> result = useCase.listShopConnections();

        assertThat(result).containsExactly(model);
    }
}
