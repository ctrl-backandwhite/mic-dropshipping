package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Un Redis caído no puede dejar a todo el mundo fuera de la aplicación.
 *
 * <p>Esta comprobación se ejecuta al validar CADA token. Antes, si Redis estaba
 * configurado pero no respondía, la excepción se propagaba y la petición moría
 * con un 500: la autenticación entera caía con Redis, incluida la parte que no lo
 * usa para nada. Se vio el 27-ago-2026 en la cadena de entrega, donde no hay
 * Redis, con miles de líneas de excepciones tapando los fallos reales.
 *
 * <p>Se prefiere perder temporalmente la revocación masiva —una medida
 * excepcional— antes que tumbar el acceso de todos.
 */
@DisplayName("Revocación de tokens · sobrevive a un Redis caído")
class JwtRevocacionSinRedisTest {

    @Test
    @DisplayName("si Redis no responde, el token sigue siendo válido en vez de dar error")
    void redis_caido_no_tumba_la_autenticacion() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        JwtRevocationService servicio = new JwtRevocationService(redis);

        // Sin el arreglo, esta línea lanzaba la excepción y la petición moría con un 500.
        boolean valido = servicio.isStillValid("cliente-1", 1_700_000_000L);

        assertThat(valido)
                .as("con Redis caído se cae al registro en memoria, no se rechaza a nadie")
                .isTrue();
    }

    @Test
    @DisplayName("sin Redis configurado, usa el registro en memoria de siempre")
    void sin_redis_usa_memoria() {
        JwtRevocationService servicio = new JwtRevocationService(null);
        assertThat(servicio.isStillValid("cliente-1", 1_700_000_000L)).isTrue();
    }
}
