package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.MarginService.PriceWithMargin;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.UUID;

/**
 * Canonical pricing pipeline:
 *
 * <pre>
 *   supplierPrice (in product.currency, e.g. CNY)
 *      → costUsd        (CurrencyRateService.toUsd)
 *      → retailUsd      (MarginService.apply: rule-resolved markup)
 *      → displayPrice   (CurrencyRateService.usdToDisplay: convert to user's currency via X-Currency header)
 * </pre>
 *
 * Stripe always charges in USD using {@code retailUsd}; the display number is purely
 * presentational on the store / partner API.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final CurrencyRateService currencyService;
    private final MarginService marginService;

    public PricedAmount priceFor(ProductEntity product, ProductVariantEntity variant) {
        // DROP-629: the product's headline price must be traceable to a real, purchasable
        // variant — not the disconnected base_price. When no specific variant is requested
        // (catalog list / detail headline) and the product has active variants, derive the
        // price from the representative (cheapest active) variant. A single-variant product
        // therefore prices exactly as its variant (delta 0%), and multi-variant products show
        // the "from" price that the customer can actually pay.
        if (product == null) {
            // representativeVariant ya contempla que el producto falte y devuelve null, pero tres líneas
            // más abajo se leía product.getBasePrice() sin comprobarlo. Sin producto no hay nada que
            // tarificar: se devuelve el mismo "sin precio" que un producto sin base_price, que las vistas
            // ya saben pintar (nunca 0, que se leería como gratis).
            return unpriced();
        }
        ProductVariantEntity effective = variant != null ? variant : representativeVariant(product);
        BigDecimal supplierAmount = effective != null && effective.getPrice() != null
                ? effective.getPrice()
                : product.getBasePrice();
        return priceForSupplierAmount(product, effective, supplierAmount);
    }

    /**
     * Tarifica un importe de proveedor concreto con las reglas del producto: margen sobre la base, y
     * después IVA y envío SIN margen.
     *
     * <p>Existe para que los tramos por cantidad pasen por AQUÍ y no por su propia cuenta. Los tramos
     * calculaban coste → USD → margen y se quedaban ahí, sin IVA ni envío: la ficha anunciaba «2+ →
     * 1,99 $» y al pagar se cobraban 3,57 $ por unidad. Con una sola fórmula, lo que se enseña y lo que
     * se cobra no pueden separarse otra vez.
     */
    public PricedAmount priceForSupplierAmount(ProductEntity product, ProductVariantEntity effective,
            BigDecimal supplierAmount) {
        if (product == null) {
            return unpriced();
        }
        String sourceCurrency = product.getCurrency() != null ? product.getCurrency() : "CNY";
        BigDecimal costUsd = supplierAmount != null ? currencyService.toUsd(supplierAmount, sourceCurrency) : null;
        PriceWithMargin withMargin = marginService.apply(costUsd, product, effective);
        // Base CON margen (el margen SOLO se aplica al precio base). El IVA y el envío se suman DESPUÉS,
        // sin margen (decisión del usuario). Ambos vienen en CNY (misma moneda que base) y se convierten a USD.
        BigDecimal retailBaseUsd = withMargin.retailUsd();
        BigDecimal ivaUsd = product.getIvaCny() != null
                ? currencyService.toUsd(product.getIvaCny(), sourceCurrency) : BigDecimal.ZERO;
        BigDecimal shippingUsd = product.getShippingCny() != null
                ? currencyService.toUsd(product.getShippingCny(), sourceCurrency) : BigDecimal.ZERO;
        String displayCode = CurrencyHolder.get();
        // Sin precio base (producto sin precio) → todo null (no se puede tarificar); no forzar 0.
        BigDecimal baseUsd = retailBaseUsd;
        // Cada componente se convierte con PRECISIÓN COMPLETA en USD y se redondea a 2 decimales SOLO al
        // pasar a la moneda mostrada (usdToDisplay, HALF_UP). Así un monto exacto de origen sale exacto
        // (30 CNY → ¥30.00). El TOTAL = suma de los componentes YA redondeados en la moneda mostrada, de
        // modo que el desglose SIEMPRE cuadra (base+IVA+envío = total) en cualquier divisa.
        BigDecimal displayBase = currencyService.usdToDisplay(baseUsd);
        BigDecimal displayIva = currencyService.usdToDisplay(ivaUsd);
        BigDecimal displayShip = currencyService.usdToDisplay(shippingUsd);
        // Cobro canónico en dólares: base, IVA y envío redondeados al céntimo y sumados. Es la cifra que
        // guarda el pedido y con la que se cobra.
        BigDecimal retailUsd = baseUsd == null ? null
                : baseUsd.setScale(2, RoundingMode.HALF_UP).add(ivaUsd.setScale(2, RoundingMode.HALF_UP))
                        .add(shippingUsd.setScale(2, RoundingMode.HALF_UP));
        // Y el precio que se ENSEÑA es ese mismo importe convertido, no la suma de los tres componentes
        // convertidos por separado. Parece equivalente y no lo es: componer en euros y componer en
        // dólares dan resultados que difieren en un céntimo, y con eso el escaparate anunciaba 14,79 €
        // mientras el pedido se cobraba a 14,78 €. Derivándolo del canónico, lo que se enseña ES lo que
        // se cobra, en cualquier moneda y sin más cuentas de por medio.
        BigDecimal displayTotal = currencyService.usdToDisplay(retailUsd);
        // El string formateado lo produce el BACKEND (locale de la moneda en BD); el frontend solo pinta.
        String displayFormatted = currencyService.formatDisplay(displayTotal, displayCode);
        String baseFormatted = currencyService.formatDisplay(displayBase, displayCode);
        String ivaFormatted = currencyService.formatDisplay(displayIva, displayCode);
        String shippingFormatted = currencyService.formatDisplay(displayShip, displayCode);
        return new PricedAmount(costUsd, retailUsd, displayTotal, displayCode, currencyService.symbolOf(displayCode),
                displayFormatted, withMargin.appliedRule() != null ? withMargin.appliedRule().getId() : null,
                withMargin.appliedPercentage(), baseUsd, ivaUsd, shippingUsd, baseFormatted, ivaFormatted,
                shippingFormatted);
    }

    /** Resultado "no se puede tarificar": todos los importes a null, nunca 0. */
    private PricedAmount unpriced() {
        String displayCode = CurrencyHolder.get();
        return new PricedAmount(null, null, null, displayCode, currencyService.symbolOf(displayCode),
                null, null, null, null, null, null, null, null, null);
    }

    /** null → 0 (para sumar componentes de desglose cuando IVA/envío son 0 y la conversión devuelve null). */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public PricedAmount priceFor(ProductEntity product) {
        return priceFor(product, null);
    }

    /**
     * DROP-629: representative variant used to price the product headline — the cheapest
     * active variant with a real price. Returns {@code null} (→ fall back to base_price) when
     * there are no priced active variants, or when variants are lazily detached outside a
     * transaction (defensive: pricing is also called from list/search contexts).
     */
    private ProductVariantEntity representativeVariant(ProductEntity product) {
        if (product == null) {
            return null;
        }
        try {
            List<ProductVariantEntity> variants = product.getVariants();
            if (variants == null || variants.isEmpty()) {
                return null;
            }
            return variants.stream()
                    .filter(v -> v != null && v.isActive() && v.getPrice() != null && v.getPrice().signum() > 0)
                    .min(Comparator.comparing(ProductVariantEntity::getPrice))
                    .orElse(null);
        } catch (RuntimeException lazyOutsideTx) {
            return null;
        }
    }

    public BigDecimal convertUsdToDisplay(BigDecimal amountUsd) {
        return currencyService.usdToDisplay(amountUsd);
    }

    public String displayCurrencyCode() {
        return CurrencyHolder.get();
    }

    public String displayCurrencySymbol() {
        return currencyService.symbolOf(CurrencyHolder.get());
    }

    public record PricedAmount(BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayAmount,
            String displayCurrency, String displaySymbol, String displayFormatted, UUID appliedRuleId,
            BigDecimal appliedMarginPercent,
            // Desglose (solo informativo, para el admin): base con margen + IVA + envío = total.
            BigDecimal baseRetailUsd, BigDecimal ivaUsd, BigDecimal shippingUsd,
            String baseFormatted, String ivaFormatted, String shippingFormatted) {
    }
}
