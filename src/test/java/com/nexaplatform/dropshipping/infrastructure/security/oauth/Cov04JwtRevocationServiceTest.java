package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Revocación inmediata de JWT.
 *
 * <p>Es lo que hace que el logout y un cambio de rol tengan efecto en el acto en vez de esperar a que el
 * token caduque solo. Se apoya en el {@code iat}: todo token emitido en o antes de la marca de revocación
 * deja de valer, así se cubren de golpe todos los tokens vivos de un sujeto.
 */
class Cov04JwtRevocationServiceTest {

    private static final String PREFIX = "nx:jwt:revoked-before:";

    /** Sin Redis (dev y tests): respaldo en memoria, mismo comportamiento observable. */
    @Nested
    class SinRedis {

        private JwtRevocationService service;

        @BeforeEach
        void setUp() {
            service = new JwtRevocationService(null);
        }

        @Test
        void unTokenDeUnSujetoQueNadieHaRevocadoSigueValiendo() {
            assertThat(service.isStillValid("cliente-1", Instant.now().getEpochSecond())).isTrue();
        }

        @Test
        void alRevocarDejanDeValerLosTokensEmitidosHastaEseInstante() {
            long antes = Instant.now().getEpochSecond() - 60;
            service.revokeAllForClient("cliente-1");

            assertThat(service.isStillValid("cliente-1", antes)).isFalse();
            // Y el que se emita después (al volver a entrar) sí vale: si no, nadie podría reautenticarse.
            assertThat(service.isStillValid("cliente-1", Instant.now().getEpochSecond() + 5)).isTrue();
        }

        @Test
        void laRevocacionDeUnSujetoNoAlcanzaALosDemas() {
            service.revokeAllForClient("cliente-1");

            assertThat(service.isStillValid("cliente-2", Instant.now().getEpochSecond() - 60)).isTrue();
        }

        @Test
        void unClientIdVacioNoRevocaNadaYNoSeAnotaEnElSnapshot() {
            service.revokeAllForClient(null);
            service.revokeAllForClient("   ");

            assertThat(service.snapshot()).isEmpty();
        }

        @Test
        void sinSujetoEnElTokenNoHayNadaQueRevocar() {
            // No se puede identificar a quién revocar; rechazarlo aquí dejaría fuera tokens legítimos.
            assertThat(service.isStillValid(null, 0L)).isTrue();
        }

        @Test
        void revocarVariosClientesDeUnUsuarioLosCubreATodos() {
            service.revokeAllForClients(List.of("app-1", "app-2"));

            long antes = Instant.now().getEpochSecond() - 60;
            assertThat(service.isStillValid("app-1", antes)).isFalse();
            assertThat(service.isStillValid("app-2", antes)).isFalse();
        }

        @Test
        void unRefreshSoloSePuedeCanjearUnaVez() {
            // El segundo canje del mismo jti significa reuso → posible robo del refresh token.
            assertThat(service.consumeRefreshJti("jti-1")).isTrue();
            assertThat(service.consumeRefreshJti("jti-1")).isFalse();
        }

        @Test
        void unRefreshSinIdentificadorSeTrataComoInvalido() {
            assertThat(service.consumeRefreshJti(null)).isFalse();
            assertThat(service.consumeRefreshJti("  ")).isFalse();
        }

        @Test
        void elSnapshotEnsenaLasRevocacionesActivasPorSujeto() {
            service.revokeAllForClient("cliente-1");

            Map<String, Long> snapshot = service.snapshot();

            assertThat(snapshot).containsOnlyKeys("cliente-1");
            assertThat(snapshot.get("cliente-1")).isNotNull();
        }
    }

    /** Con Redis: la marca se comparte entre instancias y debe sobrevivir a la vida del refresh. */
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class ConRedis {

        @Mock
        StringRedisTemplate redis;
        @Mock
        ValueOperations<String, String> valueOps;

        @InjectMocks
        private JwtRevocationService service;

        @BeforeEach
        void setUp() {
            when(redis.opsForValue()).thenReturn(valueOps);
        }

        @Test
        void laMarcaDeRevocacionViveMasQueElRefreshTokenMasLargo() {
            // El refresh de usuario dura 14 días: con un TTL menor, un refresh robado volvería a ser
            // válido en cuanto caducara la entrada.
            service.revokeAllForClient("cliente-1");

            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(eq(PREFIX + "cliente-1"), anyString(), ttl.capture());
            assertThat(ttl.getValue()).isGreaterThanOrEqualTo(Duration.ofDays(14));
        }

        @Test
        void seConsultaLaMarcaCompartidaYNoLaMemoriaLocal() {
            long revocadoEn = Instant.now().getEpochSecond();
            when(valueOps.get(PREFIX + "cliente-1")).thenReturn(Long.toString(revocadoEn));

            assertThat(service.isStillValid("cliente-1", revocadoEn - 1)).isFalse();
            assertThat(service.isStillValid("cliente-1", revocadoEn + 1)).isTrue();
        }

        @Test
        void sinMarcaEnRedisElTokenSigueValiendo() {
            when(valueOps.get(anyString())).thenReturn(null);

            assertThat(service.isStillValid("cliente-1", 0L)).isTrue();
        }

        @Test
        void elCanjeDelRefreshEsAtomicoEntreInstancias() {
            // setIfAbsent decide el ganador en Redis: dos instancias no pueden canjear el mismo jti.
            when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true, false);

            assertThat(service.consumeRefreshJti("jti-1")).isTrue();
            assertThat(service.consumeRefreshJti("jti-1")).isFalse();
        }

        @Test
        void siRedisNoContestaAlCanjeElRefreshNoSeDaPorValido() {
            when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);

            assertThat(service.consumeRefreshJti("jti-1")).isFalse();
        }

        @Test
        void unJtiVacioNiSiquieraLlegaARedis() {
            assertThat(service.consumeRefreshJti("")).isFalse();

            verifyNoInteractions(valueOps);
        }

        @Test
        void elSnapshotQuitaElPrefijoTecnicoDeLasClaves() {
            // El recorrido va por SCAN y no por KEYS: KEYS bloquea Redis entero mientras barre el
            // keyspace, y esto se abre desde una pantalla de diagnóstico con usuarios conectados.
            // El cursor se construye ANTES del when: crearlo dentro deja un stubbing anidado a medias.
            Cursor<String> cursor = cursorDe(PREFIX + "cliente-1", PREFIX + "cliente-2");
            when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
            when(valueOps.get(PREFIX + "cliente-1")).thenReturn("100");
            when(valueOps.get(PREFIX + "cliente-2")).thenReturn(null);

            Map<String, Long> snapshot = service.snapshot();

            // Solo las claves con valor: una entrada caducada entre el scan() y el get() no debe colarse.
            assertThat(snapshot).containsExactly(entry("cliente-1", 100L));
        }

        @Test
        void unRedisSinClavesDevuelveUnSnapshotVacio() {
            Cursor<String> vacio = cursorDe();
            when(redis.scan(any(ScanOptions.class))).thenReturn(vacio);

            assertThat(service.snapshot()).isEmpty();
        }

        /** Cursor de SCAN sobre las claves dadas, como el que devuelve RedisTemplate. */
        private Cursor<String> cursorDe(String... keys) {
            Iterator<String> it = List.of(keys).iterator();
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
            when(cursor.next()).thenAnswer(inv -> it.next());
            return cursor;
        }
    }
}
