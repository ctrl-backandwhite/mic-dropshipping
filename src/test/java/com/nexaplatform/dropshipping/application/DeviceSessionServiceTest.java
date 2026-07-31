package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserSessionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeviceSessionService}. The {@link UserSessionRepository} (Spring Data,
 * returns {@code UserSessionEntity}) and the servlet request/response are mocked. We exercise:
 * <ul>
 *   <li>recordLogin: new device row created + cookie issued when no/foreign device token;</li>
 *   <li>recordLogin: existing owned row reused (no new token), fields refreshed, revoked cleared;</li>
 *   <li>list: maps owned non-revoked rows to {@code SessionView}, flagging the current device;</li>
 *   <li>revoke: ownership guard — revokes only when the session belongs to the user;</li>
 *   <li>isRevoked: enforcement lookup by device cookie.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DeviceSessionServiceTest {

    @Mock
    UserSessionRepository repository;

    @InjectMocks
    DeviceSessionService service;

    private static HttpServletRequest requestWith(String deviceToken, String userAgent, String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        Cookie[] cookies = deviceToken == null ? null : new Cookie[] {new Cookie(DeviceSessionService.COOKIE, deviceToken)};
        // Helper compartido: no todos los tests consumen los 3 stubs → lenient para no romper en STRICT.
        lenient().when(req.getCookies()).thenReturn(cookies);
        lenient().when(req.getHeader("User-Agent")).thenReturn(userAgent);
        lenient().when(req.getRemoteAddr()).thenReturn(ip);
        return req;
    }

    private static UserSessionEntity session(UUID id, UUID userId, String token, Instant lastSeen, Instant revokedAt) {
        UserSessionEntity e = UserSessionEntity.builder().userId(userId).deviceToken(token)
                .device("Chrome").ip("1.1.1.1").createdAt(Instant.now()).lastSeenAt(lastSeen).revokedAt(revokedAt)
                .build();
        e.setId(id);
        return e;
    }

    @Test
    void recordLogin_createsNewRowAndIssuesCookieWhenNoDeviceToken() {
        UUID userId = UUID.randomUUID();
        HttpServletRequest req = requestWith(null, "Mozilla/5.0 (Windows NT) Chrome/120 Safari/537", "9.9.9.9");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordLogin(userId, req, res);

        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(repository).save(captor.capture());
        UserSessionEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getDeviceToken()).isNotBlank();
        assertThat(saved.getDevice()).isEqualTo("Chrome · Windows");
        assertThat(saved.getIp()).isEqualTo("9.9.9.9");
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getLastSeenAt()).isNotNull();
        verify(res).addHeader(eq("Set-Cookie"), contains(DeviceSessionService.COOKIE));
        // never looks up by token since there was no device cookie
        verify(repository, never()).findByDeviceToken(any());
    }

    @Test
    void recordLogin_reusesExistingOwnedRowKeepingTokenAndClearingRevoked() {
        UUID userId = UUID.randomUUID();
        UserSessionEntity existing = session(UUID.randomUUID(), userId, "tok-existing",
                Instant.now().minusSeconds(3600), Instant.now());
        HttpServletRequest req = requestWith("tok-existing", "Mozilla/5.0 (iPhone) Safari", "2.2.2.2");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(repository.findByDeviceToken("tok-existing")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordLogin(userId, req, res);

        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(repository).save(captor.capture());
        UserSessionEntity saved = captor.getValue();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getDeviceToken()).isEqualTo("tok-existing");
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getDevice()).isEqualTo("Safari · iOS");
        verify(res).addHeader(eq("Set-Cookie"), contains("tok-existing"));
    }

    @Test
    void recordLogin_rotatesTokenWhenCookieBelongsToAnotherUser() {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UserSessionEntity foreign = session(UUID.randomUUID(), otherUser, "tok-foreign",
                Instant.now(), null);
        HttpServletRequest req = requestWith("tok-foreign", "Mozilla/5.0 (Android) Chrome", "3.3.3.3");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(repository.findByDeviceToken("tok-foreign")).thenReturn(Optional.of(foreign));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordLogin(userId, req, res);

        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(repository).save(captor.capture());
        UserSessionEntity saved = captor.getValue();
        // a fresh row for the current user with a rotated token (not the foreign one)
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getDeviceToken()).isNotEqualTo("tok-foreign");
        assertThat(saved).isNotSameAs(foreign);
    }

    @Test
    void recordLogin_usesFirstXForwardedForIpWhenPresent() {
        UUID userId = UUID.randomUUID();
        HttpServletRequest req = requestWith(null, "Firefox", "127.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1");
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordLogin(userId, req, res);

        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void list_mapsOwnedNonRevokedRowsAndFlagsCurrentDevice() {
        UUID userId = UUID.randomUUID();
        UUID currentId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UserSessionEntity current = session(currentId, userId, "tok-current", Instant.now(), null);
        UserSessionEntity other = session(otherId, userId, "tok-other", Instant.now().minusSeconds(60), null);
        when(repository.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId))
                .thenReturn(List.of(current, other));
        HttpServletRequest req = requestWith("tok-current", "Chrome", "1.1.1.1");

        List<DeviceSessionService.SessionView> views = service.list(userId, req);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).id()).isEqualTo(currentId);
        assertThat(views.get(0).current()).isTrue();
        assertThat(views.get(1).id()).isEqualTo(otherId);
        assertThat(views.get(1).current()).isFalse();
    }

    @Test
    void list_marksNoneCurrentWhenNoDeviceCookie() {
        UUID userId = UUID.randomUUID();
        UserSessionEntity s = session(UUID.randomUUID(), userId, "tok", Instant.now(), null);
        when(repository.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId)).thenReturn(List.of(s));
        HttpServletRequest req = requestWith(null, "Chrome", "1.1.1.1");

        List<DeviceSessionService.SessionView> views = service.list(userId, req);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).current()).isFalse();
    }

    @Test
    void revoke_setsRevokedAtAndSavesWhenSessionBelongsToUser() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UserSessionEntity owned = session(sessionId, userId, "tok", Instant.now(), null);
        when(repository.findById(sessionId)).thenReturn(Optional.of(owned));

        service.revoke(userId, sessionId);

        assertThat(owned.getRevokedAt()).isNotNull();
        verify(repository).save(owned);
    }

    @Test
    void revoke_noOpWhenSessionBelongsToAnotherUser() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UserSessionEntity foreign = session(sessionId, UUID.randomUUID(), "tok", Instant.now(), null);
        when(repository.findById(sessionId)).thenReturn(Optional.of(foreign));

        service.revoke(userId, sessionId);

        assertThat(foreign.getRevokedAt()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void revoke_noOpWhenSessionMissing() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(repository.findById(sessionId)).thenReturn(Optional.empty());

        service.revoke(userId, sessionId);

        verify(repository, never()).save(any());
    }

    @Test
    void isRevoked_falseWhenNoDeviceCookie() {
        HttpServletRequest req = requestWith(null, "Chrome", "1.1.1.1");

        assertThat(service.isRevoked(req)).isFalse();
        verify(repository, never()).findByDeviceToken(any());
    }

    @Test
    void isRevoked_trueWhenSessionForCookieIsRevoked() {
        UserSessionEntity revoked = session(UUID.randomUUID(), UUID.randomUUID(), "tok", Instant.now(), Instant.now());
        when(repository.findByDeviceToken("tok")).thenReturn(Optional.of(revoked));
        HttpServletRequest req = requestWith("tok", "Chrome", "1.1.1.1");

        assertThat(service.isRevoked(req)).isTrue();
    }

    @Test
    void isRevoked_falseWhenSessionForCookieIsActive() {
        UserSessionEntity active = session(UUID.randomUUID(), UUID.randomUUID(), "tok", Instant.now(), null);
        when(repository.findByDeviceToken("tok")).thenReturn(Optional.of(active));
        HttpServletRequest req = requestWith("tok", "Chrome", "1.1.1.1");

        assertThat(service.isRevoked(req)).isFalse();
    }

    /**
     * Nombre legible del dispositivo, que es lo que el usuario ve al revisar sus sesiones abiertas para
     * decidir cuál revocar. El ORDEN de comprobación importa y por eso se prueba caso a caso: Edge y
     * Opera se anuncian también como Chrome, Chrome se anuncia además como Safari, y el identificador de
     * iPhone y iPad contiene "Mac OS X" —mirarlo después de macOS etiquetaría todos los móviles de Apple
     * como ordenadores—. Antes eran dos cadenas de cinco ternarios anidados.
     */
    @ParameterizedTest
    @CsvSource({
            "'Mozilla/5.0 (Windows NT 10.0) AppleWebKit Chrome/120 Safari/537 Edg/120', 'Edge · Windows'",
            "'Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537 OPR/106',             'Opera · Windows'",
            "'Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537',                     'Chrome · Windows'",
            "'Mozilla/5.0 (X11; Linux x86_64) Firefox/121',                             'Firefox · Linux'",
            "'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) Safari/605',                'Safari · macOS'",
            "'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604',       'Safari · iOS'",
            "'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) Safari/604',                'Safari · iOS'",
            "'Mozilla/5.0 (Linux; Android 14) Chrome/120 Safari/537',                   'Chrome · Android'",
            "'algo-que-no-reconocemos/1.0',                                             'Navegador'"
    })
    void recordLogin_nombraElDispositivoSegunElOrdenDeComprobacion(String userAgent, String expected) {
        HttpServletRequest req = requestWith(null, userAgent, "9.9.9.9");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordLogin(UUID.randomUUID(), req, mock(HttpServletResponse.class));

        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDevice()).isEqualTo(expected);
    }

    @Test
    void recordLogin_sinIdentificadorDelNavegadorElDispositivoSeMarcaComoDesconocido() {
        HttpServletRequest req = requestWith(null, null, "9.9.9.9");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordLogin(UUID.randomUUID(), req, mock(HttpServletResponse.class));

        ArgumentCaptor<UserSessionEntity> captor = ArgumentCaptor.forClass(UserSessionEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDevice()).isEqualTo("Dispositivo desconocido");
    }
}
