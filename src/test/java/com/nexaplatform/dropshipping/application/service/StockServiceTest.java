package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    ProductVariantRepository variantRepository;

    private StockService service() {
        return new StockService(variantRepository);
    }

    private static Order orderWith(OrderItem... items) {
        return Order.builder().orderNumber("ORD-1").items(List.of(items)).build();
    }

    private static OrderItem item(UUID variantId, int qty) {
        return OrderItem.builder().variantId(variantId).quantity(qty).build();
    }

    // ── Descuento al concretarse la venta ──────────────────────────────────────

    @Test
    void deduct_subtractsQuantityPerVariant() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        when(variantRepository.deductStock(v1, 2)).thenReturn(1);
        when(variantRepository.deductStock(v2, 5)).thenReturn(1);

        service().deductForOrder(orderWith(item(v1, 2), item(v2, 5)));

        verify(variantRepository).deductStock(v1, 2);
        verify(variantRepository).deductStock(v2, 5);
        verify(variantRepository, never()).zeroStock(v1);
        verify(variantRepository, never()).zeroStock(v2);
    }

    @Test
    void deduct_whenInsufficientStock_forcesZeroAndNeverGoesNegative() {
        UUID v = UUID.randomUUID();
        when(variantRepository.deductStock(v, 10)).thenReturn(0); // no había suficiente

        service().deductForOrder(orderWith(item(v, 10)));

        verify(variantRepository).deductStock(v, 10);
        verify(variantRepository).zeroStock(v); // salvaguarda anti-sobreventa (el dinero ya se capturó)
    }

    @Test
    void deduct_ignoresItemsWithoutVariantOrNonPositiveQty() {
        UUID v = UUID.randomUUID();
        when(variantRepository.deductStock(v, 3)).thenReturn(1);

        service().deductForOrder(orderWith(item(null, 4), item(v, 0), item(v, 3)));

        verify(variantRepository, times(1)).deductStock(eq(v), anyInt());
        verify(variantRepository).deductStock(v, 3);
    }

    // ── Reintegro al cancelar/reembolsar ───────────────────────────────────────

    @Test
    void restore_addsQuantityBackPerVariant() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();

        service().restoreForOrder(orderWith(item(v1, 2), item(v2, 5)));

        verify(variantRepository).restoreStock(v1, 2);
        verify(variantRepository).restoreStock(v2, 5);
    }

    @Test
    void restore_ignoresItemsWithoutVariant() {
        UUID v = UUID.randomUUID();

        service().restoreForOrder(orderWith(item(null, 4), item(v, 3)));

        verify(variantRepository).restoreStock(v, 3);
        verify(variantRepository, never()).restoreStock(eq(null), anyInt());
    }

    // ── Guardas nulas ──────────────────────────────────────────────────────────

    @Test
    void deductAndRestore_areNoOpForNullOrEmptyOrders() {
        service().deductForOrder(null);
        service().restoreForOrder(Order.builder().orderNumber("X").items(null).build());
        verifyNoInteractions(variantRepository);
    }
}
