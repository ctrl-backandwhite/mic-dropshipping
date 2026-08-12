package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
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
            CheckoutTotalsService.CheckoutTotals totals,
            /** Cupón aplicado, o el motivo por el que no vale. Nulo cuando no se ha metido ninguno. */
            String couponCode, String couponError, UUID couponId) {

        /** Sin cupón: el resto del sistema no tiene por qué pasar tres nulos. */
        public Preview(ShippingQuote quote, int subtotalUsdCents, int discountUsdCents, int shippingUsdCents,
                int taxUsdCents, int taxRateBps, BigDecimal subtotalDisplay, BigDecimal discountDisplay,
                BigDecimal shippingDisplay, BigDecimal taxDisplay, BigDecimal totalDisplay,
                CheckoutTotalsService.CheckoutTotals totals) {
            this(quote, subtotalUsdCents, discountUsdCents, shippingUsdCents, taxUsdCents, taxRateBps,
                    subtotalDisplay, discountDisplay, shippingDisplay, taxDisplay, totalDisplay, totals,
                    null, null, null);
        }
    }

    private final ShippingQuoteService shippingQuoteService;
    private final CheckoutTotalsService checkoutTotalsService;
    private final PricingService pricingService;
    private final CurrencyRateService currencyService;
    private final ProductRepository productRepository;
    private final AffiliateProgramService affiliateProgramService;
    private final PromotionService promotionService;

    /**
     * Calcula el desglose del checkout para un carrito, destino y comprador dados.
     *
     * @param userId comprador autenticado, o {@code null} si es anónimo (entonces no hay descuento de
     *        referido, igual que en el cobro).
     */
    @Transactional(readOnly = true)
    public Preview compute(String country, String region, List<Line> items, UUID userId) {
        return compute(country, region, items, userId, null);
    }

    /**
     * Vista previa del checkout, con cupón opcional.
     *
     * <p>El cupón NO se suma a la rebaja que ya lleve el producto: se compara con ella y gana el mayor
     * descuento, que es la regla del sistema. Encadenarlos daría un porcentaje que nadie ha decidido.
     */
    public Preview compute(String country, String region, List<Line> items, UUID userId, String couponCode) {
        List<Line> lines = items == null ? List.of() : items;
        ShippingQuote quote = shippingQuoteService.quote(country, lines.stream()
                .map(i -> new ShippingQuoteService.Line(i.productId(), i.variantId(), i.quantity())).toList());

        // Subtotal en CÉNTIMOS USD (canónico, para el descuento y la base del IVA), y subtotal en la
        // MONEDA MOSTRADA calculado POR LÍNEA (unidad convertida y redondeada a 2 dec. × cantidad, sumado),
        // EXACTAMENTE igual que el carrito (/cart-quote), el detalle del pedido, la lista y la factura. Así
        // el desglose cuadra al céntimo en TODAS las vistas (antes el preview convertía el subtotal de una
        // sola vez → "round(total)" ≠ "round(unidad)×qty" del resto, y salía 1 cént. de diferencia).
        int subtotalUsdCents = 0;
        // Subtotal SIN rebajas: la referencia contra la que se mide el cupón.
        int grossSubtotalUsdCents = 0;
        BigDecimal subDispAcc = BigDecimal.ZERO;
        for (Line it : lines) {
            Integer unitCents = unitPriceUsdCents(it);
            if (unitCents == null) {
                continue;
            }
            int qty = Math.clamp(it.quantity(), 1, MAX_LINE_QUANTITY);
            subtotalUsdCents = Math.addExact(subtotalUsdCents, Math.multiplyExact(unitCents, qty));
            Integer originalCents = unitOriginalUsdCents(it);
            grossSubtotalUsdCents = Math.addExact(grossSubtotalUsdCents,
                    Math.multiplyExact(originalCents != null ? originalCents : unitCents, qty));
            // El importe que se ENSEÑA sale del precio de la ficha (displayAmount), no de convertir el
            // canónico en dólares. Los dos caminos difieren en un céntimo: la ficha compone el precio en
            // la moneda del cliente —base, IVA y envío convertidos y redondeados por separado— mientras
            // que el canónico los suma en dólares y convierte al final. Con el resumen del checkout
            // sumando 39,67 € y el total diciendo 39,65 €, el cliente ve unas cuentas que no cuadran.
            BigDecimal unitDisplay = unitPriceDisplay(it);
            if (unitDisplay != null) {
                subDispAcc = subDispAcc.add(unitDisplay.multiply(BigDecimal.valueOf(qty)));
            }
        }

        // Descuento de referido del COMPRADOR (10% del subtotal de producto) si tiene atribución de
        // afiliado viva y NO es su propio código. Mismo cálculo que el pedido (AffiliateProgramService),
        // para que el total mostrado coincida al céntimo con lo que se cobra. Anónimo → sin descuento.
        int discountUsdCents = (int) affiliateProgramService.referralDiscountCents(userId, subtotalUsdCents);
        // El cupón compite con el descuento de referido y con la rebaja que el producto ya trae: se
        // queda el MAYOR, nunca la suma. El subtotal aquí ya viene con la rebaja automática aplicada,
        // así que el cupón se mide sobre el importe SIN rebajar para que la comparación sea justa.
        String couponError = null;
        UUID couponId = null;
        if (couponCode != null && !couponCode.isBlank()) {
            PromotionService.CouponCheck check = promotionService.checkCoupon(couponCode, userId,
                    subtotalUsdCents);
            if (!check.valid()) {
                couponError = check.reason();
            } else {
                // Lo que la promoción del producto YA está descontando. Sin medirlo, el cupón se
                // aplicaría encima del precio rebajado y los dos se acumularían.
                int alreadyOff = Math.max(0, grossSubtotalUsdCents - subtotalUsdCents);
                int couponCents = couponDiscountCents(check.promotion(), grossSubtotalUsdCents);
                if (couponCents > Math.max(alreadyOff, discountUsdCents)) {
                    // El cupón gana: sustituye a la rebaja, no se suma. El descuento que se aplica es
                    // solo la DIFERENCIA, porque la rebaja ya está descontada del precio de línea.
                    discountUsdCents = couponCents - alreadyOff;
                    couponId = check.promotion().getId();
                } else {
                    // El cupón es peor que lo que ya tenía: se avisa en vez de aplicarlo en silencio,
                    // o el cliente cree que no se ha canjeado.
                    couponError = "Ya tienes un descuento mejor aplicado";
                }
            }
        }
        int discountedSubtotalUsdCents = subtotalUsdCents - discountUsdCents;

        int shippingBaseUsdCents = quote.supported() ? quote.amountUsdCents() : 0;
        // Artículos = productos DISTINTOS (varias unidades o variantes del mismo producto = 1 artículo),
        // para el arancel de la UE de 3 EUR por artículo.
        int articleCount = (int) items.stream().map(Line::productId).filter(p -> p != null).distinct().count();
        // Impuesto + despacho aduanero por el MISMO servicio que usa el cobro (CheckoutTotalsService), para
        // que el desglose mostrado coincida al céntimo con el pedido.
        CheckoutTotalsService.CheckoutTotals totals = checkoutTotalsService.compute(country, region,
                discountedSubtotalUsdCents, shippingBaseUsdCents, articleCount);

        // Importes en la moneda activa: cada componente convertido y REDONDEADO a 2 decimales; el total
        // es la SUMA de esos componentes redondeados (igual que el detalle del pedido), para que el
        // desglose cuadre exactamente en pantalla (subtotal − descuento + envío + IVA = total).
        BigDecimal subDisp = subDispAcc.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discDisp = currencyService.usdToDisplay(usd(discountUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shipDisp = currencyService.usdToDisplay(usd(totals.shippingCents())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxDisp = currencyService.usdToDisplay(usd(totals.taxCents())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDisp = subDisp.subtract(discDisp).add(shipDisp).add(taxDisp);

        return new Preview(quote, subtotalUsdCents, discountUsdCents, totals.shippingCents(), totals.taxCents(),
                totals.taxRateBps(), subDisp, discDisp, shipDisp, taxDisp, totalDisp, totals,
                couponId != null ? couponCode.trim().toUpperCase(java.util.Locale.ROOT) : null,
                couponError, couponId);
    }

    /**
     * Cuánto descuenta un cupón sobre el subtotal.
     *
     * <p>El importe fijo se topa al subtotal: un cupón de 20 € en un pedido de 12 € no puede dejar un
     * total negativo ni convertirse en dinero a devolver.
     */
    private static int couponDiscountCents(PromotionEntity coupon, int subtotalCents) {
        if (coupon.getPercentOff() != null) {
            return coupon.getPercentOff().multiply(BigDecimal.valueOf(subtotalCents))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue();
        }
        return coupon.getAmountOffCents() == null ? 0 : Math.min(coupon.getAmountOffCents(), subtotalCents);
    }

    /**
     * Precio unitario de la línea en céntimos USD, o {@code null} si la línea no es facturable: llegó
     * vacía, el producto ya no existe o no tiene precio de venta. Devolver {@code null} (y no cero) es
     * deliberado: una línea sin precio se OMITE del desglose, mientras que un cero sí sumaría al carrito.
     */
    /**
     * Precio unitario SIN la rebaja automática.
     *
     * <p>Hace falta para saber cuánto está descontando ya la promoción del producto: sin ese dato, un
     * cupón se aplicaría ENCIMA del precio rebajado y los dos descuentos se acumularían, que es justo
     * lo que la regla prohíbe.
     */
    private Integer unitOriginalUsdCents(Line it) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        PricingService.PricedAmount priced = pricingService.priceFor(p, v);
        BigDecimal original = priced.originalRetailUsd() != null ? priced.originalRetailUsd() : priced.retailUsd();
        return original == null ? null
                : original.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }

    private Integer unitPriceUsdCents(Line it) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        BigDecimal retail = pricingService.priceFor(p, v).retailUsd();
        if (retail == null) {
            return null;
        }
        // HALF_UP (céntimo más cercano) — el MISMO redondeo que el catálogo y que el pedido
        // (OrderUseCaseImpl), para que catálogo == carrito == preview == cobro, sin descuadre de 1 cént.
        return retail.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }

    /**
     * Precio unitario EN LA MONEDA DEL CLIENTE, el mismo que pinta la ficha y el carrito. Se pide a
     * {@code PricingService} en lugar de convertir el canónico para que el resumen del checkout sume
     * exactamente lo que el cliente tiene delante.
     */
    private BigDecimal unitPriceDisplay(Line it) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        return pricingService.priceFor(p, v).displayAmount();
    }

    private static BigDecimal usd(int cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
