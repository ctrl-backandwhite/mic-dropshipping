package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * Una línea ya cotizada, tal como se enseña en el resumen del checkout.
     *
     * <p>Lleva las DOS cifras a propósito: el unitario, que es el precio que el cliente eligió y
     * reconoce, y el importe de la línea, que es lo que de verdad entra en el subtotal. Desde que el
     * importe se calcula multiplicando en dólares y convirtiendo al final (ver {@link OrderAmounts}), el
     * unitario mostrado por la cantidad ya no tiene por qué dar el importe de la línea: 0,14 € la unidad
     * por 100 unidades no son 14,00 € sino 13,80 €. Publicar sólo el unitario dejaría al cliente con unas
     * cuentas que no le cuadran; publicando los dos, sumar lo que se ve da el total que se paga.
     */
    public record PreviewLine(UUID productId, UUID variantId, int quantity, BigDecimal unitDisplay,
            String unitFormatted, BigDecimal lineSubtotalDisplay, String lineSubtotalFormatted) {
    }

    /** Desglose ya resuelto, en céntimos USD canónicos y en la divisa que ve el comprador. */
    public record Preview(ShippingQuote quote, int subtotalUsdCents, int discountUsdCents, int shippingUsdCents,
            int taxUsdCents, int taxRateBps, BigDecimal subtotalDisplay, BigDecimal discountDisplay,
            BigDecimal shippingDisplay, BigDecimal taxDisplay, BigDecimal totalDisplay,
            CheckoutTotalsService.CheckoutTotals totals,
            /** Cupón aplicado, o el motivo por el que no vale. Nulo cuando no se ha metido ninguno. */
            String couponCode, String couponError, UUID couponId,
            /** Las líneas con su importe, para que el resumen que se pinta sume el subtotal. */
            List<PreviewLine> lines,
            /**
             * La forma de envío con la que está calculado ESTE desglose: la que eligió el cliente si
             * sigue cotizando, y si no la más barata. Se publica para que el checkout marque la que de
             * verdad se está cobrando en vez de dejar señalada una que ya no vale. Nula cuando la
             * tarifa sale de la tabla de zonas: ahí no hay entre qué elegir.
             */
            ShippingOption shippingOption) {

        /** Sin cupón: el resto del sistema no tiene por qué pasar tres nulos. */
        public Preview(ShippingQuote quote, int subtotalUsdCents, int discountUsdCents, int shippingUsdCents,
                int taxUsdCents, int taxRateBps, BigDecimal subtotalDisplay, BigDecimal discountDisplay,
                BigDecimal shippingDisplay, BigDecimal taxDisplay, BigDecimal totalDisplay,
                CheckoutTotalsService.CheckoutTotals totals) {
            this(quote, subtotalUsdCents, discountUsdCents, shippingUsdCents, taxUsdCents, taxRateBps, subtotalDisplay,
                    discountDisplay, shippingDisplay, taxDisplay, totalDisplay, totals, null, null, null, List.of(),
                    null);
        }
    }

    private final ShippingQuoteService shippingQuoteService;
    private final CheckoutTotalsService checkoutTotalsService;
    private final ProductSubsidyService productSubsidyService;
    private final PricingService pricingService;
    private final CurrencyRateService currencyService;
    private final ProductRepository productRepository;
    /** Los tramos por cantidad: la vista previa tiene que anunciar el precio que se va a cobrar. */
    private final ProductPriceTierRepository priceTierRepository;
    private final CustomsDutyLinesService customsDutyLinesService;
    private final AffiliateProgramService affiliateProgramService;
    private final PromotionService promotionService;
    /** La cuenta del pedido: el importe de línea lo decide ELLA, no esta clase (ver {@link OrderAmounts}). */
    private final OrderAmounts orderAmounts;
    /** De dónde sale la descripción con la que se declarará cada línea: el grupo aprobado, o el título. */
    private final CustomsDeclarationGroupService declarationGroups;

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
        return compute(country, region, items, userId, couponCode, null);
    }

    /**
     * Vista previa del checkout con la forma de envío que el cliente ha elegido.
     *
     * <p>El envío entra en la base del impuesto, así que el desglose entero depende de qué canal se
     * aplique: se resuelve aquí y no restando en el navegador. El código llega del cliente y se
     * revalida contra la cotización recién hecha, con el MISMO criterio que el cobro
     * ({@link ShippingOptionResolver}): un canal que ya no cotiza se ignora y se cae a la más barata,
     * en vez de tumbar la compra por algo que cambió mientras el cliente rellenaba la dirección.
     *
     * @param shippingOptionCode canal elegido en el checkout; nulo o desconocido = el más barato
     */
    public Preview compute(String country, String region, List<Line> items, UUID userId, String couponCode,
            String shippingOptionCode) {
        List<Line> lines = items == null ? List.of() : items;
        ShippingQuote quote = shippingQuoteService.quote(country, lines.stream()
                .map(i -> new ShippingQuoteService.Line(i.productId(), i.variantId(), i.quantity())).toList());

        // Subtotal en CÉNTIMOS USD (canónico, para el descuento y la base del IVA), y subtotal en la
        // MONEDA MOSTRADA como SUMA DE LOS IMPORTES DE LÍNEA, cada uno calculado por OrderAmounts —el
        // mismo servicio que usa el cobro real, el carrito (/cart-quote), la ficha del pedido y la
        // factura—. Con la cuenta escrita en un solo sitio, la vista previa y el cargo no pueden
        // separarse ni un céntimo.
        String displayCode = CurrencyHolder.get();
        int subtotalUsdCents = 0;
        // Subtotal SIN rebajas: la referencia contra la que se mide el cupón.
        int grossSubtotalUsdCents = 0;
        // Gross por producto: base para acotar un cupón PRODUCT/CATEGORY solo a las líneas que alcanza
        // (igual que en el cobro real, para que preview y cargo coincidan).
        Map<UUID, Integer> grossByProduct = new HashMap<>();
        BigDecimal subDispAcc = BigDecimal.ZERO;
        // Unidades de cada producto en TODA la compra, para resolver el tramo por cantidad igual que lo
        // resuelven la cesta y el cobro. Contar por línea daría un precio distinto en cada pantalla.
        Map<UUID, Integer> unidadesPorProducto = new HashMap<>();
        for (Line it : lines) {
            if (it != null && it.productId() != null) {
                unidadesPorProducto.merge(it.productId(), Math.clamp(it.quantity(), 1, MAX_LINE_QUANTITY),
                        Integer::sum);
            }
        }
        // Las escaleras de todos los productos de golpe: una consulta en vez de una por línea.
        Map<UUID, List<ProductPriceTierEntity>> escaleras = unidadesPorProducto.isEmpty()
                ? Map.of()
                : priceTierRepository.findByProductIdInOrderByMinQtyAsc(unidadesPorProducto.keySet()).stream()
                        .collect(Collectors.groupingBy(x -> x.getProduct().getId()));
        List<PreviewLine> previewLines = new ArrayList<>();
        for (Line it : lines) {
            Integer unitCents = unitPriceUsdCents(it, unidadesPorProducto, escaleras);
            if (unitCents == null) {
                continue;
            }
            int qty = Math.clamp(it.quantity(), 1, MAX_LINE_QUANTITY);
            subtotalUsdCents = Math.addExact(subtotalUsdCents, Math.multiplyExact(unitCents, qty));
            Integer originalCents = unitOriginalUsdCents(it, unidadesPorProducto, escaleras);
            int lineGross = Math.multiplyExact(originalCents != null ? originalCents : unitCents, qty);
            grossSubtotalUsdCents = Math.addExact(grossSubtotalUsdCents, lineGross);
            if (it.productId() != null) {
                grossByProduct.merge(it.productId(), lineGross, Integer::sum);
            }
            // REGLA (14-ago-2026): el importe de la línea se obtiene multiplicando en DÓLARES y
            // convirtiendo al final, no multiplicando el unitario ya redondeado. Antes se hacía al revés
            // —se sumaba displayAmount × cantidad— para que el resumen del checkout cuadrase con lo que
            // el carrito enseñaba unidad a unidad; el problema es que el redondeo del unitario se
            // multiplicaba con él: 0,15 $ al cambio 0,92 son 0,138 €, en pantalla 0,14 €, y por 100
            // unidades salían 14,00 € en vez de los 13,80 € que valen 15,00 $. Un 1,45 % de más, cobrado
            // de verdad por la pasarela.
            //
            // El riesgo que motivaba el diseño anterior sigue siendo real: si el cliente multiplica el
            // unitario que ve, no le da el subtotal. Por eso el importe de línea deja de ser un cálculo
            // implícito y se PUBLICA junto al unitario (PreviewLine): el resumen enseña «0,14 € /ud ·
            // 13,80 €» y sumando lo que se ve se llega exactamente al total que se cobra.
            BigDecimal lineSubtotal = orderAmounts.lineSubtotal((long) unitCents, qty, displayCode);
            subDispAcc = subDispAcc.add(lineSubtotal);
            // El unitario que se PINTA sigue saliendo del precio de la ficha (displayAmount): es el que el
            // cliente ha visto en el catálogo y en el carrito, y cambiarlo aquí sería enseñarle otro.
            BigDecimal unitDisplay = unitPriceDisplay(it, unidadesPorProducto, escaleras);
            previewLines.add(new PreviewLine(it.productId(), it.variantId(), qty, unitDisplay,
                    currencyService.formatDisplay(unitDisplay, displayCode), lineSubtotal,
                    currencyService.formatDisplay(lineSubtotal, displayCode)));
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
            PromotionService.CouponCheck check = promotionService.checkCoupon(couponCode, userId, subtotalUsdCents);
            if (!check.valid()) {
                couponError = check.reason();
            } else {
                // Lo que la promoción del producto YA está descontando. Sin medirlo, el cupón se
                // aplicaría encima del precio rebajado y los dos se acumularían.
                int alreadyOff = Math.max(0, grossSubtotalUsdCents - subtotalUsdCents);
                // ALCANCE del cupón: solo descuenta sobre las líneas que alcanza (todo si es global).
                int base = promotionService.reachableGrossCents(check.promotion(), grossByProduct);
                int couponCents = couponDiscountCents(check.promotion(), base);
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

        // La forma de envío que se está cotizando: la elegida si sigue viva, si no la más barata. Su
        // tarifa manda sobre la de la cotización, que es siempre la de la opción más barata.
        ShippingOption shippingOption = ShippingOptionResolver.resolve(quote, shippingOptionCode);
        int shippingBaseUsdCents = quote.supported()
                ? (shippingOption != null ? shippingOption.amountUsdCents() : quote.amountUsdCents())
                : 0;
        // Bultos con sus partidas arancelarias: el derecho fijo de la UE se cobra por línea de declaración
        // dentro de cada bulto, no por producto ni por unidad (ver CustomsDutyLinesService).
        // Con el canal que se está cotizando: el peso máximo por bulto lo fija el par (canal, país), así
        // que la vista previa tiene que repartir igual que después el despacho o el derecho por partida
        // mostrado y el liquidado contarían bultos distintos.
        List<CustomsDutyLinesService.DutyParcel> parcels = customsDutyLinesService.parcelsOf(
                customsLines(items, country), shippingOption != null ? shippingOption.code() : null, country);
        // Impuesto + despacho aduanero por el MISMO servicio que usa el cobro (CheckoutTotalsService), para
        // que el desglose mostrado coincida al céntimo con el pedido.
        ProductSubsidyService.Bags bolsas = productSubsidyService.bagsFor(productosDeLasLineas(items));
        CheckoutTotalsService.CheckoutTotals totals = checkoutTotalsService.compute(country, region,
                discountedSubtotalUsdCents, shippingBaseUsdCents, parcels,
                new CheckoutTotalsService.Subsidy(bolsas.shippingCents(), bolsas.dutyCents()));

        // Importes en la moneda activa: cada componente convertido y REDONDEADO a 2 decimales; el total
        // es la SUMA de esos componentes redondeados (igual que el detalle del pedido), para que el
        // desglose cuadre exactamente en pantalla (subtotal − descuento + envío + IVA = total). El
        // subtotal ya viene sumado de importes de línea redondeados, así que este setScale sólo fija la
        // escala; no vuelve a redondear nada.
        BigDecimal subDisp = subDispAcc.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discDisp = currencyService.usdToDisplay(usd(discountUsdCents)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shipDisp = currencyService.usdToDisplay(usd(totals.shippingCents())).setScale(2,
                RoundingMode.HALF_UP);
        BigDecimal taxDisp = currencyService.usdToDisplay(usd(totals.taxCents())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDisp = subDisp.subtract(discDisp).add(shipDisp).add(taxDisp);

        return new Preview(quote, subtotalUsdCents, discountUsdCents, totals.shippingCents(), totals.taxCents(),
                totals.taxRateBps(), subDisp, discDisp, shipDisp, taxDisp, totalDisp, totals,
                couponId != null ? couponCode.trim().toUpperCase(Locale.ROOT) : null, couponError, couponId,
                List.copyOf(previewLines), shippingOption);
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
    private Integer unitOriginalUsdCents(Line it, Map<UUID, Integer> unidadesPorProducto,
            Map<UUID, List<ProductPriceTierEntity>> escaleras) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null
                ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        PricingService.PricedAmount priced = conTramo(p, v, it, unidadesPorProducto, escaleras);
        BigDecimal original = priced.originalRetailUsd() != null ? priced.originalRetailUsd() : priced.retailUsd();
        return original == null ? null : original.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }

    /**
     * Los productos de las líneas, para que la bolsa los cuente UNA VEZ CADA UNO.
     *
     * <p>Se devuelven los productos y no unas líneas con cantidad porque la cantidad ya no interviene:
     * la subvención la asigna el admin por producto y el proveedor manda un solo bulto tenga el cliente
     * una unidad o cinco.
     */
    private List<ProductEntity> productosDeLasLineas(List<Line> items) {
        List<ProductEntity> out = new ArrayList<>();
        for (Line it : items) {
            if (it == null || it.productId() == null) {
                continue;
            }
            ProductEntity p = productRepository.findById(it.productId()).orElse(null);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** Un importe en dólares a céntimos, con el redondeo de siempre. Nulo cuenta como cero. */
    private static int centimos(BigDecimal usd) {
        return usd == null ? 0 : usd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }

    private Integer unitPriceUsdCents(Line it, Map<UUID, Integer> unidadesPorProducto,
            Map<UUID, List<ProductPriceTierEntity>> escaleras) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null
                ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        BigDecimal retail = conTramo(p, v, it, unidadesPorProducto, escaleras).retailUsd();
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
    private BigDecimal unitPriceDisplay(Line it, Map<UUID, Integer> unidadesPorProducto,
            Map<UUID, List<ProductPriceTierEntity>> escaleras) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null
                ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        return conTramo(p, v, it, unidadesPorProducto, escaleras).displayAmount();
    }

    /**
     * El precio con el tramo por cantidad que le toca a este producto en esta compra.
     *
     * <p>Los tres ayudantes de arriba lo piden por aquí para que la vista previa tarifique EXACTAMENTE
     * igual que la cesta y que el cobro. Cuando cada pantalla resolvía el tramo por su cuenta —o no lo
     * resolvía— el resumen del checkout anunciaba un total y la pasarela cobraba otro.
     */
    private PricingService.PricedAmount conTramo(ProductEntity p, ProductVariantEntity v, Line it,
            Map<UUID, Integer> unidadesPorProducto, Map<UUID, List<ProductPriceTierEntity>> escaleras) {
        int unidades = unidadesPorProducto.getOrDefault(p.getId(), Math.clamp(it.quantity(), 1, MAX_LINE_QUANTITY));
        return pricingService.priceFor(p, v, unidades, escaleras.getOrDefault(p.getId(), List.of()));
    }

    private static BigDecimal usd(int cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }

    /**
     * Traduce las líneas del carrito a lo que necesita el cálculo aduanero: clasificación arancelaria para
     * agrupar, y peso/medidas para repartir la mercancía en bultos igual que hará el transportista.
     */
    List<CustomsDutyLinesService.Line> customsLines(List<Line> items, String country) {
        List<CustomsDutyLinesService.Line> out = new ArrayList<>();
        // El valor que se DECLARA en aduana es el que se cobra, así que lleva el tramo por cantidad
        // igual que el resto: declarar el precio de una unidad en un pedido de mil sobrevalora la
        // mercancía y hace pagar de más en el derecho de aduana.
        Map<UUID, Integer> unidadesPorProducto = new HashMap<>();
        for (Line it : items) {
            if (it != null && it.productId() != null) {
                unidadesPorProducto.merge(it.productId(), Math.clamp(it.quantity(), 1, MAX_LINE_QUANTITY),
                        Integer::sum);
            }
        }
        for (Line it : items) {
            if (it == null || it.productId() == null) {
                continue;
            }
            ProductEntity p = productRepository.findById(it.productId()).orElse(null);
            if (p == null) {
                continue;
            }
            ProductVariantEntity v = it.variantId() == null
                    ? null
                    : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
            Integer unit = unitPriceUsdCents(it, unidadesPorProducto,
                    unidadesPorProducto.isEmpty()
                            ? Map.of()
                            : priceTierRepository.findByProductIdInOrderByMinQtyAsc(unidadesPorProducto.keySet())
                                    .stream().collect(Collectors.groupingBy(x -> x.getProduct().getId())));
            out.add(new CustomsDutyLinesService.Line(p.getId(), p.getHsCode(),
                    declarationGroups.describeFor(p, country), p.getCountryOfOrigin(), Math.max(1, it.quantity()),
                    unit == null ? 0 : unit, ParcelAggregator.unitWeightGrams(p, v), dimension(p, v, 0),
                    dimension(p, v, 1), dimension(p, v, 2), ParcelAggregator.hasBattery(p)));
        }
        return out;
    }

    /** Medida del bulto (0=largo, 1=ancho, 2=alto): manda el producto y, si no la tiene, la variante. */
    private static int dimension(ProductEntity p, ProductVariantEntity v, int which) {
        Integer fromProduct = switch (which) {
            case 0 -> p.getLengthMm();
            case 1 -> p.getWidthMm();
            default -> p.getHeightMm();
        };
        if (fromProduct != null && fromProduct > 0) {
            return fromProduct;
        }
        Integer fromVariant = v == null ? null : switch (which) {
            case 0 -> v.getLengthMm();
            case 1 -> v.getWidthMm();
            default -> v.getHeightMm();
        };
        return fromVariant != null && fromVariant > 0 ? fromVariant : 0;
    }

}
