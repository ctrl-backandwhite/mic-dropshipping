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
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;

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
    /** Rebajas vigentes: el precio de escaparate sale ya descontado. */
    private final PromotionService promotionService;
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
     * Tarifica un importe de proveedor concreto con las reglas del producto: el margen se aplica sobre el
     * COSTE COMPLETO del proveedor —base + IVA chino + porte—, no solo sobre la base.
     *
     * <p>Existe para que los tramos por cantidad pasen por AQUÍ y no por su propia cuenta. Los tramos
     * calculaban coste → USD → margen y se quedaban ahí, sin IVA ni envío: la ficha anunciaba «2+ →
     * 1,99 $» y al pagar se cobraban 3,57 $ por unidad. Con una sola fórmula, lo que se enseña y lo que
     * se cobra no pueden separarse otra vez.
     *
     * <p><b>Sobre qué se aplica el margen (25-ago-2026).</b> Hasta esta fecha el margen gravaba solo la
     * base y el IVA y el porte se sumaban en crudo, de modo que dos tercios del desembolso real —el porte
     * de 16 CNY pesa más que muchos artículos— viajaban sin un céntimo de margen. Ahora el porcentaje se
     * aplica al desembolso entero: {@code (base + IVA + porte) × (1 + margen)}. Se implementa como un
     * FACTOR derivado de {@code retail / coste} en vez de releer el porcentaje, para que un margen de tipo
     * FIXED —que suma dólares en vez de multiplicar— reparta su parte proporcional igual que uno
     * porcentual, sin una segunda rama que mantener.
     */
    public PricedAmount priceForSupplierAmount(ProductEntity product, ProductVariantEntity effective,
            BigDecimal supplierAmount) {
        if (product == null) {
            return unpriced();
        }
        String sourceCurrency = product.getCurrency() != null ? product.getCurrency() : "CNY";
        BigDecimal costUsd = supplierAmount != null ? currencyService.toUsd(supplierAmount, sourceCurrency) : null;
        PriceWithMargin withMargin = marginService.apply(costUsd, product, effective);
        BigDecimal retailBaseUsd = withMargin.retailUsd();
        // Lo que se le debe al PROVEEDOR, sin margen. Se conserva porque son dos cosas distintas de las que
        // dependen cálculos distintos: la subvención por porte repetido devuelve el porte que de verdad no
        // se gasta (estos 16 CNY), mientras que el cliente paga ese porte ya con margen.
        BigDecimal supplierIvaUsd = product.getIvaCny() != null
                ? currencyService.toUsd(product.getIvaCny(), sourceCurrency) : BigDecimal.ZERO;
        BigDecimal supplierShippingUsd = product.getShippingCny() != null
                ? currencyService.toUsd(product.getShippingCny(), sourceCurrency) : BigDecimal.ZERO;
        BigDecimal marginFactor = marginFactor(costUsd, retailBaseUsd);
        BigDecimal ivaUsd = supplierIvaUsd.multiply(marginFactor);
        BigDecimal shippingUsd = supplierShippingUsd.multiply(marginFactor);
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
        // Rebaja. Se aplica sobre el precio YA compuesto y en la moneda que se enseña, para que el
        // porcentaje anunciado sea el que el cliente ve descontado y no difiera por redondeos.
        // Suelo: el PRECIO BASE del producto (coste × margen), decisión del usuario del 8-ago-2026.
        // Ninguna promoción baja de ahí por mucho que diga su porcentaje.
        //
        // Se eligió la base y no «coste + envío» por dos motivos. Uno, aquel suelo se olvidaba del IVA
        // —cubría producto y porte pero no el impuesto, así que dejaba vender con pérdida—. Y dos, la
        // base ya lleva dentro el coste y deja un margen aunque el descuento llegue al tope, mientras
        // que rozar el coste desnudo convierte cada venta rebajada en trabajo gratis.
        BigDecimal floorDisplay = displayBase;
        // REGLA ESTRICTA: las rebajas y promociones son SOLO del escaparate propio (web/app NX036). El
        // canal de integración (Shopify/WooCommerce/API de partners) vende con su propio margen y NUNCA
        // se le aplica un descuento: si un partner revende, la promoción es decisión suya, no nuestra, y
        // regalársela le comería el margen que paga por integrarse. Ver [[price-rule-channel]].
        PromotionService.Discounted deal = PricingChannelHolder.get() == PriceRuleChannel.STOREFRONT
                ? promotionService.applyAutomatic(product, displayTotal, floorDisplay)
                : PromotionService.Discounted.none(displayTotal);
        String originalFormatted = null;
        Integer discountPercent = null;
        String promotionName = null;
        BigDecimal originalRetailUsd = retailUsd;
        if (deal.applies()) {
            originalFormatted = currencyService.formatDisplay(displayTotal, displayCode);
            discountPercent = deal.percentOff().intValue();
            promotionName = deal.promotionName();
            displayTotal = deal.finalAmount();
            // El cobro real también baja: si solo cambiara el escaparate, se anunciaría una rebaja que
            // el cliente no llega a pagar. Se aplica el MISMO porcentaje al importe en dólares en vez
            // de reconvertir desde la moneda mostrada: ida y vuelta por el tipo de cambio introduce un
            // céntimo de deriva, y ahí es donde el precio anunciado deja de coincidir con el cobrado.
            BigDecimal factor = deal.finalAmount().divide(deal.original(), 8, RoundingMode.HALF_UP);
            retailUsd = retailUsd == null ? null : retailUsd.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        // El string formateado lo produce el BACKEND (locale de la moneda en BD); el frontend solo pinta.
        String displayFormatted = currencyService.formatDisplay(displayTotal, displayCode);
        String baseFormatted = currencyService.formatDisplay(displayBase, displayCode);
        String ivaFormatted = currencyService.formatDisplay(displayIva, displayCode);
        String shippingFormatted = currencyService.formatDisplay(displayShip, displayCode);
        // Ganancia real de la línea: lo que se cobra menos TODO lo que se le debe al proveedor (coste, su
        // IVA y su porte). Se calcula aquí, donde están los tres importes sin margen, y no en quien la
        // consume: la vista previa y el pedido la necesitaban por separado y cada uno la derivaba a su
        // manera, que es como se acaba con dos cifras de ganancia que no coinciden.
        BigDecimal profitUsd = retailUsd == null ? null
                : retailUsd.subtract(nvl(costUsd)).subtract(supplierIvaUsd).subtract(supplierShippingUsd);
        return new PricedAmount(costUsd, retailUsd, displayTotal, displayCode, currencyService.symbolOf(displayCode),
                displayFormatted, withMargin.appliedRule() != null ? withMargin.appliedRule().getId() : null,
                withMargin.appliedPercentage(), baseUsd, ivaUsd, shippingUsd, baseFormatted, ivaFormatted,
                shippingFormatted, originalFormatted, discountPercent, promotionName, originalRetailUsd,
                supplierShippingUsd, profitUsd);
    }

    /**
     * Cuánto multiplica el margen al coste, para repartirlo también sobre el IVA y el porte del proveedor.
     *
     * <p>Sin coste no hay proporción que calcular —dividir daría ArithmeticException— así que se devuelve
     * 1: el IVA y el porte se cobran tal cual. Es el caso del producto sin precio, que no se tarifica.
     */
    private static BigDecimal marginFactor(BigDecimal costUsd, BigDecimal retailBaseUsd) {
        if (costUsd == null || costUsd.signum() <= 0 || retailBaseUsd == null) {
            return BigDecimal.ONE;
        }
        return retailBaseUsd.divide(costUsd, 8, RoundingMode.HALF_UP);
    }

    /** Cero cuando falta el importe: sumar null en una cadena de BigDecimal revienta con NullPointerException. */
    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Resultado "no se puede tarificar": todos los importes a null, nunca 0. */
    private PricedAmount unpriced() {
        String displayCode = CurrencyHolder.get();
        return new PricedAmount(null, null, null, displayCode, currencyService.symbolOf(displayCode),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
            String baseFormatted, String ivaFormatted, String shippingFormatted,
            // Rebaja. Nulos cuando el producto no está en promoción, que es lo que el escaparate usa
            // para decidir si pinta el precio tachado o solo uno.
            String originalFormatted, Integer discountPercent, String promotionName,
            /**
             * Importe en USD ANTES de la rebaja. Hace falta para comparar descuentos a nivel de pedido:
             * el cupón se mide contra el precio sin rebajar, o compararlo con el ya rebajado acumularía
             * los dos y daría un descuento que nadie ha decidido.
             */
            BigDecimal originalRetailUsd,
            /**
             * El porte del proveedor SIN margen: los 16 CNY de suelo que se le pagan por mandar el bulto
             * al almacén. El cliente paga ese mismo porte con margen encima ({@code shippingUsd}); esta
             * cifra es la que devuelve la subvención cuando se compra más de una unidad, porque es el
             * gasto que de verdad no se repite.
             */
            BigDecimal supplierShippingUsd,
            /** Lo que gana la plataforma con esta línea: cobrado − (coste + IVA + porte del proveedor). */
            BigDecimal profitUsd) {

        /**
         * Precio sin promoción.
         *
         * <p>La mayoría de los usos —y de las pruebas— no se ocupan de rebajas, y obligarles a pasar
         * tres nulos solo añade ruido a cada llamada.
         */
        public PricedAmount(BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayAmount,
                String displayCurrency, String displaySymbol, String displayFormatted, UUID appliedRuleId,
                BigDecimal appliedMarginPercent, BigDecimal baseRetailUsd, BigDecimal ivaUsd,
                BigDecimal shippingUsd, String baseFormatted, String ivaFormatted, String shippingFormatted) {
            this(costUsd, retailUsd, displayAmount, displayCurrency, displaySymbol, displayFormatted,
                    appliedRuleId, appliedMarginPercent, baseRetailUsd, ivaUsd, shippingUsd, baseFormatted,
                    ivaFormatted, shippingFormatted, null, null, null, null, shippingUsd, null);
        }

        /** ¿Este precio lleva rebaja? Lo pregunta el frontend para tachar el precio anterior. */
        public boolean discounted() {
            return discountPercent != null && discountPercent > 0;
        }
    }
}
