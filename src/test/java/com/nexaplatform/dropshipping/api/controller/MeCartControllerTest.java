package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.CartItemDto;
import com.nexaplatform.dropshipping.application.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El controller del carrito SIEMPRE opera sobre el usuario autenticado (subject de la sesión) y delega en
 * el service. Ninguna firma acepta un id de usuario: por ahí entraría un IDOR.
 */
@ExtendWith(MockitoExtension.class)
class MeCartControllerTest {

    @Mock
    CartService service;
    @InjectMocks
    MeCartController controller;

    private UUID userId;
    private UUID productId;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        auth = mock(Authentication.class);
        lenient().when(auth.getName()).thenReturn(userId.toString());
    }

    private CartItemDto dto() {
        return new CartItemDto(productId, null, "SKU1", "slug", "Título", null, null, new BigDecimal("10.00"), "EUR", 1,
                null, null, null, null);
    }

    @Test
    @DisplayName("listar usa el usuario autenticado")
    void listarUsaElUsuarioAutenticado() {
        List<CartItemDto> expected = List.of(dto());
        when(service.list(userId)).thenReturn(expected);

        assertThat(controller.list(auth).getBody()).isSameAs(expected);
    }

    @Test
    @DisplayName("guardar delega en upsert con el usuario de la sesión")
    void guardarDelegaEnUpsertConElUsuario() {
        CartItemDto item = dto();
        controller.save(auth, item);

        verify(service).upsert(userId, item);
    }

    @Test
    @DisplayName("fusionar delega en merge con la lista recibida")
    void fusionarDelegaEnMergeConLaLista() {
        List<CartItemDto> items = List.of(dto());
        controller.merge(auth, items);

        verify(service).merge(userId, items);
    }

    @Test
    @DisplayName("quitar con variante pasa el variantId")
    void quitarConVariantePasaElVariantId() {
        UUID variantId = UUID.randomUUID();
        controller.remove(auth, productId, variantId);

        verify(service).remove(userId, productId, variantId);
    }

    @Test
    @DisplayName("quitar sin variante pasa null (producto base)")
    void quitarSinVariantePasaNull() {
        controller.remove(auth, productId, null);

        verify(service).remove(userId, productId, null);
    }

    @Test
    @DisplayName("vaciar delega en clear con el usuario de la sesión")
    void vaciarDelegaEnClearConElUsuario() {
        when(service.clear(userId)).thenReturn(List.of());

        assertThat(controller.clear(auth).getBody()).isEmpty();
        verify(service).clear(userId);
    }
}
