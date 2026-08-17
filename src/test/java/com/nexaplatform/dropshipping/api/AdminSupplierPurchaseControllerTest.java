package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.controller.AdminSupplierPurchaseController;
import com.nexaplatform.dropshipping.api.dto.in.AdminPurchaseBoughtDtoIn;
import com.nexaplatform.dropshipping.application.service.PackOrderExportService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.enums.SupplierPurchaseStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El pedido avanza solo cuando toda su mercancía está comprada.
 *
 * <p>Ese salto era manual y en otra pantalla. Olvidarlo dejaba al cliente viendo «pagado» con el
 * pedido ya comprado y en camino, y de paso el fichero de re-empaquetado no se activaba nunca,
 * porque la guía internacional solo se emite sobre pedidos despachados.
 */
@ExtendWith(MockitoExtension.class)
class AdminSupplierPurchaseControllerTest {

    @Mock
    private SupplierPurchaseService purchaseService;
    @Mock
    private PackOrderExportService packOrderExportService;
    @Mock
    private CurrencyRateService currencyRateService;
    @Mock
    private OrderUseCase orderUseCase;

    @InjectMocks
    private AdminSupplierPurchaseController controller;

    private static final UUID PURCHASE_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();

    private SupplierPurchaseEntity compra;

    @BeforeEach
    void setUp() {
        compra = SupplierPurchaseEntity.builder().id(PURCHASE_ID).orderId(ORDER_ID)
                .status(SupplierPurchaseStatus.PURCHASED).build();
        when(purchaseService.markPurchased(eq(PURCHASE_ID), any(), any(), any())).thenReturn(compra);
        // La recarga no es lo que se prueba aquí: sin vistas, el controlador devuelve la proyección
        // mínima y el test se centra en el despacho.
        when(purchaseService.viewsForOrder(ORDER_ID)).thenReturn(List.of());
    }

    private void marcarComprada() {
        controller.bought(PURCHASE_ID, new AdminPurchaseBoughtDtoIn());
    }

    @Test
    @DisplayName("con toda la mercancía comprada, el pedido pasa a enviado a proveedor")
    void despachaCuandoTodoEstaComprado() {
        when(purchaseService.allPurchased(ORDER_ID)).thenReturn(true);

        marcarComprada();

        verify(orderUseCase).forwardOrder(ORDER_ID);
    }

    @Test
    @DisplayName("si queda mercancía por comprar, el pedido no se despacha")
    void noDespachaConComprasPendientes() {
        // Un pedido de dos proveedores donde solo se ha comprado a uno no está enviado a nadie.
        when(purchaseService.allPurchased(ORDER_ID)).thenReturn(false);

        marcarComprada();

        verify(orderUseCase, never()).forwardOrder(any());
    }

    @Test
    @DisplayName("un fallo al despachar no tumba el registro de la compra")
    void elFalloAlDespacharNoPropaga() {
        // La compra ya está guardada cuando se intenta el despacho. Propagar aquí le devolvería un
        // error al admin por algo que sí se hizo, y le llevaría a repetir la operación.
        when(purchaseService.allPurchased(ORDER_ID)).thenReturn(true);
        when(orderUseCase.forwardOrder(ORDER_ID)).thenThrow(new IllegalStateException("estado no válido"));

        assertThatCode(this::marcarComprada).doesNotThrowAnyException();
    }
}
