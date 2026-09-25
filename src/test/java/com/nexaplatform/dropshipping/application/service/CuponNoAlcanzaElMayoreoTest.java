package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Un cupón no alcanza a las líneas cobradas a precio de MAYOREO, y el descuento de referido tampoco.
 *
 * <p>Regla del titular del 25-sep-2026: los descuentos —promociones, rebajas, campañas y cupones— son
 * del precio unitario. La rebaja automática se corta dentro del propio cálculo del precio
 * ({@code PricingService}); el cupón y el referido NO pueden cortarse ahí porque se aplican al pedido
 * entero, así que se cortan aquí, dejando esas líneas fuera de la base sobre la que se calculan.
 *
 * <p><b>Qué se rompería sin esto.</b> El escalón por cantidad ya es el descuento del proveedor por
 * volumen. Un cupón del 50 % encima de un pedido de mil unidades no descuenta una campaña: descuenta el
 * coste. Y como la vista previa y el cobro real hacen esta cuenta por separado, la regla tiene que
 * estar escrita en los dos sitios o se enseñaría un total y se cobraría otro.
 */
class CuponNoAlcanzaElMayoreoTest {

    private final ShippingQuoteService shipping = mock(ShippingQuoteService.class, RETURNS_DEEP_STUBS);
    private final CheckoutTotalsService totals = mock(CheckoutTotalsService.class, RETURNS_DEEP_STUBS);
    private final PricingService pricing = mock(PricingService.class, RETURNS_DEEP_STUBS);
    private final CurrencyRateService currency = mock(CurrencyRateService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final AffiliateProgramService affiliate = mock(AffiliateProgramService.class);
    private final PromotionService promociones = mock(PromotionService.class);
    private final CustomsDutyLinesService dutyLines = new CustomsDutyLinesService(null);
    private final OrderAmounts orderAmounts = new OrderAmounts(currency);
    private final CustomsDeclarationGroupService declarationGroups = mock(CustomsDeclarationGroupService.class);
    private final ProductPriceTierRepository tramos = mock(ProductPriceTierRepository.class);
    private final ProductSubsidyService subvenciones = mock(ProductSubsidyService.class, RETURNS_DEEP_STUBS);

    private final CheckoutPreviewService service = new CheckoutPreviewService(shipping, totals, subvenciones, pricing,
            currency, products, tramos, dutyLines, affiliate, promociones, orderAmounts, declarationGroups);

    /** El que se lleva mil unidades: cae en el segundo escalón de su escalera. */
    private final UUID alMayor = UUID.randomUUID();
    /** El que se lleva una: precio unitario de toda la vida. */
    private final UUID porUnidad = UUID.randomUUID();

    @BeforeEach
    void setup() {
        ProductEntity cualquiera = mock(ProductEntity.class, RETURNS_DEEP_STUBS);
        lenient().when(products.findById(any())).thenReturn(Optional.of(cualquiera));
        // Un dólar la unidad en los dos, para que las cuentas se lean de un vistazo.
        lenient().when(pricing.priceFor(any(), any(), anyInt(), any()).retailUsd()).thenReturn(BigDecimal.ONE);
        lenient().when(pricing.priceFor(any(), any(), anyInt(), any()).displayAmount()).thenReturn(BigDecimal.ONE);
        lenient().when(pricing.priceFor(any(), any(), anyInt(), any()).originalRetailUsd()).thenReturn(BigDecimal.ONE);
        lenient().when(currency.usdToDisplay(any(BigDecimal.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString())).thenReturn(BigDecimal.ZERO);
        lenient().when(currency.decimalsOf(anyString())).thenReturn(2);
        lenient().when(tramos.findByProductIdInOrderByMinQtyAsc(any()))
                .thenReturn(List.of(mock(ProductPriceTierEntity.class, RETURNS_DEEP_STUBS)));
        // Quién va a mayoreo lo decide PricingService, que aquí es un doble: se le dice qué contestar.
        lenient().when(pricing.esPrecioDeMayoreo(any(), anyInt())).thenReturn(false);
    }

    @Test
    @DisplayName("el cupón se calcula solo sobre las líneas que no van a mayoreo")
    void elCuponNoCuentaLasLineasDeMayoreo() {
        // 1.000 unidades a mayoreo + 40 a precio unitario. Sin la regla, el cupón mordería sobre los
        // 1.040 $ del carrito entero; con ella, solo sobre los 40 $ que se venden por unidad.
        cuponDel50PorCiento();
        when(pricing.esPrecioDeMayoreo(any(), eq(1000))).thenReturn(true);

        service.compute("ES", null, List.of(new CheckoutPreviewService.Line(alMayor, null, 1000),
                new CheckoutPreviewService.Line(porUnidad, null, 40)), UUID.randomUUID(), "REBAJA50");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, Integer>> alcance = ArgumentCaptor.forClass(Map.class);
        verify(promociones).reachableGrossCents(any(), alcance.capture());
        assertThat(alcance.getValue()).containsOnlyKeys(porUnidad).containsEntry(porUnidad, 4_000);
    }

    @Test
    @DisplayName("sin mayoreo, el cupón alcanza a todo el carrito como siempre")
    void sinMayoreoElCuponAlcanzaTodo() {
        // EL control. La forma fácil de cumplir la regla es dejar fuera del cupón cualquier producto con
        // tabla de cantidades, y como el primer escalón lo tienen TODOS, eso desactivaría los cupones del
        // catálogo entero sin dar un solo error.
        cuponDel50PorCiento();

        service.compute("ES", null, List.of(new CheckoutPreviewService.Line(alMayor, null, 1000),
                new CheckoutPreviewService.Line(porUnidad, null, 40)), UUID.randomUUID(), "REBAJA50");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, Integer>> alcance = ArgumentCaptor.forClass(Map.class);
        verify(promociones).reachableGrossCents(any(), alcance.capture());
        assertThat(alcance.getValue()).containsOnlyKeys(alMayor, porUnidad).containsEntry(alMayor, 100_000);
    }

    @Test
    @DisplayName("el descuento de referido tampoco se calcula sobre el mayoreo")
    void elReferidoNoCuentaElMayoreo() {
        // El referido es un 10 % del subtotal de producto. Si su base siguiera siendo el carrito entero,
        // la regla se cumpliría por la puerta del cupón y se saltaría por esta, que además se aplica sin
        // que el comprador teclee nada.
        when(pricing.esPrecioDeMayoreo(any(), eq(1000))).thenReturn(true);
        lenient().when(affiliate.referralDiscountCents(any(), anyLong())).thenReturn(0L);

        service.compute("ES", null, List.of(new CheckoutPreviewService.Line(alMayor, null, 1000),
                new CheckoutPreviewService.Line(porUnidad, null, 40)), UUID.randomUUID());

        // 40 $ de la línea unitaria, no los 1.040 $ del carrito.
        verify(affiliate).referralDiscountCents(any(), eq(4_000L));
    }

    @Test
    @DisplayName("si el cupón no alcanza nada se dice, en vez de culpar a un descuento mejor")
    void siElCuponNoAlcanzaNadaSeDice() {
        // Lo destapó la certificación en el entorno real, no las pruebas: con TODAS las líneas a precio
        // de mayoreo el cupón cae a base cero, y el aviso que salía era «Ya tienes un descuento mejor
        // aplicado». El cliente no tenía ninguno. Un mensaje falso ahí manda a reintentar con otro
        // código creyendo que el suyo está caducado, y deja al servicio de atención buscando un
        // descuento que no existe.
        cuponDel50PorCiento();
        when(pricing.esPrecioDeMayoreo(any(), anyInt())).thenReturn(true);

        CheckoutPreviewService.Preview preview = service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(alMayor, null, 1000)), UUID.randomUUID(), "REBAJA50");

        assertThat(preview.couponError()).isEqualTo("Los precios por cantidad no admiten cupones");
        assertThat(preview.discountUsdCents()).isZero();
    }

    private void cuponDel50PorCiento() {
        PromotionEntity cupon = PromotionEntity.builder().id(UUID.randomUUID()).percentOff(new BigDecimal("50"))
                .build();
        when(promociones.checkCoupon(anyString(), any(), anyInt()))
                .thenReturn(new PromotionService.CouponCheck(true, null, cupon));
        when(promociones.reachableGrossCents(any(), any()))
                .thenAnswer(i -> i.<Map<UUID, Integer>>getArgument(1).values().stream().mapToInt(Integer::intValue)
                        .sum());
        lenient().when(affiliate.referralDiscountCents(any(), anyLong())).thenReturn(0L);
    }
}
