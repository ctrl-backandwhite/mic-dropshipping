package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.in.PartnerApiKeyCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PartnerApiKeyCreatedDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.PartnerApiKeyService;
import com.nexaplatform.dropshipping.infrastructure.security.oauth.JwtRevocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnerApiKeyServiceTest {

    @Mock RegisteredClientRepository repo;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JdbcTemplate jdbc;
    @Mock ObjectMapper mapper;
    @Mock JwtRevocationService revocationService;

    private PartnerApiKeyService service() {
        return new PartnerApiKeyService(repo, passwordEncoder, jdbc, mapper, revocationService);
    }

    @Test
    void create_persistsClientAndReturnsOneTimeSecret() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        var req = new PartnerApiKeyCreateDtoIn("My key", List.of("catalog.read"));

        PartnerApiKeyCreatedDtoOut result = service().create(userId, req);

        assertThat(result.getName()).isEqualTo("My key");
        assertThat(result.getClientSecret()).startsWith("sk_");
        assertThat(result.getClientId()).startsWith("pk_");
        assertThat(result.getScopes()).containsExactly("catalog.read");
        verify(repo).save(any(RegisteredClient.class));
    }

    @Test
    void create_rejectsWhenQuotaExceeded() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5L);
        var req = new PartnerApiKeyCreateDtoIn("Another", null);
        PartnerApiKeyService svc = service();

        assertThatThrownBy(() -> svc.create(userId, req))
                .isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void create_rejectsUnknownScope() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);
        var req = new PartnerApiKeyCreateDtoIn("Bad", List.of("admin.everything"));
        PartnerApiKeyService svc = service();

        assertThatThrownBy(() -> svc.create(userId, req))
                .isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void revoke_deletesAndRevokesTokensWhenOwned() {
        UUID userId = UUID.randomUUID();
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("pk_abc")
                .clientName("k")
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(org.springframework.security.oauth2.server.authorization.settings.ClientSettings.builder()
                        .setting("nexadrop.owner_user_id", userId.toString()).build())
                .build();
        when(repo.findByClientId("pk_abc")).thenReturn(client);

        service().revoke(userId, "pk_abc");

        verify(jdbc).update(anyString(), eq("pk_abc"));
        verify(revocationService).revokeAllForClient("pk_abc");
    }

    @Test
    void revoke_throwsWhenNotFound() {
        UUID userId = UUID.randomUUID();
        when(repo.findByClientId("ghost")).thenReturn(null);
        PartnerApiKeyService svc = service();

        assertThatThrownBy(() -> svc.revoke(userId, "ghost"))
                .isInstanceOf(NotFoundException.class);
    }
}
