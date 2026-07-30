package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Vista previa del checkout: lo que verá el comprador antes de pagar (subtotal, descuento de referido,
 * envío, impuesto y total), en su divisa y ya formateado.
 *
 * <p>Estaba dentro del endpoint de cotización. Es el cálculo más delicado de la aplicación —si la
 * previsualización no coincide al céntimo con el cobro, el cliente ve un importe y se le cobra otro— y
 * los redondeos están alineados a propósito con el carrito, el detalle del pedido, la lista y la factura.
 * Por eso vive en un servicio, junto al resto del cálculo de dinero, y no en la capa HTTP.
 */
@Service
@RequiredArgsConstructor
public class CheckoutPreviewService {

    /**
     * Tope de unidades por línea, el MISMO que aplica el checkout al cobrar
     * ({@code OrderUseCaseImpl.MAX_LINE_QUANTITY}). Sin acotar, una cantidad enorme desbordaba el entero
     * y la base del impuesto salía negativa: el preview mostraba 0 de IVA y el cobro real no.
     */
    private static final int MAX_LINE_QUANTITY = 100_000;

    /** Una línea a previsualizar. */
    public record Line(UUID productId, UUID variantId, int quantity) {
    }

    /** Desglose ya resuelto, en céntimos USD canónicos y en la divisa que ve el comprador. */
    public record Preview(ShippingQuote quote, int subtotalUsdCents, int discountUsdCents, int shippingUsdCents,
            int taxUsdCents, int taxRateBps, BigDecimal subtotalDisplay, BigDecimal discountDisplay,
            BigDecimal shippingDisplay, BigDecimal taxDisplay, BigDecimal totalDisplay,
            CheckoutTotalsService.CheckoutTotals totals) {
    }

    private final ShippingQuoteService shippingQuoteService;
    private final CheckoutTotalsService checkoutTotalsService;
    private final PricingService pricingService;
    private final CurrencyRateService currencyService;
    private final ProductRepository productRepository;
    private final AffiliateProgramService affiliateProgramService;

    /**
     * Calcula el desglose del checkout para un carrito, destino y comprador dados.
     *
     * @param userId comprador autenticado, o {@code null} si es anónimo (entonces no hay descuento de
     *        referido, igual que en el cobro).
     */
    @Transactional(readOnly = true)
    public Preview compute(String country, String region, List<Line> items, UUID userId) {
        List<Line> lines = items == null ? List.of() : items;
        ShippingQuote quote = shippingQuoteService.quote(country, lines.stream()
                .map(i -> new ShippingQuoteService.Line(i.productId(), i.variantId(), i.quantity())).toList());

        // Subtotal en CÉNTIMOS USD (canónico, para el descuento y la base del IVA), y subtotal en la
        // MONEDA MOSTRADA calculado POR LÍNEA (unidad convertida y redondeada a 2 dec. × cantidad, sumado),
        // EXACTAMENTE igual que el carrito (/cart-quote), el detalle del pedido, la lista y la factura. Así
        // el desglose cuadra al céntimo en TODAS las vistas (antes el preview convertía el subtotal de una
        // sola vez → "round(total)" ≠ "round(unidad)×qty" del resto, y salía 1 cént. de diferencia).
        int subtotalUsdCents = 0;
        BigDecimal subDispAcc = BigDecimal.ZERO;
        for (Line it : lines) {
            if (it == null || it.productId() == null) {
                continue;
            }
            ProductEntity p = productRepository.findById(it.productId()).orElse(null);
            if (p == null) {
                continue;
            }
            ProductVariantEntity v = it.variantId() == null ? null
                    : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
            BigDecimal retail = pricingService.priceFor(p, v).retailUsd();
            if (retail == null) {
                continue;
            }
            // HALF_UP (céntimo más cercano) — el MISMO redondeo que el catálogo y que el pedido
            // (OrderUseCaseImpl), para que catálogo == carrito == preview == cobro, sin descuadre de 1 cént.
            int unitCents = retail.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
            int qty = Math.clamp(1, it.quantity(), MAX_LINE_QUANTITY);
            subtotalUsdCents = Math.addExact(subtotalUsdCents, Math.multiplyExact(unitCents, qty));
            // Unidad en la moneda mostrada, redondeada a 2 dec., × cantidad (misma unidad que carrito/detalle).
            subDispAcc = subDispAcc.add(currencyService.usdToDisplay(usd(unitCents)).multiply(BigDecimal.valueOf(qty)));
        }

        // Descuento de referido del COMPRADOR (10% del subtotal de producto) si tiene atribución de
        // afiliado viva y NO es su propio código. Mismo cálculo que el pedido (AffiliateProgramService),
        // para que el total mostrado coincida al céntimo con lo que se cobra. Anónimo → sin descuento.
        int discountUsdCents = (int) affiliateProgramService.referralDiscountCents(userId, subtotalUsdCents);
        int discountedSubtotalUsdCents = subtotalUsdCents - discountUsdCents;

        int shippingBaseUsdCents = quote.supported() ? quote.amountUsdCents() : 0;
        // Impuesto + despacho aduanero por el MISMO servicio que usa el cobro (CheckoutTotalsService), para
        // que el desglose mostrado coincida al céntimo con el pedido.
        CheckoutTotalsService.CheckoutTotals totals = checkoutTotalsService.compute(country, region,
                discountedSubtotalUsdCents, shippingBaseUsdCents);

        // Importes en la moneda activa: cada componente convertido y REDONDEADO a 2 decimales; el total
        // es la SUMA de esos componentes redondeados (igual que el detalle del pedido), para que el
        // desglose cuadre exactamente en pantalla (subtotal − descuento + envío + IVA = total).
        BigDecimal subDisp = subDispAcc.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discDisp = currencyService.usdToDisplay(usd(discountUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shipDisp = currencyService.usdToDisplay(usd(totals.shippingCents())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxDisp = currencyService.usdToDisplay(usd(totals.taxCents())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDisp = subDisp.subtract(discDisp).add(shipDisp).add(taxDisp);

        return new Preview(quote, subtotalUsdCents, discountUsdCents, totals.shippingCents(), totals.taxCents(),
                totals.taxRateBps(), subDisp, discDisp, shipDisp, taxDisp, totalDisp, totals);
    }

    private static BigDecimal usd(int cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
