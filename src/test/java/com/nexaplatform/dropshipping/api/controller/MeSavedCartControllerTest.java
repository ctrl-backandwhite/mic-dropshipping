package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.SavedCartItemDto;
import com.nexaplatform.dropshipping.application.service.SavedCartService;
import org.junit.jupiter.api.BeforeEach;
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
 * El controller siempre opera sobre el usuario autenticado (subject de la sesión) y delega en el service.
 */
@ExtendWith(MockitoExtension.class)
class MeSavedCartControllerTest {

    @Mock
    SavedCartService service;
    @InjectMocks
    MeSavedCartController controller;

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

    private SavedCartItemDto dto() {
        return new SavedCartItemDto(productId, null, "SKU1", "slug", "Título", null, null, new BigDecimal("10.00"),
                "EUR", 1, null, null, null, null);
    }

    @Test
    void listarUsaElUsuarioAutenticado() {
        List<SavedCartItemDto> expected = List.of(dto());
        when(service.list(userId)).thenReturn(expected);

        assertThat(controller.list(auth).getBody()).isSameAs(expected);
    }

    @Test
    void guardarDelegaEnUpsertConElUsuario() {
        SavedCartItemDto item = dto();
        controller.save(auth, item);

        verify(service).upsert(userId, item);
    }

    @Test
    void fusionarDelegaEnMergeConLaLista() {
        List<SavedCartItemDto> items = List.of(dto());
        controller.merge(auth, items);

        verify(service).merge(userId, items);
    }

    @Test
    void quitarConVariantePasaElVariantId() {
        UUID variantId = UUID.randomUUID();
        controller.remove(auth, productId, variantId);

        verify(service).remove(userId, productId, variantId);
    }

    @Test
    void quitarSinVariantePasaNull() {
        controller.remove(auth, productId, null);

        verify(service).remove(userId, productId, null);
    }
}
