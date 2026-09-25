package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.MarginService.PriceWithMargin;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
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
    /** Rebajas vigentes: el precio de escaparate sale ya descontado. */
    private final PromotionService promotionService;
    private final MarginService marginService;
    /** Solo para saber si el destino del comprador cobra derecho por artículo. */
    private final CustomsValuationService customsValuation;

    private static final BigDecimal CIEN = new BigDecimal("100");

    /**
     * El precio de una CANTIDAD concreta: aplica el tramo por cantidad que le toca (23-sep-2026).
     *
     * <p>Hasta esta fecha la tabla de cantidades de la ficha era pura decoracion. Anunciaba «1000+ ->
     * 5,14 EUR», el cliente metia mil unidades en la cesta y se le cobraban 5,78 EUR cada una, porque
     * ni la cesta, ni la vista previa, ni el pedido consultaban los tramos. La promesa que hace vender
     * mas unidades no se cumplia en la unica pantalla donde importa: la del pago.
     *
     * <p><b>El tramo se aplica como PROPORCION sobre el precio de la variante, no como importe
     * absoluto.</b> Los tramos son del PRODUCTO —las 19.568 filas del catalogo tienen la variante a
     * nulo— mientras que cada variante tiene su propio coste, y en 713 productos hay una variante mas
     * cara que el primer tramo, hasta 300 CNY por encima. Cobrar a esa variante el importe literal del
     * tramo seria venderla por debajo de coste justo en los pedidos grandes, que es donde mas duele.
     * Con la proporcion, el descuento por volumen que declara el proveedor se respeta y el sobreprecio
     * de la variante tambien.
     *
     * <p>La proporcion se mide contra el PRIMER tramo, que es el precio de una unidad. Asi el tramo
     * inicial vale exactamente lo mismo que el precio de portada —factor 1— y la tabla de cantidades
     * deja de contradecir a la cifra grande de la ficha, cosa que hoy hace en esos mismos 713.
     *
     * @param quantity unidades de ESTE producto en todo el pedido, no solo en esta linea: el pedido
     *                 minimo ya se cuenta asi, y a quien se lleva cien unidades repartidas en dos
     *                 tallas hay que hacerle el mismo precio que a quien se lleva cien de una.
     */
    public PricedAmount priceFor(ProductEntity product, ProductVariantEntity variant, int quantity,
            List<ProductPriceTierEntity> tiers) {
        BigDecimal factor = factorDelTramo(tiers, quantity);
        ProductPriceTierEntity aplicable = tramoAplicable(tiers, quantity);
        // El recargo del tramo manda sobre el del producto; nulo = el tramo no tiene uno propio.
        BigDecimal recargoDelTramo = aplicable != null ? aplicable.getSurchargeCny() : null;
        // REGLA DEL TITULAR (25-sep-2026): las rebajas son del PRECIO UNITARIO. Sobre un precio de
        // mayoreo no se descuenta nada. Ver esMayoreo().
        boolean aplicaRebaja = !esMayoreo(tiers, aplicable);

        // Sin descuento que aplicar Y sin recargo propio no hay nada que cambiar.
        //
        // Las dos condiciones, y la segunda faltaba: `factorDelTramo` devuelve nulo cuando el tramo
        // ES el primero —no hay proporcion que medir contra si mismo— y por ahi se salia al precio
        // normal, que usa el recargo del PRODUCTO. Resultado: un recargo escrito para 1-9 unidades
        // se guardaba en `product_price_tier.surcharge_cny` y no se cobraba nunca, sin un solo
        // error. Y el primer tramo lo tienen TODOS los productos, asi que era justo el caso mas
        // comun del cambio del 23-sep-2026.
        if (factor == null && recargoDelTramo == null) {
            return priceFor(product, variant, aplicaRebaja);
        }
        ProductVariantEntity effective = variant != null ? variant : representativeVariant(product);
        BigDecimal base = effective != null && effective.getPrice() != null
                ? effective.getPrice()
                : product.getBasePrice();
        if (base == null) {
            return priceFor(product, variant, aplicaRebaja);
        }
        // Sin proporcion se cobra la base tal cual: el primer escalon no descuenta nada, pero su
        // recargo si cuenta.
        BigDecimal proporcion = factor != null ? factor : BigDecimal.ONE;
        return priceForSupplierAmount(product, effective, base.multiply(proporcion), recargoDelTramo, aplicaRebaja);
    }

    /**
     * Si esta cantidad se esta cobrando a precio de MAYOREO, es decir, con un escalon de la tabla de
     * cantidades que no es el primero.
     *
     * <p><b>Por qué existe (regla del titular, 25-sep-2026):</b> las rebajas y los cupones son del
     * precio unitario. El precio de mayoreo ya ES el descuento —lo concede el proveedor por volumen— y
     * encadenar encima una promoción del escaparate descuenta dos veces sobre el mismo margen, que en
     * los tramos altos es justo el más estrecho. Un −30 % sobre el tramo de 1.000 unidades no es una
     * campaña: es vender por debajo de coste mil veces.
     *
     * <p>El PRIMER escalón no cuenta como mayoreo: vale exactamente lo mismo que la cifra grande de la
     * ficha —factor 1— y es el precio por unidad de toda la vida. Ahí la rebaja se aplica igual que
     * siempre, que es lo que sostiene las campañas del escaparate.
     *
     * <p>Basta con que el escalón aplicado no sea el primero, sin mirar si de verdad baja el precio: un
     * tramo que solo cambia el recargo sigue siendo una venta al por mayor, y la regla se escribió sobre
     * la tabla de cantidades, no sobre la proporción.
     */
    /**
     * Si esta cantidad de este producto se cobra a precio de mayoreo.
     *
     * <p>Lo necesitan el checkout y el pedido para dejar las líneas de mayoreo FUERA del alcance de los
     * cupones y del descuento de referido. La rebaja automática se corta antes, dentro del propio
     * cálculo del precio; estos dos no, porque se aplican al pedido entero y no a un precio suelto.
     */
    public boolean esPrecioDeMayoreo(List<ProductPriceTierEntity> tiers, int quantity) {
        return esMayoreo(tiers, tramoAplicable(tiers, quantity));
    }

    private boolean esMayoreo(List<ProductPriceTierEntity> tiers, ProductPriceTierEntity aplicable) {
        if (aplicable == null || tiers == null || tiers.isEmpty()) {
            return false;
        }
        ProductPriceTierEntity primero = tiers.stream().min(Comparator.comparingInt(ProductPriceTierEntity::getMinQty))
                .orElse(null);
        return primero != null && aplicable != primero;
    }

    /**
     * Cuanto multiplica al precio el tramo que corresponde a esta cantidad, o nulo si no hay descuento
     * que aplicar (sin tramos, cantidad en el primer escalon, o datos que no permiten dividir).
     */
    private BigDecimal factorDelTramo(List<ProductPriceTierEntity> tiers, int quantity) {
        ProductPriceTierEntity tramo = tramoAplicable(tiers, quantity);
        if (tramo == null || tramo.getUnitPrice() == null) {
            return null;
        }
        ProductPriceTierEntity primero = tiers.stream().min(Comparator.comparingInt(ProductPriceTierEntity::getMinQty))
                .orElse(null);
        // Sin un primer tramo utilizable no hay contra que medir la proporcion. Se deja el precio como
        // esta en vez de inventar una referencia: un factor mal calculado se cobra.
        if (primero == null || primero.getUnitPrice() == null || primero.getUnitPrice().signum() <= 0) {
            return null;
        }
        if (tramo == primero) {
            return null;
        }
        return tramo.getUnitPrice().divide(primero.getUnitPrice(), 10, RoundingMode.HALF_UP);
    }

    /**
     * El tramo cuyo rango [minQty, maxQty] contiene la cantidad.
     *
     * <p>Gana el de mayor minQty de los que la cantidad alcanza: son escalones «a partir de N», no
     * franjas sueltas, y el proveedor no siempre cierra el maximo del ultimo.
     */
    private ProductPriceTierEntity tramoAplicable(List<ProductPriceTierEntity> tiers, int quantity) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        ProductPriceTierEntity mejor = null;
        for (ProductPriceTierEntity t : tiers) {
            Integer max = t.getMaxQty();
            boolean cabe = quantity >= t.getMinQty() && (max == null || quantity <= max);
            if (cabe && (mejor == null || t.getMinQty() > mejor.getMinQty())) {
                mejor = t;
            }
        }
        return mejor;
    }

    public PricedAmount priceFor(ProductEntity product, ProductVariantEntity variant) {
        return priceFor(product, variant, true);
    }

    /**
     * Lo mismo, indicando si este precio admite rebaja.
     *
     * <p>{@code aplicaRebaja} en false es la regla del titular del 25-sep-2026: un precio de mayoreo no
     * se rebaja. Ver {@link #esMayoreo}.
     */
    private PricedAmount priceFor(ProductEntity product, ProductVariantEntity variant, boolean aplicaRebaja) {
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
        return priceForSupplierAmount(product, effective, supplierAmount, null, aplicaRebaja);
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
        return priceForSupplierAmount(product, effective, supplierAmount, null);
    }

    /**
     * Lo mismo, pero con el recargo de un TRAMO concreto en vez del del producto (23-sep-2026).
     *
     * <p>El recargo cubre un coste que no escala con la cantidad -la gestion de la compra, el
     * manipulado, la parte fija del despacho-, asi que cobrarlo igual por una unidad que por diez mil
     * encarece el pedido grande justo donde la tabla de cantidades promete lo contrario. El envio y el
     * arancel NO se tocan: esos si escalan con el bulto y siguen siendo uno por producto.
     *
     * <p>{@code surchargeOverrideCny} nulo significa «este tramo no tiene recargo propio», y entonces
     * se usa el del producto. Nulo y cero son cosas distintas a proposito: cero es un recargo de cero
     * que alguien ha escrito, y tiene que poder escribirse.
     */
    public PricedAmount priceForSupplierAmount(ProductEntity product, ProductVariantEntity effective,
            BigDecimal supplierAmount, BigDecimal surchargeOverrideCny) {
        return priceForSupplierAmount(product, effective, supplierAmount, surchargeOverrideCny, true);
    }

    /**
     * Lo mismo, diciendo ademas si este precio admite rebaja.
     *
     * <p>{@code aplicaRebaja} en false deja fuera TODA promoción del escaparate —rebajas, campañas y
     * cualquier otra— porque el importe que se está tarificando es de mayoreo y ya lleva dentro el
     * descuento por volumen del proveedor. Los cupones se cortan aparte, en el checkout y en el pedido,
     * porque se aplican al pedido entero y no a un precio suelto.
     */
    public PricedAmount priceForSupplierAmount(ProductEntity product, ProductVariantEntity effective,
            BigDecimal supplierAmount, BigDecimal surchargeOverrideCny, boolean aplicaRebaja) {
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
        // MARGEN INTERNO (25-sep-2026): lo que antes viajaba como «IVA chino» y nunca lo fue.
        //
        // Valía siempre el 50 % exacto de la base —el IVA de China es el 13 %—, o sea que era margen
        // nuestro con el nombre de otra cosa, y guardado como importe fijo en yuanes: al cambiar el
        // coste del proveedor había que rehacerlo a mano o se quedaba desfasado en silencio. Ahora es
        // un PORCENTAJE sobre el coste, así que sigue al coste sin que nadie lo toque.
        //
        // Se calcula sobre `costUsd`, que es el importe que se está tarificando: con la tabla de
        // cantidades, eso significa que el margen baja con el tramo en la misma proporción que el
        // coste, que es justo lo que se espera de un porcentaje.
        //
        // Nulo aporta CERO, exactamente igual que aportaba el importe nulo de antes. No se inventa un
        // porcentaje por defecto: eso encarecería en silencio cualquier producto al que le falte el
        // dato, y la regla de este cambio es que renombrar un concepto no mueva el precio de nadie. La
        // carga masiva exige el campo, así que un producto real siempre lo trae.
        BigDecimal margenPct = product.getMargenInternoPct();
        BigDecimal margenInternoBaseUsd = costUsd != null && margenPct != null && margenPct.signum() != 0
                ? costUsd.multiply(margenPct).divide(CIEN, 10, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        // El envío de la VARIANTE manda sobre el del producto (23-sep-2026).
        //
        // El envío nacional chino se calcula por tramos de PESO, y en una misma ficha una talla
        // pesa 800 g y otra 1,2 kg: 10 CNY y 16. Con un único importe por producto había que
        // elegir entre cobrar de menos en la pesada o de más en la ligera.
        //
        // Mismo criterio que el recargo del tramo, y por el mismo motivo: nulo = esta variante no
        // declara envío propio y hereda el del producto. Las 225.959 variantes ya cargadas tienen
        // la columna vacía, así que tratar el nulo como cero las pondría a portes gratis.
        BigDecimal envioCny = effective != null && effective.getShippingCny() != null
                ? effective.getShippingCny()
                : product.getShippingCny();
        BigDecimal supplierShippingUsd = envioCny != null
                ? currencyService.toUsd(envioCny, sourceCurrency)
                : BigDecimal.ZERO;
        // Recargo fijo por producto (surcharge_cny, default 0): lo fija el admin y se suma al precio de
        // venta tal cual, SIN margen — es un cargo directo que él decide, no un coste de proveedor.
        BigDecimal surchargeCny = surchargeOverrideCny != null ? surchargeOverrideCny : product.getSurchargeCny();
        BigDecimal surchargeUsd = surchargeCny != null && surchargeCny.signum() != 0
                ? currencyService.toUsd(surchargeCny, sourceCurrency)
                : BigDecimal.ZERO;
        // Bolsas de subvención (1-sep-2026): igual que el recargo, se suman al precio de venta SIN margen.
        // El cliente las paga aquí y se le descuentan después del envío y del arancel del pedido, que es
        // lo que permite anunciar el envío cubierto sin regalar margen.
        BigDecimal shippingUserUsd = product.getShippingUserCny() != null && product.getShippingUserCny().signum() > 0
                ? currencyService.toUsd(product.getShippingUserCny(), sourceCurrency)
                : BigDecimal.ZERO;
        // La bolsa de ARANCEL solo se cobra donde hay un arancel del que descontarla.
        //
        // El subsidio de arancel no es un descuento: es un importe que el comprador PAGA por adelantado y
        // que luego se le descuenta del derecho de aduana de su pedido. En los destinos que no cobran
        // derecho por artículo —Latinoamérica, Estados Unidos y los otros cincuenta y nueve países con el
        // importe a cero en `country_customs_rule`— no hay nada que descontar, así que cobrarlo era cobrar
        // de más por un concepto que ese comprador no llega a pagar nunca. Los veintisiete de la Unión
        // Europea sí lo llevan (3 EUR por línea de declaración) y ahí se cobra íntegro.
        //
        // El país es el del COMPRADOR, no el del envío: es la misma regla que ya gobierna el margen.
        // Sin país conocido no se cobra, por el mismo criterio que el resto de esta zona —un dato que falta
        // no encarece a nadie—; en cuanto se resuelve el país, el precio ya lo refleja.
        //
        // Se consulta por producto, de ahí que la lectura esté cacheada: sin eso, un listado de veinticuatro
        // fichas serían veinticuatro consultas.
        boolean elDestinoCobraArancel = customsValuation.perArticleFeeUsdCents(PricingCountryHolder.get()) > 0;
        BigDecimal dutyUserUsd = elDestinoCobraArancel && product.getDutyUserCny() != null
                && product.getDutyUserCny().signum() > 0
                        ? currencyService.toUsd(product.getDutyUserCny(), sourceCurrency)
                        : BigDecimal.ZERO;
        BigDecimal marginFactor = marginFactor(costUsd, retailBaseUsd);
        BigDecimal margenInternoUsd = margenInternoBaseUsd.multiply(marginFactor);
        BigDecimal shippingUsd = supplierShippingUsd.multiply(marginFactor);
        String displayCode = CurrencyHolder.get();
        // Sin precio base (producto sin precio) → todo null (no se puede tarificar); no forzar 0.
        BigDecimal baseUsd = retailBaseUsd;
        // Cada componente se convierte con PRECISIÓN COMPLETA en USD y se redondea a 2 decimales SOLO al
        // pasar a la moneda mostrada (usdToDisplay, HALF_UP). Así un monto exacto de origen sale exacto
        // (30 CNY → ¥30.00). El TOTAL = suma de los componentes YA redondeados en la moneda mostrada, de
        // modo que el desglose SIEMPRE cuadra (base+IVA+envío+recargo = total) en cualquier divisa.
        BigDecimal displayBase = currencyService.usdToDisplay(baseUsd);
        BigDecimal displayIva = currencyService.usdToDisplay(margenInternoUsd);
        BigDecimal displayShip = currencyService.usdToDisplay(shippingUsd);
        BigDecimal displaySurcharge = currencyService.usdToDisplay(surchargeUsd);
        BigDecimal displayShippingUser = currencyService.usdToDisplay(shippingUserUsd);
        BigDecimal displayDutyUser = currencyService.usdToDisplay(dutyUserUsd);
        // Cobro canónico en dólares: base, IVA, envío y recargo redondeados al céntimo y sumados. Es la
        // cifra que guarda el pedido y con la que se cobra.
        BigDecimal retailUsd = baseUsd == null
                ? null
                : baseUsd.setScale(2, RoundingMode.HALF_UP).add(margenInternoUsd.setScale(2, RoundingMode.HALF_UP))
                        .add(shippingUsd.setScale(2, RoundingMode.HALF_UP))
                        .add(surchargeUsd.setScale(2, RoundingMode.HALF_UP))
                        .add(shippingUserUsd.setScale(2, RoundingMode.HALF_UP))
                        .add(dutyUserUsd.setScale(2, RoundingMode.HALF_UP));
        // Y el precio que se ENSEÑA es ese mismo importe convertido, no la suma de los tres componentes
        // convertidos por separado. Parece equivalente y no lo es: componer en euros y componer en
        // dólares dan resultados que difieren en un céntimo, y con eso el escaparate anunciaba 14,79 €
        // mientras el pedido se cobraba a 14,78 €. Derivándolo del canónico, lo que se enseña ES lo que
        // se cobra, en cualquier moneda y sin más cuentas de por medio.
        BigDecimal displayTotal = currencyService.usdToDisplay(retailUsd);
        // Y aquí se cierra el círculo: el desglose tiene que SUMAR ese total, o el administrador ve seis
        // líneas que no dan la cifra de abajo y deja de fiarse de las seis.
        //
        // No se pueden tener las tres cosas a la vez —total igual a lo que se cobra, cada línea redondeada
        // al céntimo, y las líneas sumando el total—: convertir y redondear seis importes por separado
        // deja un residuo de céntimos que no está en ninguna parte. Sumar sin redondear y redondear al
        // final tampoco vale: entonces el total no coincide con las líneas que se enseñan, que es
        // exactamente el problema que se quería evitar.
        //
        // Así que se redondea cada línea y el residuo lo absorbe la BASE, que es la partida mayor: un
        // céntimo sobre el precio del producto no se nota, mientras que colocarlo en el recargo o en una
        // bolsa a cero haría aparecer un «0,01 €» donde el administrador ha escrito 0.
        BigDecimal ivaR = redondea(displayIva);
        BigDecimal shipR = redondea(displayShip);
        BigDecimal surchargeR = redondea(displaySurcharge);
        BigDecimal shippingUserR = redondea(displayShippingUser);
        BigDecimal dutyUserR = redondea(displayDutyUser);
        BigDecimal baseR = displayTotal == null
                ? displayBase
                : displayTotal.setScale(2, RoundingMode.HALF_UP).subtract(ivaR).subtract(shipR).subtract(surchargeR)
                        .subtract(shippingUserR).subtract(dutyUserR);
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
        //
        // Y SEGUNDA REGLA ESTRICTA (25-sep-2026): tampoco se rebaja un precio de MAYOREO. El escalón por
        // cantidad ya es el descuento que concede el proveedor por volumen; encadenarle encima una
        // campaña descuenta dos veces sobre el margen más estrecho del catálogo. Ver esMayoreo().
        PromotionService.Discounted deal = aplicaRebaja && PricingChannelHolder.get() == PriceRuleChannel.STOREFRONT
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
        String baseFormatted = currencyService.formatDisplay(baseR, displayCode);
        String margenInternoFormatted = currencyService.formatDisplay(ivaR, displayCode);
        String shippingFormatted = currencyService.formatDisplay(shipR, displayCode);
        String surchargeFormatted = currencyService.formatDisplay(surchargeR, displayCode);
        String shippingUserFormatted = currencyService.formatDisplay(shippingUserR, displayCode);
        String dutyUserFormatted = currencyService.formatDisplay(dutyUserR, displayCode);
        return new PricedAmount(costUsd, retailUsd, displayTotal, displayCode, currencyService.symbolOf(displayCode),
                displayFormatted, withMargin.appliedRule() != null ? withMargin.appliedRule().getId() : null,
                withMargin.appliedPercentage(), baseUsd, margenInternoUsd, shippingUsd, baseFormatted, margenInternoFormatted,
                shippingFormatted, surchargeUsd, surchargeFormatted, shippingUserFormatted, dutyUserFormatted,
                originalFormatted, discountPercent, promotionName, originalRetailUsd, supplierShippingUsd);
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
    /**
     * Un precio que ya se resolvió antes y solo hay que enseñar.
     *
     * <p>Lo usa el historial de visitas: el importe se calculó cuando la persona abrió la ficha y se guardó
     * entonces, así que al listar no se vuelve a pasar por la conversión de divisa, el margen, el IVA, el
     * envío, las bolsas de subvención y el recargo. Solo se rellena lo que se pinta; el desglose para el
     * administrador no se guarda porque el historial no lo enseña.
     */
    public PricedAmount precioYaVisto(BigDecimal importe, String moneda, String formateado) {
        String codigo = moneda != null ? moneda : CurrencyHolder.get();
        return new PricedAmount(null, null, importe, codigo, currencyService.symbolOf(codigo), formateado, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private PricedAmount unpriced() {
        String displayCode = CurrencyHolder.get();
        return new PricedAmount(null, null, null, displayCode, currencyService.symbolOf(displayCode), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Un importe de la moneda mostrada al céntimo. Nulo cuenta como cero: no hay línea que enseñar. */
    private static BigDecimal redondea(BigDecimal importe) {
        return importe == null ? BigDecimal.ZERO.setScale(2) : importe.setScale(2, RoundingMode.HALF_UP);
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
                    .min(Comparator.comparing(ProductVariantEntity::getPrice)).orElse(null);
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
            // Desglose (solo informativo, para el admin): base con margen + IVA + envío + recargo = total.
            BigDecimal baseRetailUsd, BigDecimal margenInternoUsd, BigDecimal shippingUsd, String baseFormatted,
            String margenInternoFormatted, String shippingFormatted,
            /**
             * Recargo fijo por producto (surcharge_cny, 30-ago-2026). Va en USD ya convertido y con su
             * versión formateada para el admin. NO lleva margen: lo fija el admin por producto/categoría/
             * masivamente y se suma tal cual al precio de venta, como un cargo directo.
             */
            BigDecimal surchargeUsd, String surchargeFormatted,
            /**
             * Las dos bolsas de subvención, ya en la moneda que se enseña. Se editan en CNY —es lo que
             * teclea el admin— pero se muestran convertidas, igual que el recargo: un importe en yuanes
             * junto a euros no se puede comparar de un vistazo.
             */
            String shippingUserFormatted, String dutyUserFormatted,
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
             * al almacén. El cliente paga ese mismo porte con margen encima ({@code shippingUsd}).
             *
             * <p>Lo consume el escaparate para desglosar el porte. Antes servía además para calcular la
             * subvención por porte repetido; esa regla se retiró el 1-sep-2026, cuando el envío y el
             * arancel pasaron a salir de dos importes que el admin asigna al producto.
             */
            BigDecimal supplierShippingUsd) {

        /**
         * Precio sin promoción.
         *
         * <p>La mayoría de los usos —y de las pruebas— no se ocupan de rebajas, y obligarles a pasar
         * tres nulos solo añade ruido a cada llamada.
         */
        public PricedAmount(BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayAmount, String displayCurrency,
                String displaySymbol, String displayFormatted, UUID appliedRuleId, BigDecimal appliedMarginPercent,
                BigDecimal baseRetailUsd, BigDecimal margenInternoUsd, BigDecimal shippingUsd, String baseFormatted,
                String margenInternoFormatted, String shippingFormatted) {
            // Sin promoción ni recargo: los constructores heredados (tests/llamadas previas) no los usan.
            this(costUsd, retailUsd, displayAmount, displayCurrency, displaySymbol, displayFormatted, appliedRuleId,
                    appliedMarginPercent, baseRetailUsd, margenInternoUsd, shippingUsd, baseFormatted, margenInternoFormatted,
                    shippingFormatted, null, null, null, null, null, null, null, null, shippingUsd);
        }

        /** ¿Este precio lleva rebaja? Lo pregunta el frontend para tachar el precio anterior. */
        public boolean discounted() {
            return discountPercent != null && discountPercent > 0;
        }
    }
}
