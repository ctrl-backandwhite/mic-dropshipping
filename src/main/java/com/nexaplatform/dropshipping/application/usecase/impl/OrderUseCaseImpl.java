package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ErrorCode;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CustomsDataCheck;
import com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.ParcelAggregator;
import com.nexaplatform.dropshipping.application.service.PostalCodeCheck;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.application.service.ProductSubsidyService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.application.service.RefundPolicy;
import com.nexaplatform.dropshipping.application.service.ShippingOptionResolver;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.Texts;
import com.nexaplatform.dropshipping.application.service.UnserviceableZoneService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Customer-order use case. Operates on the {@link Order} domain model and delegates
 * persistence to the domain port. Cross-aggregate read enrichment (customer email,
 * shop name/handle, supplier name) is filled here and carried on the model, mirroring
 * {@code Category.productCount}. Logic moved verbatim out of the legacy
 * {@code OrderService}, preserving the checkout / lifecycle / notification semantics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderUseCaseImpl implements OrderUseCase {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String ORDER_NOT_FOUND = "Order not found";
    /** Recurso del 404 corto que devuelven el checkout y el detalle del cliente. */
    private static final String ORDER_RESOURCE = "Order";
    private static final String WALLET = "WALLET";

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Cota superior por línea de pedido. Blinda el cálculo del importe frente al desbordamiento de
     * enteros (todo el pipeline de céntimos es {@code int}): sin esta cota, una cantidad enorme hacía
     * que {@code unitCents * quantity} desbordara a un positivo pequeño y la orden se cobraba por una
     * fracción de su valor real. Se valida a nivel de dominio (cubre checkout, admin y partner) además
     * de en el DTO. 100.000 uds/línea es holgado para cualquier pedido legítimo.
     */
    private static final int MAX_LINE_QUANTITY = 100_000;

    private final OrderRepository orderRepository;
    // Repo JPA de la entidad (mismo nombre simple que el puerto de dominio → FQN): se usa
    // solo para la idempotencia a nivel de orden (buscar reutilizable + sellar el idem).
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository orderEntityRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository userRepository;
    private final ShopConnectionRepository shopConnectionRepository;
    private final UserAddressRepository userAddressRepository;
    private final WebhookDispatcherService webhooks;
    private final WalletUseCase walletUseCase;
    private final NotificationsPublisher notificationsPublisher;
    /** El buzón de la aplicación; el publicador de arriba solo saca el evento al bus. */
    private final NotificationUseCase notificationUseCase;
    private final PricingService pricingService;
    /** Los tramos por cantidad, para cobrar el precio que la ficha promete a partir de N unidades. */
    private final ProductPriceTierRepository priceTierRepository;
    private final AffiliateProgramService affiliateProgramService;
    private final StockService stockService;
    private final PaymentUseCase paymentUseCase;
    private final OrderEmailService orderEmailService;
    private final FulfillmentProvider fulfillment;
    /**
     * Cotiza preguntando a TODOS los transportistas que puedan llevar el pedido. El proveedor de arriba
     * se queda para lo que todavía va por uno solo; la cotización ya no puede ir por ahí.
     */
    private final FulfillmentRouter router;
    private final CheckoutTotalsService checkoutTotalsService;
    private final ProductSubsidyService productSubsidyService;
    private final CustomsDutyLinesService customsDutyLinesService;
    private final UnserviceableZoneService unserviceableZoneService;
    private final OperatorCommissionService operatorCommissionService;
    private final PromotionService promotionService;
    private final SupplierPurchaseService supplierPurchaseService;
    /** De dónde sale la descripción con la que se declara cada línea: el grupo aprobado, o el título. */
    private final CustomsDeclarationGroupService declarationGroups;
    /** Timeline del pedido: los pasos que marca una persona también tienen que verse ahí. */
    private final OrderTrackingEventRepository trackingRepository;
    private final OrderIndexer orderIndexer;
    private final OrderSearchService orderSearchService;
    /** La cesta sincronizada: lo comprado sale de ella en cuanto el pedido queda PAGADO. */
    private final CartService cartService;

    @Value("${nexadrop.demo.orders-enabled:false}")
    private boolean demoOrdersEnabled;

    @Override
    @Transactional
    public Order createOrder(UUID partnerAppId, UUID userId, CreateOrderRequest req) {
        return newOrder(partnerAppId, userId, req);
    }

    /**
     * Alta del pedido. El cuerpo vive aquí, sin anotación, porque los demás flujos de alta (manual, partner,
     * demo, checkout) lo invocan dentro de su propia transacción: llamando al método público desde dentro de
     * la clase el proxy de Spring no interviene y su {@code @Transactional} sería una promesa vacía.
     */
    private Order newOrder(UUID partnerAppId, UUID userId, CreateOrderRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("CART_EMPTY", "Order must have at least one item");
        }

        // Origen de la orden: si la petición viene por una integración (Shopify/WooCommerce/API de partners)
        // el filtro de canal marcó INTEGRATION; el checkout propio de la web/app queda STOREFRONT → PLATFORM.
        // Determina la comisión del operador (10% propias / 5% integradas).
        String orderSource = PricingChannelHolder.get() == PriceRuleChannel.INTEGRATION ? "INTEGRATION" : "PLATFORM";
        Order order = Order.builder().orderNumber(generateOrderNumber()).partnerAppId(partnerAppId).userId(userId)
                .source(orderSource).externalOrderId(req.externalOrderId()).status(OrderStatus.PENDING).currency("USD")
                .notes(req.notes()).placedAt(Instant.now()).items(new ArrayList<>()).build();

        applyAddress(order, req.shippingAddress(), false);
        if (req.billingAddress() != null) {
            applyAddress(order, req.billingAddress(), true);
        }

        // Idioma del pedido = idioma del usuario (para snapshotear el título del producto en su idioma, no en
        // chino). Así la factura/email salen en un único idioma coherente. Sin usuario → español por defecto.
        String orderLang = userId != null
                ? userRepository.findById(userId).map(u -> u.getLanguage()).filter(l -> l != null && !l.isBlank())
                        .orElse("es")
                : "es";

        int subtotal = 0;
        ParcelAggregator parcel = new ParcelAggregator();
        GrossSubtotal gross = new GrossSubtotal();
        // Gross (precio de tarifa) acumulado POR PRODUCTO: base para acotar un cupón con alcance
        // PRODUCT/CATEGORY solo a las líneas que alcanza (ver applyTotals).
        Map<UUID, Integer> grossByProduct = new HashMap<>();
        UUID cuponAplicado = null;
        // EL MARGEN VA POR EL PAÍS DEL COMPRADOR, NO POR EL DEL DESTINO. NO LO CAMBIES SIN LEER ESTO.
        //
        // Regla de negocio (decisión del dueño, 18-ago-2026): quien se registra en México ve y paga
        // precio de México aunque envíe el paquete a España; para pagar el precio español hay que
        // registrarse en España. El país del comprador es el que trae la cabecera `X-Country` que pone
        // el front, y ya está en PricingCountryHolder cuando se llega aquí (PricingCountryFilter), que
        // es exactamente el mismo que usan la ficha del catálogo y la vista previa del checkout. Por eso
        // aquí NO se toca: preciar el pedido con otro país es lo que hacía que se enseñara un total y se
        // cobrara otro (23,19 $ enseñados contra 22,66 $ cobrados, visto certificando en local).
        //
        // Antes esto pisaba el holder con `order.getShippingCountry()`. Se hizo por seguridad, y el
        // riesgo que describía es REAL y sigue vivo: `X-Country` la manda el navegador, así que un
        // comprador puede declarar el país de menor margen y pagar de menos. Se acepta a propósito,
        // porque la alternativa —cobrar por el destino— rompe la regla de negocio de arriba y deja al
        // cliente pagando algo distinto de lo que se le enseñó, que es peor. Si algún día pesa más el
        // abuso que la coherencia, la solución NO es volver al país de envío: es leer el país de
        // registro de `users.country` (dato de la base, no manipulable) y usarlo en los TRES sitios
        // —ficha, vista previa y cobro— a la vez.
        //
        // El IVA y el arancel son otra cosa y NO dependen de esto: los fija la aduana del destino y se
        // calculan con `order.getShippingCountry()` explícito, más abajo, en checkoutTotalsService.
        //
        // Lo fija PedidoPaisDelMargenTest; ese test falla si alguien vuelve a pisar el país aquí.
        // Unidades de cada producto en TODO el pedido, para resolver el tramo por cantidad (23-sep-2026).
        // Se cuenta igual que el pedido mínimo —sumando las líneas del mismo producto— porque el lote se
        // compone mezclando variantes: a quien se lleva cien unidades en dos tallas hay que hacerle el
        // mismo precio que a quien se lleva cien de una, que es lo que le cuesta al proveedor.
        Map<UUID, Integer> unidadesPorProducto = new HashMap<>();
        for (OrderItemInput itemReq : req.items()) {
            unidadesPorProducto.merge(itemReq.productId(), Math.max(0, itemReq.quantity()), Integer::sum);
        }
        // Las escaleras de todos los productos de golpe: una consulta en vez de una por línea.
        Map<UUID, List<ProductPriceTierEntity>> escaleras = unidadesPorProducto.isEmpty()
                ? Map.of()
                : priceTierRepository.findByProductIdInOrderByMinQtyAsc(unidadesPorProducto.keySet()).stream()
                        .collect(Collectors.groupingBy(x -> x.getProduct().getId()));
        // Subtotal que admite descuento: el de arriba menos las líneas cobradas a precio de mayoreo. Es
        // la base del descuento de referido, por la misma regla que deja el cupón fuera del mayoreo.
        int subtotalRebajable = 0;
        for (OrderItemInput itemReq : req.items()) {
            OrderItem line = buildLine(itemReq, orderLang, parcel, gross, grossByProduct, order.getShippingCountry(),
                    unidadesPorProducto, escaleras);
            order.getItems().add(line);
            subtotal = Math.addExact(subtotal, line.getLineTotalCents());
            if (!pricingService.esPrecioDeMayoreo(escaleras.getOrDefault(itemReq.productId(), List.of()),
                    unidadesPorProducto.getOrDefault(itemReq.productId(), itemReq.quantity()))) {
                subtotalRebajable = Math.addExact(subtotalRebajable, line.getLineTotalCents());
            }
        }
        requireMinimumOrderQuantities(order.getItems());
        cuponAplicado = applyTotals(order, userId, subtotal, subtotalRebajable, gross.cents(), parcel, req.couponCode(),
                grossByProduct, req.shippingOptionCode());

        Order saved = orderRepository.save(order);
        // El canje se apunta con el pedido ya guardado: si el guardado falla, el cupón no se gasta.
        if (cuponAplicado != null) {
            promotionService.recordUse(cuponAplicado, userId, saved.getId(), saved.getDiscountCents());
        }
        orderIndexer.indexOrder(saved.getId()); // auto-sync del índice al crear la orden
        return saved;
    }

    /**
     * Construye una línea del pedido a partir de lo pedido, congelando precio, coste y textos.
     *
     * <p>Todo lo que se guarda aquí es una FOTO del momento de la compra: si mañana cambia el precio, el
     * título o la imagen del producto, la línea vendida sigue diciendo lo que se vendió.
     */
    /**
     * Suma de las líneas a precio SIN rebajar.
     *
     * <p>Es la referencia contra la que se mide un cupón: si se midiera contra el subtotal ya
     * rebajado, el cupón se aplicaría ENCIMA de la rebaja y los dos descuentos se acumularían.
     */
    private static final class GrossSubtotal {
        private int cents;

        void add(int amount) {
            cents = Math.addExact(cents, amount);
        }

        int cents() {
            return cents;
        }
    }

    private OrderItem buildLine(OrderItemInput itemReq, String orderLang, ParcelAggregator parcel, GrossSubtotal gross,
            Map<UUID, Integer> grossByProduct, String shippingCountry, Map<UUID, Integer> unidadesPorProducto,
            Map<UUID, List<ProductPriceTierEntity>> escaleras) {
        // Cantidad dentro de un rango sano ANTES de calcular importes. Cierra el desbordamiento de
        // enteros del cobro (unitCents * quantity) y rechaza cantidades ≤ 0 aunque el DTO no valide.
        if (itemReq.quantity() <= 0 || itemReq.quantity() > MAX_LINE_QUANTITY) {
            throw new BusinessException("INVALID_QUANTITY",
                    "La cantidad por línea debe estar entre 1 y " + MAX_LINE_QUANTITY);
        }
        ProductEntity product = productRepository.findById(itemReq.productId())
                .orElseThrow(() -> new NotFoundException("Product not found: " + itemReq.productId()));
        // Un producto retirado no se vende, aunque la línea siga en un carrito viejo. El carrito vive en
        // el navegador del cliente: añade hoy, el administrador pausa o archiva mañana —porque el
        // proveedor lo dio de baja, porque se agotó o porque no puede venderse— y el cliente compra la
        // semana que viene. Sin esta comprobación el pedido se aceptaba y se cobraba igual, y el
        // escaparate ni siquiera enseñaba ya el producto.
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessException("PRODUCT_UNAVAILABLE", "«" + orderTitle(product, orderLang)
                    + "» ya no está disponible. Quítalo del carrito" + " para continuar.");
        }
        ProductVariantEntity variant = itemReq.variantId() == null
                ? null
                : variantRepository.findById(itemReq.variantId())
                        // Variante inexistente (carrito obsoleto: el catálogo se re-importó y la variante
                        // cambió de ID). Código específico + variantId en detail para que el checkout
                        // identifique y quite del carrito la línea rota, en vez de un 404 genérico.
                        .orElseThrow(() -> new NotFoundException("CART_ITEM_UNAVAILABLE",
                                List.of(itemReq.variantId().toString())));

        // Dropshipping: NO rechazamos por stock. La plataforma no mantiene inventario propio; el
        // proveedor abastece bajo demanda (stock efectivamente ilimitado), así que un pedido siempre
        // se puede aceptar y el stock mostrado no se agota. El número de stock es solo informativo.

        // DROP-637: charge the PRICED amount (raw supplier price → USD → margin), not the raw
        // CNY value. The order currency is USD, so we bill retailUsd — the same figure the
        // storefront showed — instead of the stored 14.90 CNY mis-billed as $14.90.
        // El precio del TRAMO que corresponde a las unidades de este producto en el pedido. Antes se
        // tarificaba sin mirar la cantidad, así que la tabla de cantidades de la ficha anunciaba una
        // rebaja por volumen que el cobro no aplicaba: se enseñaba «1000+ → 5,14 €» y se cobraban 5,78 €.
        int unidadesDelProducto = unidadesPorProducto.getOrDefault(itemReq.productId(), itemReq.quantity());
        PricedAmount priced = pricingService.priceFor(product, variant, unidadesDelProducto,
                escaleras.getOrDefault(product.getId(), List.of()));
        BigDecimal unitPrice = priced.retailUsd();
        if (unitPrice == null) {
            throw new BusinessException("Product " + product.getSlug() + " has no price");
        }
        // El precio de línea debe COINCIDIR con el precio que ve el usuario en el catálogo/carrito. El
        // catálogo redondea a 2 decimales al céntimo MÁS CERCANO (HALF_UP, ver CurrencyRateService), así
        // que el cobro usa el MISMO redondeo → catálogo == carrito == cobro, sin céntimos de más ni de menos.
        //
        // Este setScale NO es un segundo redondeo: `retailUsd` ya llega con 2 decimales desde
        // PricingService (base + IVA + envío, cada uno redondeado al céntimo). En dólares —la moneda
        // canónica— el precio unitario ES el precio con dos decimales, y multiplicarlo por la cantidad da
        // el importe exacto. Que sea así está fijado a propósito en CheckoutFlowIT: un coste de 10,0050 $
        // vale 10,01 $ la unidad y 7 unidades cuestan 70,07 $, no 70,04 $. El redondeo que sí se
        // multiplicaba —y cobraba de más— era el de la conversión a la moneda del cliente, y ese vive
        // ahora en OrderAmounts.lineSubtotal, que convierte el importe de la línea entero.
        int unitCents = unitPrice.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
        int costCents = priced.costUsd() != null
                ? priced.costUsd().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue()
                : unitCents;
        // DROP: coste en YUAN (CNY) congelado al crear la orden = precio del proveedor (variante o base),
        // SIEMPRE en CNY (los productos se persisten solo en CNY). Base de la comisión del operador (15%).
        BigDecimal cnyUnit = variant != null && variant.getPrice() != null
                ? variant.getPrice()
                : product.getBasePrice();
        long costCnyCents = cnyUnit != null
                ? cnyUnit.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
                : 0L;
        // Importe de la línea EN DÓLARES. Aquí no hay conversión de divisa, así que multiplicar el
        // unitario canónico es exacto por definición (ver la nota de unitCents); el importe en la moneda
        // del cliente se deriva de este número, una sola vez, en OrderAmounts.lineSubtotal.
        //
        // multiplyExact: si el producto se saliera de rango (int), lanza en vez de envolver a un
        // valor pequeño (que se cobraría de menos). Con MAX_LINE_QUANTITY nunca ocurre en la práctica.
        int lineTotal = Math.multiplyExact(unitCents, itemReq.quantity());
        // Precio de tarifa (antes de la rebaja automática). Cuando el producto no está en promoción
        // coincide con el de venta, así que la resta que mide la rebaja da cero.
        BigDecimal sinRebaja = priced.originalRetailUsd() != null ? priced.originalRetailUsd() : unitPrice;
        int lineGross = Math.multiplyExact(
                sinRebaja.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact(), itemReq.quantity());
        gross.add(lineGross);
        // ALCANCE DE LOS DESCUENTOS (regla del titular, 25-sep-2026): una línea que se está cobrando a
        // precio de MAYOREO no entra en el alcance del cupón. El escalón por cantidad ya es el descuento
        // que concede el proveedor por volumen; un cupón encima descuenta dos veces sobre el margen más
        // estrecho del catálogo. Se deja fuera del mapa, no a cero, para que un cupón con alcance
        // PRODUCT/CATEGORY que SOLO alcance líneas de mayoreo no descuente nada en vez de descontar
        // sobre las demás.
        //
        // Al gross total (gross.add) sí suma, y tiene que seguir sumando: ese acumulado sirve para medir
        // cuánto rebajó ya la promoción automática, y una línea de mayoreo aporta lo mismo al bruto que
        // al neto, así que no altera esa medida.
        if (!pricingService.esPrecioDeMayoreo(escaleras.getOrDefault(product.getId(), List.of()),
                unidadesDelProducto)) {
            grossByProduct.merge(product.getId(), lineGross, Integer::sum);
        }

        parcel.add(product, variant, itemReq.quantity());
        return OrderItem.builder().productId(product.getId()).variantId(variant != null ? variant.getId() : null)
                .titleSnapshot(orderTitle(product, orderLang)).imageUrlSnapshot(snapshotImage(product, variant))
                .skuSnapshot(variant != null ? variant.getSku() : null).unitPriceCents(unitCents).costCents(costCents)
                .costCnyCents(costCnyCents).quantity(itemReq.quantity()).lineTotalCents(lineTotal)
                // Se congela AQUÍ la descripción con la que se va a declarar, con el mismo país del
                // arancel que usa checkoutTotalsService. Si el grupo se aprueba después de cobrar, este
                // pedido seguirá contando por lo que se declaró: es lo que impide que la vista previa
                // cuente una línea y el despacho cuente dos.
                .declaredDescription(declarationGroups.describeFor(product, shippingCountry))
                .declaredDescriptionZh(declarationGroups.describeZhFor(product, shippingCountry)).build();
    }

    /**
     * Exige el pedido mínimo de cada producto, sumando TODAS sus líneas.
     *
     * <p>Se cuenta por producto y no por línea a propósito: el lote se compone mezclando variantes —dos
     * colores, o dos tallas—, igual que en 1688, y el proveedor solo mira cuántas unidades van en total.
     * Pedir el mínimo a cada variante obligaría a comprar mucho más de lo que exige el proveedor.
     *
     * <p>Y se comprueba aquí, en el alta del pedido, porque hasta ahora solo lo miraba la ficha: quien
     * llamara a la API directamente —o llegara al pago con un carrito viejo— se saltaba el mínimo, y el
     * pedido acababa en una compra que el proveedor no acepta.
     */
    private void requireMinimumOrderQuantities(List<OrderItem> items) {
        Map<UUID, Integer> qtyByProduct = new HashMap<>();
        for (OrderItem item : items) {
            qtyByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        for (Map.Entry<UUID, Integer> e : qtyByProduct.entrySet()) {
            int moq = productRepository.findById(e.getKey()).map(ProductEntity::getMoq).orElse(1);
            if (moq > 1 && e.getValue() < moq) {
                throw new BusinessException(ErrorCode.MOQ_NOT_REACHED.name(), "El pedido mínimo de este producto es de "
                        + moq + " unidades y solo hay " + e.getValue() + ".");
            }
        }
    }

    /**
     * Imagen que se congela en la línea. Prioriza la de la VARIANTE comprada —el color concreto que se
     * pidió— ya espejada en nuestro almacenamiento, luego la de origen, y sólo si no hay ninguna cae a la
     * primera del producto. Antes eran tres ternarios anidados y no había forma de leer el orden.
     */
    private static String snapshotImage(ProductEntity product, ProductVariantEntity variant) {
        if (variant != null) {
            if (variant.getImageCdnUrl() != null && !variant.getImageCdnUrl().isBlank()) {
                return variant.getImageCdnUrl();
            }
            if (variant.getImageSourceUrl() != null && !variant.getImageSourceUrl().isBlank()) {
                return variant.getImageSourceUrl();
            }
        }
        return product.getImages().isEmpty() ? null : product.getImages().get(0).getSourceUrl();
    }

    /**
     * Cierra los importes del pedido: envío por destino, descuento de referido, impuesto y despacho.
     *
     * <p>Se calculan con los MISMOS servicios que la vista previa del checkout para que lo mostrado
     * coincida al céntimo con lo cobrado.
     */
    private UUID applyTotals(Order order, UUID userId, int subtotal, int subtotalRebajable, int grossSubtotal,
            ParcelAggregator parcel, String couponCode, Map<UUID, Integer> grossByProduct, String shippingOptionCode) {
        // Envío: tarifa por destino del carrier. Si el país no está cubierto, el envío queda en 0 aquí
        // (el checkout del storefront bloquea antes el destino no soportado). El bulto se arma con el
        // MISMO agregador que la vista previa del checkout: peso, medidas del paquete y batería.
        // Se cotiza con el ENRUTADOR, el mismo que usó la vista previa, y no con un transportista
        // suelto. Preguntarle solo a uno tiene una consecuencia que no se ve venir: la opción que el
        // cliente eligió del OTRO no aparecería en esta cotización, el resolutor la daría por inválida y
        // caería a la más barata de quien sí contestó. El cliente elegiría una cosa y se le cobraría y
        // enviaría otra — la misma familia de fallo que el descuadre del 18-ago-2026, por otra puerta.
        ShippingQuote quote = router.cotizar(order.getShippingCountry(), parcel.build(), productosDe(order));
        // La forma de envío que eligió el cliente, revalidada contra lo que cotiza AHORA: el código
        // llega del navegador y aceptarlo sin comprobar dejaría pagar el precio de un canal más barato
        // —o colarse por uno postal, fuera del IVA prepagado—. El importe sale de la cotización.
        ShippingOption chosen = ShippingOptionResolver.resolve(quote, shippingOptionCode);
        if (chosen != null) {
            order.setShippingChannelCode(chosen.code());
            // Sin esto, al despachar habría que adivinar a quién pedirle la guía: los códigos de dos
            // transportistas no se parecen en nada y no hay forma de deducirlo del código.
            order.setShippingCarrier(chosen.carrier());
            // El nombre, además del código: es lo que el transportista exige para emitir la guía.
            order.setShippingChannelName(chosen.name());
        }
        int shippingCents = quote.supported() ? (chosen != null ? chosen.amountUsdCents() : quote.amountUsdCents()) : 0;

        // Descuento de referido para el COMPRADOR: 10% del subtotal de producto si tiene una atribución
        // de afiliado viva (y no es su propio código). El envío y el IVA se calculan sobre (subtotal −
        // descuento).
        // Sobre el subtotal REBAJABLE: las líneas a precio de mayoreo quedan fuera, igual que del cupón.
        int discount = (int) affiliateProgramService.referralDiscountCents(userId, subtotalRebajable);

        // Cupón tecleado en el checkout. Compite con el descuento de referido y se queda el MAYOR: dos
        // descuentos sumados sobre el mismo pedido se comen el margen entero. La rebaja automática del
        // producto no entra en la cuenta porque ya está descontada del precio de cada línea.
        UUID cuponAplicado = null;
        if (couponCode != null && !couponCode.isBlank()) {
            PromotionService.CouponCheck check = promotionService.checkCoupon(couponCode, userId, subtotal);
            if (check.valid()) {
                // Lo que la rebaja automática YA descontó de las líneas. El cupón se mide sobre el precio
                // de tarifa y solo se cobra la DIFERENCIA, igual que en la vista previa del checkout: si
                // los dos números no se calcularan igual, se cobraría un total distinto del enseñado.
                // ALCANCE: un cupón PRODUCT/CATEGORY solo descuenta sobre las líneas que alcanza, no sobre
                // todo el carrito. reachableGrossCents devuelve el gross completo si el cupón es global.
                int base = promotionService.reachableGrossCents(check.promotion(), grossByProduct);
                int cupon = couponDiscountCents(check.promotion(), base);
                int extra = couponExtraDiscountCents(cupon, grossSubtotal, subtotal, discount);
                if (extra > 0) {
                    discount = extra;
                    cuponAplicado = check.promotion().getId();
                }
            }
            // Un cupón inválido no tumba el pedido: el checkout ya avisó en la vista previa y aquí
            // simplemente no descuenta. Rechazar la compra entera por un código caducado sería peor.
        }

        int discountedSubtotal = subtotal - discount;

        // Impuesto + despacho aduanero. Incluye:
        //  · IVA por estado/provincia (US/CA/BR) o tasa nacional, sobre (subtotal − descuento) + envío.
        //  · Recargo del despacho DDP del país (lo que el transportista cobra por adelantar el impuesto).
        //  · Recargo de despacho formal si el valor de los bienes supera el umbral de minimis del destino.
        // Derecho fijo de la UE: se cobra por línea de declaración (partida arancelaria) dentro de cada
        // bulto, no por producto ni por unidad. Ver CustomsDutyLinesService.
        // Las bolsas van por el MISMO servicio que la vista previa: si el checkout enseñara un descuento
        // y el cobro otro, el cliente pagaría distinto de lo que aceptó. Cada una cubre su concepto —una
        // el porte y la otra el arancel—, sin mezclarse.
        ProductSubsidyService.Bags bolsas = productSubsidyService.bagsFor(productosDelPedido(order));
        CheckoutTotalsService.CheckoutTotals totals = checkoutTotalsService.compute(order.getShippingCountry(),
                order.getShippingState(), discountedSubtotal, shippingCents, customsParcelsOf(order),
                new CheckoutTotalsService.Subsidy(bolsas.shippingCents(), bolsas.dutyCents()));
        // Destino cuya política prohíbe vender por encima del umbral: se rechaza ANTES de cobrar, en vez de
        // aceptar un pedido que costaría aranceles y despacho formal no repercutidos.
        if (totals.blocked()) {
            // El importe de la mercancía supera el umbral libre de aranceles del destino; por encima, la
            // línea de e-commerce no despacha y habría aranceles. Se rechaza ANTES de cobrar. El texto que
            // ve el cliente lo localiza el front por el CODE; este es el fallback técnico.
            String limite = totals.customs().deMinimisLabel();
            throw new BusinessException("CUSTOMS_THRESHOLD_EXCEEDED",
                    "El valor de los productos supera el límite de importación de " + order.getShippingCountry()
                            + (limite.isBlank() ? "" : " (" + limite + ")")
                            + ", por encima del cual se aplican aranceles de aduana. Reduce el carrito por"
                            + " debajo de ese importe para completar la compra.");
        }
        order.setSubtotalCents(subtotal);
        order.setDiscountCents(discount);
        order.setShippingCents(totals.shippingCents());
        // El arancel viaja dentro del envío para el cobro, pero se guarda aparte: la factura lo desglosa y
        // tiene que reflejar lo que se cobró ENTONCES, no lo que la tarifa diga al reimprimirla.
        order.setCustomsDutyCents(totals.customsHandlingCents());
        order.setTaxCents(totals.taxCents());
        order.setTotalCents(totals.totalCents(discountedSubtotal));
        return cuponAplicado;
    }

    /**
     * Lo que hay que descontar DE MÁS al aplicar un cupón, o 0 si el cupón no mejora lo que ya había.
     *
     * <p>La regla es «gana el mayor, nunca se suman»: el cupón se mide sobre el precio de tarifa y
     * compite contra la rebaja automática (que ya está descontada de las líneas) y contra el descuento
     * de referido. Si gana, solo se cobra la diferencia respecto a la rebaja ya aplicada.
     *
     * <p>Es el MISMO cálculo que hace la vista previa del checkout; si los dos se separan, se cobra un
     * total distinto del que se enseñó.
     */
    static int couponExtraDiscountCents(int couponCents, int grossSubtotal, int subtotal, int otherDiscount) {
        int yaRebajado = Math.max(0, grossSubtotal - subtotal);
        if (couponCents <= Math.max(yaRebajado, otherDiscount)) {
            return 0;
        }
        return couponCents - yaRebajado;
    }

    /**
     * Lo que descuenta un cupón sobre un subtotal, en céntimos.
     *
     * <p>Nunca más que el propio subtotal: un cupón de importe fijo mayor que el carrito dejaría el
     * pedido en negativo.
     */
    private static int couponDiscountCents(PromotionEntity coupon, int subtotalCents) {
        if (coupon.getPercentOff() != null) {
            return coupon.getPercentOff().multiply(BigDecimal.valueOf(subtotalCents))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValue();
        }
        return coupon.getAmountOffCents() != null ? Math.min(coupon.getAmountOffCents(), subtotalCents) : 0;
    }

    @Override
    @Transactional
    public Order createManualOrder(String customerEmail, CreateOrderRequest req) {
        UUID userId = null;
        if (customerEmail != null && !customerEmail.isBlank()) {
            userId = userRepository.findByEmail(customerEmail.trim()).map(u -> u.getId())
                    .orElseThrow(() -> new NotFoundException("No existe un usuario con email: " + customerEmail));
        }
        Order order = newOrder(null, userId, req);
        log.info("Admin manual order {} created ({} items, customer={})", order.getOrderNumber(), req.items().size(),
                customerEmail != null ? customerEmail : "guest");
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrder(UUID id) {
        return requireOrder(id);
    }

    private Order requireOrder(UUID id) {
        return orderRepository.findById(id).orElseThrow(() -> new NotFoundException("Order not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> listForPartner(UUID partnerAppId) {
        return partnerOrders(partnerAppId);
    }

    private List<Order> partnerOrders(UUID partnerAppId) {
        return orderRepository.findByPartnerAppId(partnerAppId);
    }

    /* ============ Partner (JWT) ============ */

    @Override
    @Transactional
    public Order createOrderForPartner(Jwt jwt, CreateOrderRequest req) {
        return newOrder(resolvePartnerId(jwt), null, req);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> listForPartner(Jwt jwt) {
        return partnerOrders(resolvePartnerId(jwt));
    }

    @Override
    @Transactional(readOnly = true)
    public Order getPartnerOrder(Jwt jwt, UUID id) {
        Order order = requireOrder(id);
        // IDOR: un partner solo puede leer SUS pedidos (partnerAppId == su id derivado del JWT). Los pedidos
        // de otro partner o del escaparate (partnerAppId null) → 404, sin filtrar su existencia.
        UUID partnerId = resolvePartnerId(jwt);
        if (order.getPartnerAppId() == null || !order.getPartnerAppId().equals(partnerId)) {
            throw new NotFoundException("Order not found: " + id);
        }
        return order;
    }

    private UUID resolvePartnerId(Jwt jwt) {
        String sub = jwt.getSubject();
        return UUID.nameUUIDFromBytes(("partner:" + sub).getBytes());
    }

    /* ============ Admin ============ */

    @Override
    @Transactional(readOnly = true)
    public List<Order> listAdminOrders(String status, String q) {
        return adminOrders(status, q);
    }

    private List<Order> adminOrders(String status, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        return orderRepository.findAll().stream()
                .filter(o -> status == null || status.isBlank() || o.getStatus().name().equalsIgnoreCase(status))
                .filter(o -> needle.isEmpty()
                        || (o.getOrderNumber() != null && o.getOrderNumber().toLowerCase().contains(needle))
                        || (o.getExternalOrderId() != null && o.getExternalOrderId().toLowerCase().contains(needle))
                        || (o.getShippingFullName() != null && o.getShippingFullName().toLowerCase().contains(needle)))
                .sorted((a, b) -> {
                    Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
                    Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
                    return bi.compareTo(ai);
                }).map(this::enrich).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage pageAdminOrders(String status, String q, int page, int size) {
        // Primario: el índice de OpenSearch devuelve la página de IDs ya ordenada de reciente a antigua y
        // ya filtrada; solo se enriquece esa página cargándola de la BD, que es lo que mantiene el
        // enriquecido entre agregados fuera de la tabla completa.
        Optional<OrderSearchService.IdPage> idx = orderSearchService.pageIds(status, q, page, size);
        if (idx.isPresent()) {
            List<Order> items = idx.get().ids().stream().map(id -> orderRepository.findById(id).orElse(null))
                    .filter(Objects::nonNull).map(this::enrich).toList();
            return new OrderPage(items, page, size, idx.get().total());
        }
        // Fallback (OpenSearch caído): listado BD filtrado/ordenado + página en memoria (dataset acotado).
        List<Order> all = adminOrders(status, q);
        int from = Math.min(Math.max(0, page) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new OrderPage(all.subList(from, to), page, size, all.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Order getAdminOrderDetail(UUID id, String lang) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        // DROP-632: localise the line titles for the admin's language (the admin detail used
        // to fall through to the raw Chinese snapshot) and consolidate the duplicated
        // base/variant lines into a single resolved-SKU line.
        resolveItemTitles(o, lang);
        consolidateItems(o);
        return enrich(o);
    }

    /**
     * DROP-632: collapses duplicated order lines for the same product+unit price into one
     * line (summing quantity), preferring the line that resolved a real SKU. This removes the
     * "same unit, one with empty SKU and one resolved" artifact that inflated item counts.
     * Operates on the in-memory (read-only) order; never persisted.
     */
    private void consolidateItems(Order o) {
        if (o.getItems() == null || o.getItems().size() < 2) {
            return;
        }
        LinkedHashMap<String, OrderItem> merged = new LinkedHashMap<>();
        for (OrderItem it : o.getItems()) {
            String key = (it.getProductId() != null ? it.getProductId() : it.getId()) + "|" + it.getUnitPriceCents();
            OrderItem prev = merged.get(key);
            if (prev == null) {
                merged.put(key, it);
            } else {
                mergeInto(prev, it);
            }
        }
        if (merged.size() != o.getItems().size()) {
            o.setItems(new ArrayList<>(merged.values()));
        }
    }

    /**
     * Funde dos líneas del mismo producto y mismo precio unitario: suma cantidades y se queda con la más
     * informativa. Que la línea que llega traiga SKU y la anterior no es justo el artefacto que se quiere
     * corregir, así que en ese caso —y solo en ese— pisa también el título congelado.
     */
    private void mergeInto(OrderItem prev, OrderItem it) {
        prev.setQuantity(prev.getQuantity() + it.getQuantity());
        prev.setLineTotalCents(prev.getUnitPriceCents() * prev.getQuantity());
        boolean prevHasSku = prev.getSkuSnapshot() != null && !prev.getSkuSnapshot().isBlank();
        boolean curHasSku = it.getSkuSnapshot() != null && !it.getSkuSnapshot().isBlank();
        if (!prevHasSku && curHasSku) {
            prev.setSkuSnapshot(it.getSkuSnapshot());
            if (it.getTitleSnapshot() != null) {
                prev.setTitleSnapshot(it.getTitleSnapshot());
            }
        }
    }

    @Override
    @Transactional
    public Order forwardOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        // Ya enviado al proveedor: se devuelve tal cual (idempotente), que es lo razonable si el panel
        // repite el clic o llegan dos peticiones.
        if (o.getStatus() == OrderStatus.FORWARDED) {
            return publishAndEnrich(o, "order.forwarded");
        }
        // Cualquier otro estado se RECHAZA en vez de responder 200 sin hacer nada. Antes, reenviar un
        // pedido ya entregado, cancelado o reembolsado devolvía el pedido intacto con un 200, así que el
        // panel no podía distinguir «hecho» de «no se hizo nada» y el operador se quedaba creyendo que
        // había avanzado. Sus hermanos (ship/deliver/refund) sí lanzan, y no había motivo para la excepción.
        if (o.getStatus() != OrderStatus.PENDING && o.getStatus() != OrderStatus.AWAITING_PAYMENT
                && o.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(
                    "Solo se puede enviar al proveedor un pedido pendiente o pagado; este está " + o.getStatus());
        }
        requireCustomsDataOnItems(o);
        o.setStatus(OrderStatus.FORWARDED);
        o.setForwardedAt(Instant.now());
        o = orderRepository.save(o);
        notificarAvance(o, "FORWARDED");
        return publishAndEnrich(o, "order.forwarded");
    }

    /**
     * Corta el despacho si a algún producto del pedido le faltan los datos que exige la aduana.
     *
     * <p>Se comprueba AQUÍ, en el paso «enviar al proveedor», porque es el último momento en que un
     * administrador está mirando: a partir de aquí la guía se emite sola desde el sondeo y lo que falle
     * lo descubre el cliente. Bloquear deja el pedido cobrado tal y como estaba —sigue PAGADO y el mismo
     * botón vuelve a funcionar en cuanto se corrija el catálogo—, mientras que despacharlo con la
     * declaración coja arriesga que el transportista rechace la guía o que la aduana retenga el paquete,
     * y eso ya no tiene arreglo barato.
     *
     * <p>NO se comprueba en el checkout a propósito: lo que falta es un dato de nuestro catálogo, así que
     * cortarle la compra al cliente sería castigarle por un fallo que no es suyo.
     *
     * <p>Una línea cuyo producto ya no está en el catálogo —pedidos manuales, referencias retiradas— no
     * bloquea: no hay ficha que corregir y dejaríamos el pedido sin salida. Ese caso lo sigue cubriendo el
     * corte de la transmisión, que mira la declaración realmente construida.
     */
    private void requireCustomsDataOnItems(Order o) {
        if (o.getItems() == null || o.getItems().isEmpty()) {
            return;
        }
        List<String> problemas = new ArrayList<>();
        for (OrderItem item : o.getItems()) {
            if (item.getProductId() == null) {
                continue;
            }
            ProductEntity product = productRepository.findById(item.getProductId()).orElse(null);
            if (product == null) {
                continue;
            }
            List<CustomsDataCheck.CustomsField> faltantes = CustomsDataCheck.faltantesDe(product);
            if (!faltantes.isEmpty()) {
                problemas.add(CustomsDataCheck.describe(nombreDeLinea(item, product), faltantes));
            }
        }
        if (!problemas.isEmpty()) {
            throw new BusinessException("INCOMPLETE_CUSTOMS_DATA",
                    "No se puede enviar al proveedor: faltan datos obligatorios de aduana. "
                            + String.join(". ", problemas)
                            + ". Complétalos en la ficha del producto y vuelve a intentarlo.");
        }
    }

    /** Cómo se nombra el producto en el aviso: por lo que el administrador ve en el pedido. */
    private static String nombreDeLinea(OrderItem item, ProductEntity product) {
        String titulo = item.getTitleSnapshot() != null && !item.getTitleSnapshot().isBlank()
                ? item.getTitleSnapshot()
                : product.getTitleZh();
        String sku = item.getSkuSnapshot();
        return sku != null && !sku.isBlank() ? titulo + " (" + sku + ")" : String.valueOf(titulo);
    }

    @Override
    @Transactional
    public Order shipOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        // DROP-631: "Marcar en camino" is only valid once the order was forwarded to the supplier.
        if (o.getStatus() != OrderStatus.FORWARDED) {
            throw new BusinessException("Solo se puede marcar en camino un pedido enviado al proveedor");
        }
        o.setStatus(OrderStatus.SHIPPED);
        o.setShippedAt(Instant.now());
        o = orderRepository.save(o);
        // El cliente ve DOS cosas: la barra de estado y el detalle del seguimiento. Cambiar solo el
        // estado dejaba la barra en «Entregado» y el detalle parado en «Envío registrado», que es
        // justo cuando alguien escribe preguntando dónde está su pedido.
        appendManualStep(o, OrderStatus.SHIPPED, "Paquete recogido por el transportista");
        sendOrderEmail(o, "shipped");
        notificarAvance(o, "SHIPPED");
        return publishAndEnrich(o, "order.shipped");
    }

    @Override
    @Transactional
    public Order deliverOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        // DROP-631: delivery only valid from "en camino" (SHIPPED).
        if (o.getStatus() != OrderStatus.SHIPPED) {
            throw new BusinessException("Solo se puede entregar un pedido que está en camino");
        }
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredAt(Instant.now());
        o = orderRepository.save(o);
        appendManualStep(o, OrderStatus.DELIVERED, "Entregado al destinatario");
        // Acredita al operador que entrega la comisión del 15% (CNY) y registra la operación (histórico).
        operatorCommissionService.recordDelivery(o);
        sendOrderEmail(o, "delivered");
        notificarAvance(o, "DELIVERED");
        return publishAndEnrich(o, "order.delivered");
    }

    @Override
    @Transactional
    public Order cancelOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        if (o.getStatus() == OrderStatus.CANCELLED) {
            return enrich(o); // idempotente
        }
        // Si el pedido ya estaba PAGADO (aunque haya avanzado), al cancelarlo se le devuelve el dinero
        // al cliente (a su método de pago original o al wallet). Los no pagados no generan reembolso.
        boolean wasPaid = o.getStatus() == OrderStatus.PAID || o.getStatus() == OrderStatus.FORWARDED
                || o.getStatus() == OrderStatus.SHIPPED || o.getStatus() == OrderStatus.DELIVERED;
        if (wasPaid) {
            issueRefund(o, "cancel-", false); // admin: reembolso al método original del cliente
            stockService.restoreForOrder(o); // la venta no se concretó → devolvemos el stock descontado
        }
        // Solo lo que aún no se ha comprado: lo ya pagado al proveedor sigue en el tablero porque esa
        // mercancía existe y hay que decidir qué se hace con ella antes de que el almacén la destruya.
        supplierPurchaseService.cancelUnbought(id, "Pedido cancelado por el administrador");
        o.setStatus(OrderStatus.CANCELLED);
        o.setCancelledAt(Instant.now());
        o = orderRepository.save(o);
        affiliateProgramService.rejectForOrder(o.getId()); // DROP-646: void any affiliate commission
        if (wasPaid) {
            sendRefundEmail(o, false); // admin: reembolso al método original del cliente
        }
        return publishAndEnrich(o, "order.cancelled");
    }

    @Override
    @Transactional
    public Order refundOrder(UUID id) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        if (o.getStatus() == OrderStatus.REFUNDED) {
            return enrich(o); // idempotent
        }
        if (o.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("Cannot refund a cancelled order");
        }
        issueRefund(o, "refund-", false); // admin: reembolso al método original del cliente
        stockService.restoreForOrder(o); // la venta no se concretó → devolvemos el stock descontado
        supplierPurchaseService.cancelUnbought(id, "Pedido reembolsado");
        o.setStatus(OrderStatus.REFUNDED);
        o = orderRepository.save(o);
        affiliateProgramService.rejectForOrder(o.getId()); // DROP-646: void any affiliate commission
        sendRefundEmail(o, false); // admin: reembolso al método original del cliente
        return publishAndEnrich(o, "order.refunded");
    }

    /**
     * Cancelación por el PROPIO cliente desde su panel de pedidos. Solo se permite mientras el pedido
     * está {@code PAID} (pagado pero aún NO enviado al proveedor): se le devuelve el dinero y el pedido
     * queda {@code CANCELLED}. Si ya avanzó (enviado a proveedor/en camino/entregado) NO se puede cancelar
     * — eso sería una devolución, que se gestiona manualmente cuando recibimos el producto de vuelta.
     */
    @Override
    @Transactional
    public Order cancelMyOrder(UUID userId, UUID orderId, boolean refundToWallet) {
        // Los mensajes son un fallback técnico (en inglés, para logs); el texto que ve el usuario lo
        // localiza el front a partir del CODE devuelto, en su idioma de navegación.
        Order o = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", ORDER_NOT_FOUND));
        if (o.getUserId() == null || !o.getUserId().equals(userId)) {
            throw new NotFoundException("ORDER_NOT_FOUND", ORDER_NOT_FOUND); // no filtramos pedidos ajenos
        }
        if (o.getStatus() != OrderStatus.PAID) {
            // Ya avanzó (enviado a proveedor/en camino/entregado) o ya está cancelado/reembolsado.
            throw new BusinessException("ORDER_NOT_CANCELLABLE",
                    "The order can no longer be cancelled because it is already being processed.");
        }
        // El estado PAID no basta: el tablero de compras avanza por su cuenta y marcar «COMPRADO» NO
        // mueve el pedido, que sigue en PAID hasta que salen TODAS sus compras. Un pedido con el género
        // ya pagado en 1688 seguía ofreciendo cancelar, y ese reembolso salía entero de la caja del
        // comercio sin nadie a quien reclamárselo.
        if (supplierPurchaseService.anyBought(orderId)) {
            throw new BusinessException("ORDER_NOT_CANCELLABLE",
                    "The order can no longer be cancelled: the goods were already purchased from the supplier.");
        }
        // El cliente elige: wallet (inmediato) o su método original (tarjeta/PayPal, con sus tiempos).
        issueRefund(o, "cancel-", refundToWallet);
        stockService.restoreForOrder(o); // estaba PAID (stock ya descontado) → lo devolvemos al no concretarse
        // Sin esto la compra se quedaba en el tablero y el admin acababa comprándola en 1688.
        supplierPurchaseService.cancelUnbought(orderId, "Pedido cancelado por el cliente");
        o.setStatus(OrderStatus.CANCELLED);
        o.setCancelledAt(Instant.now());
        o = orderRepository.save(o);
        affiliateProgramService.rejectForOrder(o.getId());
        sendRefundEmail(o, refundToWallet); // el cliente recibe el aviso de reembolso (destino que eligió)
        return publishAndEnrich(o, "order.cancelled");
    }

    /**
     * Devuelve el importe del pedido al destino que corresponda:
     * <ul>
     *   <li>{@code toWallet == true}: se acredita el WALLET del comprador (inmediato), sea cual sea el
     *       método original. Es la opción "sugerida" que el cliente puede elegir.</li>
     *   <li>{@code toWallet == false}: se devuelve al MÉTODO ORIGINAL — si se pagó con tarjeta/PayPal el
     *       reembolso se hace EN el proveedor (Stripe/PayPal, con sus tiempos); si se pagó con wallet (o
     *       sin pago externo) se acredita el wallet.</li>
     * </ul>
     * {@code refPrefix} identifica el movimiento (idempotencia del abono al wallet).
     */
    private void issueRefund(Order o, String refPrefix, boolean toWallet) {
        if (!toWallet) {
            Payment external = paymentUseCase.listOrderPayments(o.getId()).stream()
                    .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                    .filter(p -> "stripe".equals(p.getProvider()) || "paypal".equals(p.getProvider())).findFirst()
                    .orElse(null);
            if (external != null) {
                paymentUseCase.refundOrderPayment(o.getId(), external.getId(), 0); // reembolso total en el proveedor
                return;
            }
            // Sin pago externo (pago con wallet): no hay nada que reembolsar en proveedor → wallet.
        }
        // El arancel de la UE no lo reintegra el transportista una vez el paquete entra en su almacén.
        // En desistimiento lo asume el comercio —la Directiva obliga a devolver todo—; si la devolución
        // nace de una causa del cliente, se descuenta. Así está escrito en las condiciones (v140).
        long amountCents = RefundPolicy.refundableCents(o, RefundPolicy.Reason.WITHDRAWAL);
        if (o.getUserId() != null && amountCents > 0) {
            walletUseCase.deposit(o.getUserId(), amountCents, o.getId(), refPrefix + o.getId(),
                    "Refund order " + o.getOrderNumber());
        }
    }

    @Override
    @Transactional
    public Order createDemoOrder() {
        if (!demoOrdersEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Demo order creation is disabled in this environment.");
        }
        ProductEntity p = productRepository.findAll().stream()
                .filter(x -> x.getStatus() != null && x.getStatus().name().equals("ACTIVE"))
                .filter(x -> x.getBasePrice() != null).findFirst()
                .orElseThrow(() -> new NotFoundException("No active product to seed demo order"));
        AddressInput addr = new AddressInput("Demo Customer", "+34000000", "demo@nx036.local", "C/ Demo 1", null,
                "Madrid", "M", "28001", "ES");
        CreateOrderRequest req = new CreateOrderRequest("DEMO-" + Instant.now().getEpochSecond(), addr, null,
                List.of(new OrderItemInput(p.getId(), null, 2)), "demo order from admin panel");
        return newOrder(null, null, req);
    }

    /* ============ Me (B2C) ============ */

    @Override
    @Transactional(readOnly = true)
    public List<Order> listMyOrders(UUID userId) {
        return orderRepository.findAll().stream().filter(o -> userId.equals(o.getUserId())).sorted((a, b) -> {
            Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
            Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
            return bi.compareTo(ai);
        }).toList();
    }

    @Override
    @Transactional
    public Order checkout(UUID userId, MeCheckoutDtoIn req, String idem) {
        AddressInput addr = resolveShippingAddress(userId, req);

        List<OrderItemInput> items = req.getItems().stream()
                .map(i -> new OrderItemInput(i.getProductId(), i.getVariantId(), i.getQuantity())).toList();

        // Idempotencia a nivel de orden: si este mismo carrito (mismo idem) ya creó una
        // orden AÚN SIN PAGAR, la reutilizamos en vez de crear un duplicado. Así un intento
        // abandonado en la pasarela + un reintento no dejan dos órdenes.
        String idemKeyTrim = (idem != null && !idem.isBlank()) ? idem.trim() : null;
        // Idempotencia REAL del checkout: incluimos también PAID. Un reintento del MISMO Idempotency-Key que
        // ya produjo un pedido PAGADO debe devolver ESE pedido tal cual, nunca crear otro. Sin esto —como el
        // cargo al wallet es idempotente por clave— reenviar el idem creaba un pedido nuevo que se marcaba
        // PAID sin volver a cobrar (minteo de pedidos gratis ilimitados).
        CustomerOrderEntity reusable = idemKeyTrim == null
                ? null
                : orderEntityRepository
                        .findFirstByUserIdAndIdempotencyKeyAndStatusInOrderByCreatedAtDesc(userId, idemKeyTrim,
                                List.of(OrderStatus.PENDING, OrderStatus.AWAITING_PAYMENT, OrderStatus.PAID))
                        .orElse(null);
        if (reusable != null && reusable.getStatus() == OrderStatus.PAID) {
            return orderRepository.findById(reusable.getId()).orElseThrow(() -> new NotFoundException(ORDER_RESOURCE));
        }
        boolean reused = reusable != null;

        Order created;
        if (reused) {
            created = orderRepository.findById(reusable.getId())
                    .orElseThrow(() -> new NotFoundException(ORDER_RESOURCE));
        } else {
            CreateOrderRequest orderReq = new CreateOrderRequest("ME-" + Instant.now().getEpochSecond(), addr, null,
                    items, req.getNotes(), req.getCouponCode(), req.getShippingOptionCode());
            created = newOrder(null, userId, orderReq);
        }

        // DROP-549: only charge the wallet when the requested method is WALLET.
        // For CARD/PAYPAL/USDT the order stays PENDING and the client follows up
        // with /me/orders/{id}/payment-intent for the external flow.
        String method = req.getPaymentMethod() == null ? WALLET : req.getPaymentMethod().toUpperCase();
        Order o = orderRepository.findById(created.getId()).orElseThrow(() -> new NotFoundException(ORDER_RESOURCE));
        if (WALLET.equals(method)) {
            long charge = created.getTotalCents();
            // Clave de idempotencia del cargo ACOTADA AL PEDIDO: el débito es único por pedido y no se puede
            // reutilizar la clave del cliente entre pedidos distintos para colar cargos a cero. La compone
            // WalletUseCase y no este método: el pago posterior del mismo pedido entra por otro camino, y
            // con un prefijo distinto en cada uno el monedero no deduplicaba y se debitaba dos veces.
            String idemKey = WalletUseCase.claveDeCargoDePedido(created.getId());
            walletUseCase.charge(userId, charge, created.getId(), idemKey, "Order " + created.getOrderNumber());
            o.setStatus(OrderStatus.PAID);
            // El dinero ya está cobrado: a la cola de compras de 1688. Los pagos externos (Stripe/PayPal)
            // planifican al confirmarse en PaymentUseCaseImpl; este camino cobra el wallet en el acto y
            // sin esta llamada el pedido quedaba SIN compra — y el freno, al ver cero compras, lo trataba
            // como pedido antiguo y dejaba emitir la guía sin mercancía comprada.
            supplierPurchaseService.planPurchases(o);
        } else {
            o.setStatus(OrderStatus.PENDING);
        }
        o = orderRepository.save(o);
        if (!WALLET.equals(method) && !reused) {
            // «Hemos recibido tu pedido»: el pago externo puede tardar (o abandonarse) y sin este correo
            // el cliente no tenía ninguna constancia escrita hasta la factura. Con saldo no hace falta:
            // se cobra en el acto y su primer correo ya es la factura. Solo la 1ª vez (no en reuso idem).
            sendOrderEmail(o, "placed");
        }

        // Sella el idem en la orden para que un reintento del MISMO carrito la reutilice
        // (se hace al final: save() de dominio hace update parcial y no toca esta columna).
        if (idemKeyTrim != null && !reused) {
            final UUID savedId = o.getId();
            orderEntityRepository.findById(savedId).ifPresent(e -> {
                e.setIdempotencyKey(idemKeyTrim);
                orderEntityRepository.save(e);
            });
        }

        // DROP-645/646: capture an affiliate conversion + commission for this confirmed order
        // (no-op if the customer has no live referral attribution). Solo la 1ª vez (no en reuso).
        // La comisión del afiliado se calcula sobre el importe de PRODUCTO que paga el cliente = subtotal
        // − descuento de referido (lo realmente cobrado por el producto, sin envío ni IVA).
        if (!reused) {
            long commissionBase = (long) o.getSubtotalCents() - o.getDiscountCents();
            affiliateProgramService.onOrderPlaced(o.getId(), userId, commissionBase, o.getCurrency());
        }

        notifyCheckout(userId, created, o, reused);

        // Lo último del checkout: si el pedido ha quedado PAGADO (pago con saldo, que se cobra en el acto),
        // lo comprado sale de la cesta. Con pago externo (tarjeta/PayPal/USDT) el pedido sale PENDIENTE y
        // la cesta se queda INTACTA —el dinero puede no llegar nunca, y vaciarla aquí dejaría a la persona
        // sin pedido y sin cesta—: esa limpieza la hace PaymentUseCaseImpl al confirmarse el cobro. Va al
        // final porque el borrado se confirma en su propia transacción, y así ningún paso posterior puede
        // tumbar el pedido dejando la cesta ya vacía.
        if (o.getStatus() == OrderStatus.PAID) {
            clearPurchasedFromCart(o);
        }

        return o;
    }

    /**
     * Saca de la cesta las líneas del pedido recién pagado con saldo.
     *
     * <p>Un fallo limpiando NUNCA puede tumbar el cobro: el dinero ya se ha debitado del monedero y el
     * pedido está pagado. Dejar restos en la cesta es un mal mucho menor que perder un pago por no poder
     * borrar una fila, así que se registra el motivo —en claro, sin SQL— y el checkout sigue.
     */
    private void clearPurchasedFromCart(Order order) {
        try {
            cartService.removePurchased(order);
        } catch (RuntimeException e) {
            log.warn("Pedido {} PAGADO pero no se pudo vaciar la cesta: {}", order.getId(), ErrorMessages.humanize(e),
                    e);
        }
    }

    /**
     * Dirección de envío del pedido: la guardada que indique el cliente o la que llega en línea.
     *
     * <p>Una dirección guardada de OTRO usuario se responde 404 y no 403: un 403 confirmaría al atacante
     * que esa dirección existe. Y el destino se comprueba AQUÍ, antes de crear y cobrar nada, porque
     * descubrir después que no hay transporte deja un cobro que hay que reembolsar.
     */
    private AddressInput resolveShippingAddress(UUID userId, MeCheckoutDtoIn req) {
        AddressInput addr = req.getShippingAddressInline();
        if (req.getShippingAddressId() != null) {
            UserAddressEntity saved = userAddressRepository.findById(req.getShippingAddressId())
                    .orElseThrow(() -> new NotFoundException("Address not found"));
            if (!saved.getUser().getId().equals(userId)) {
                throw new NotFoundException("Address not found");
            }
            addr = new AddressInput(saved.getFullName(), saved.getPhone(), null, saved.getLine1(), saved.getLine2(),
                    saved.getCity(), saved.getState(), saved.getPostalCode(), saved.getCountry());
        }
        if (addr == null) {
            throw new BusinessException("SHIPPING_ADDRESS_REQUIRED", "Shipping address is required");
        }
        if (!fulfillment.isSupported(addr.country())) {
            throw new BusinessException(
                    "No realizamos envíos a este destino (" + addr.country() + "). Elige un país soportado.");
        }
        // El código postal tiene que ser el del país elegido. Va ANTES del bloqueo de zonas y no es un
        // detalle de formulario: las zonas excluidas se comparan por número, así que un código con una
        // letra de más («07001A») quedaba fuera de la comparación y colaba un envío a Baleares.
        PostalCodeCheck.require(addr.country(), addr.postalCode());
        // Dentro de un país servible hay zonas que el transportista excluye —en España, Baleares,
        // Canarias, Ceuta y Melilla—. Se comprueba AQUÍ, antes de cobrar: aceptarlo dejaría un pedido
        // pagado que nadie puede despachar.
        if (unserviceableZoneService.isUnserviceable(addr.country(), addr.postalCode())) {
            throw new BusinessException("UNSERVICEABLE_POSTAL_CODE", "El transportista no entrega en el código postal "
                    + addr.postalCode() + ". Prueba con otra dirección.");
        }
        return addr;
    }

    /**
     * Avisos del checkout. Se publican en la misma transacción que el pedido para que nunca quede un
     * "pedido sin notificación": el correo de "pedido recibido" solo la primera vez —en el reintento de un
     * carrito ya existente se envió antes—, y el de pago confirmado con su factura solo cuando la orden ya
     * sale PAID, que es el caso de haber pagado con el saldo del monedero.
     */
    /**
     * Avisa a quien compró de que su pedido ha avanzado.
     *
     * <p>Los avisos de enviado y entregado existían desde hacía tiempo y no los llamaba nadie: el
     * cliente veía su pedido moverse en la pantalla del pedido —si entraba a mirar— pero no recibía
     * nada. Ahora se avisa en cada paso que la pantalla enseña.
     *
     * <p>Un fallo al avisar no puede tumbar el avance del pedido, que ya está guardado: la mercancía se
     * movió de verdad y volver atrás por no haber podido mandar un correo sería peor que el silencio.
     */
    private void notificarAvance(Order o, String estado) {
        if (o.getUserId() == null) {
            return;
        }
        try {
            userRepository.findById(o.getUserId()).ifPresent(u -> {
                switch (estado) {
                    case "SHIPPED" -> notificationsPublisher.orderShipped(o.getUserId(), u.getEmail(),
                            o.getOrderNumber(), o.getCarrier(), o.getTrackingNumber(), u.getLanguage());
                    case "DELIVERED" -> notificationsPublisher.orderDelivered(o.getUserId(), u.getEmail(),
                            o.getOrderNumber(), u.getLanguage());
                    default -> notificationsPublisher.dispatch("ORDER_FORWARDED", o.getUserId(), u.getEmail(),
                            Map.of("orderNumber", o.getOrderNumber()), u.getLanguage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar del avance a {} del pedido {}: {}", estado, o.getOrderNumber(), e.getMessage());
        }
    }

    private void notifyCheckout(UUID userId, Order created, Order placed, boolean reused) {
        String totalPlain = BigDecimal.valueOf(created.getTotalCents())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP).toPlainString();
        userRepository.findById(userId).ifPresent(u -> {
            if (!reused) {
                notificationsPublisher.orderPlaced(userId, u.getEmail(), created.getOrderNumber(), totalPlain,
                        created.getCurrency(), u.getLanguage());
            }
            if (placed.getStatus() == OrderStatus.PAID) {
                orderEmailService.paymentConfirmed(placed, u.getEmail(), u.getLanguage(), WALLET);
                // Y en el buzón de la aplicación. Hasta ahora el pago solo salía por correo y por el
                // bus: quien pagaba desde el móvil abría «Avisos» y lo encontraba VACÍO con el pedido
                // ya cobrado. Best-effort: un fallo aquí no puede tumbar un cobro que ya ha ocurrido.
                try {
                    notificationUseCase.orderPaid(userId, placed.getOrderNumber(), u.getLanguage());
                } catch (RuntimeException e) {
                    log.warn("No se pudo dejar el aviso de pago del pedido {}: {}", placed.getOrderNumber(),
                            e.getMessage());
                }
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Order getMyOrderDetail(UUID userId, UUID id, String lang) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new NotFoundException(ORDER_RESOURCE));
        if (!userId.equals(o.getUserId()))
            throw new NotFoundException(ORDER_RESOURCE);
        resolveItemTitles(o, lang);
        return o;
    }

    /* ============ Helpers ============ */

    /** Enriches the order, publishes the lifecycle webhook with the legacy payload shape. */
    private Order publishAndEnrich(Order o, String eventType) {
        Order enriched = enrich(o);
        webhooks.publish(eventType, o.getId().toString(), toWebhookPayload(enriched));
        // Auto-sync del índice OpenSearch en cada transición de estado (forward/ship/deliver/cancel/refund).
        orderIndexer.indexOrder(o.getId());
        return enriched;
    }

    /** Resuelve email/idioma del comprador y dispara el email transaccional del pedido. */
    private void sendOrderEmail(Order o, String kind) {
        if (o.getUserId() == null) {
            return;
        }
        userRepository.findById(o.getUserId()).ifPresent(u -> {
            switch (kind) {
                case "placed" -> orderEmailService.placedAwaitingPayment(o, u.getEmail(), u.getLanguage());
                case "shipped" -> orderEmailService.shipped(o, u.getEmail(), u.getLanguage());
                case "delivered" -> orderEmailService.delivered(o, u.getEmail(), u.getLanguage());
                case "refunded" -> orderEmailService.refunded(o, u.getEmail(), u.getLanguage());
                default -> {
                    /* sin email para otros estados */ }
            }
        });
    }

    /**
     * Email de reembolso enriquecido: resuelve del pago satisfactorio el método original y la moneda
     * cobrada para que el correo muestre el importe exacto y el destino correcto del reembolso.
     *
     * @param toWallet true si el reembolso se acreditó al saldo (inmediato); false = al método original.
     */
    private void sendRefundEmail(Order o, boolean toWallet) {
        if (o.getUserId() == null) {
            return;
        }
        Payment paid = paymentUseCase.listOrderPayments(o.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED).findFirst().orElse(null);
        String method = paid != null && paid.getMethod() != null ? paid.getMethod().name() : WALLET;
        // Se devuelve en la divisa en que se COBRÓ; si el pago no la fijó, la del pedido.
        String ccy = Texts.firstNonBlankOr("USD", paid != null ? paid.getSettlementCurrency() : null, o.getCurrency());
        userRepository.findById(o.getUserId())
                .ifPresent(u -> orderEmailService.refunded(o, u.getEmail(), u.getLanguage(), toWallet, ccy, method));
    }

    /** Fills the cross-aggregate read fields (customerEmail/shopName/shopHandle/supplierName). */
    private Order enrich(Order o) {
        if (o.getUserId() != null) {
            userRepository.findById(o.getUserId()).ifPresent(u -> o.setCustomerEmail(u.getEmail()));
            shopConnectionRepository.findByUser_IdOrderByCreatedAtDesc(o.getUserId()).stream().findFirst()
                    .ifPresent(sc -> {
                        o.setShopName(sc.getShopHandle());
                        o.setShopHandle(sc.getShopHandle());
                    });
        }
        if (o.getItems() != null && !o.getItems().isEmpty() && o.getItems().get(0).getSupplierName() != null) {
            o.setSupplierName(o.getItems().get(0).getSupplierName());
        }
        return o;
    }

    /**
     * DROP-537: resolves each line's display title for the request language using the
     * legacy fallback chain product_translation[lang] -> [en] -> snapshot -> titleZh and
     * stores it back into {@code titleSnapshot} so the api mapper stays free of logic.
     */
    private void resolveItemTitles(Order o, String lang) {
        if (o.getItems() == null)
            return;
        for (OrderItem i : o.getItems()) {
            Map<String, String> titles = i.getProductTitles();
            String title = null;
            if (titles != null) {
                title = titles.get(lang == null ? null : lang.toLowerCase());
                if (title == null)
                    title = titles.get("en");
            }
            if (title == null) {
                title = i.getTitleSnapshot() != null ? i.getTitleSnapshot() : i.getProductTitleZh();
            }
            i.setTitleSnapshot(title);
        }
    }

    /** Build the webhook envelope data payload, preserving the legacy row shape. */
    private Map<String, Object> toWebhookPayload(Order o) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", o.getId());
        r.put("orderNumber", o.getOrderNumber());
        r.put("status", o.getStatus().name());
        r.put("partnerAppId", o.getPartnerAppId());
        r.put("subtotalCents", o.getSubtotalCents());
        r.put("shippingCents", o.getShippingCents());
        r.put("totalCents", o.getTotalCents());
        r.put("currency", o.getCurrency());
        r.put("itemCount", o.getItems() != null ? o.getItems().size() : 0);
        r.put("placedAt", o.getPlacedAt());
        r.put("forwardedAt", o.getForwardedAt());
        r.put("shippedAt", o.getShippedAt());
        r.put("deliveredAt", o.getDeliveredAt());
        r.put("cancelledAt", o.getCancelledAt());
        if (o.getCustomerEmail() != null)
            r.put("customerEmail", o.getCustomerEmail());
        if (o.getShopName() != null) {
            r.put("shopName", o.getShopName());
            r.put("shopHandle", o.getShopHandle());
        }
        if (o.getSupplierName() != null)
            r.put("supplierName", o.getSupplierName());
        return r;
    }

    /** Título del producto en {@code lang} para el snapshot del pedido (fallback en → es → cualquiera → zh). */
    private String orderTitle(ProductEntity p, String lang) {
        String byLang = translationTitle(p, lang);
        if (byLang != null) {
            return byLang;
        }
        String en = translationTitle(p, "en");
        if (en != null) {
            return en;
        }
        String es = translationTitle(p, "es");
        if (es != null) {
            return es;
        }
        if (p.getTranslations() != null) {
            for (ProductTranslationEntity tr : p.getTranslations()) {
                if (tr.getTitle() != null && !tr.getTitle().isBlank()) {
                    return tr.getTitle();
                }
            }
        }
        return p.getTitleZh();
    }

    private String translationTitle(ProductEntity p, String lang) {
        if (p.getTranslations() == null || lang == null) {
            return null;
        }
        return p.getTranslations().stream().filter(
                tr -> lang.equalsIgnoreCase(tr.getLanguage()) && tr.getTitle() != null && !tr.getTitle().isBlank())
                .map(ProductTranslationEntity::getTitle).findFirst().orElse(null);
    }

    /** Applies an inline address onto the order's flat shipping/billing snapshot fields. */
    private void applyAddress(Order order, AddressInput in, boolean billing) {
        if (billing) {
            order.setBillingFullName(in.fullName());
            order.setBillingPhone(in.phone());
            order.setBillingEmail(in.email());
            order.setBillingLine1(in.line1());
            order.setBillingLine2(in.line2());
            order.setBillingCity(in.city());
            order.setBillingState(in.state());
            order.setBillingPostalCode(in.postalCode());
            order.setBillingCountry(in.country());
        } else {
            order.setShippingFullName(in.fullName());
            order.setShippingPhone(in.phone());
            order.setShippingEmail(in.email());
            order.setShippingLine1(in.line1());
            order.setShippingLine2(in.line2());
            order.setShippingCity(in.city());
            order.setShippingState(in.state());
            order.setShippingPostalCode(in.postalCode());
            order.setShippingCountry(in.country());
        }
    }

    private String generateOrderNumber() {
        long ts = Instant.now().getEpochSecond();
        int rnd = RNG.nextInt(9000) + 1000;
        return "NX-" + ts + "-" + rnd;
    }

    /**
     * Deja constancia en el seguimiento de un paso que marcó una persona, no el transportista.
     *
     * <p>Se etiqueta como ADMIN para que el timeline distinga lo que informó el carrier de lo que se
     * anotó a mano, y se escribe en un try-catch: perder una línea del seguimiento es un incordio, pero
     * tumbar la transición del pedido por ello sería peor.
     *
     * <p><b>Sólo si el hito no está ya contado.</b> Estas transiciones no las dispara únicamente el botón
     * del panel: {@code FulfillmentSyncScheduler} llama a {@code shipOrder}/{@code deliverOrder} cuando el
     * transportista informa del avance, precisamente para que salgan el correo y el webhook. Por ese
     * camino el hito llega dos veces —el evento del carrier y esta anotación— y el cliente lo lee dos
     * veces en su seguimiento: se vio en la certificación del 18-ago-2026, con «Entregado al destinatario»
     * repetido con dos segundos de diferencia, y antes «Recogido por el transportista» seguido de
     * «Paquete recogido por el transportista», que es el mismo hecho con otras palabras.
     *
     * <p>La anotación no se puede quitar sin más: hay pedidos que avanzan a mano, sin transportista que
     * informe, y sin ella el cliente ve la barra en «Entregado» y el detalle parado en «Envío registrado»
     * —que es justo cuando escribe preguntando dónde está su pedido—.
     */
    private void appendManualStep(Order o, OrderStatus status, String description) {
        try {
            if (yaLoContoElTransportista(o, status)) {
                return;
            }
            trackingRepository.save(OrderTrackingEventEntity.builder().orderId(o.getId()).status(status.name())
                    .description(description).location(o.getShippingCountry()).source("ADMIN").occurredAt(Instant.now())
                    .createdAt(Instant.now()).build());
        } catch (RuntimeException e) {
            log.warn("No se pudo anotar el paso {} en el seguimiento del pedido {}: {}", status, o.getOrderNumber(),
                    e.getMessage());
        }
    }

    /**
     * ¿El transportista ya contó este hito en el seguimiento?
     *
     * <p>Se compara por ESTADO y no por el texto: la descripción la escribe el carrier en sus términos
     * y en su idioma —«Delivered to recipient», «Entregado al destinatario»—, así que cotejar
     * descripciones daría por nuevo lo que ya está contado en cuanto cambie una palabra.
     *
     * <p>Sólo cuentan los eventos del carrier: los {@code ADMIN} son estas mismas anotaciones y los
     * {@code SYSTEM} son transiciones internas, así que tomarlos por información del transportista
     * silenciaría el paso en los pedidos que avanzan a mano, que son los que más lo necesitan.
     */
    private boolean yaLoContoElTransportista(Order o, OrderStatus status) {
        for (OrderTrackingEventEntity evento : trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId())) {
            boolean delCarrier = evento.getSource() != null && !"ADMIN".equals(evento.getSource())
                    && !"SYSTEM".equals(evento.getSource());
            if (delCarrier && status.name().equals(evento.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Los productos del pedido, que el enrutador necesita para decidir qué líneas admiten la mercancía:
     * la de ropa de YunExpress solo lleva textil. Las líneas cuyo producto ya no está en el catálogo se
     * omiten en vez de bloquear: no hay ficha que consultar y dejar el pedido sin envío sería peor.
     */
    private java.util.List<ProductEntity> productosDe(Order order) {
        java.util.List<ProductEntity> productos = new java.util.ArrayList<>();
        if (order.getItems() == null) {
            return productos;
        }
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue;
            }
            productRepository.findById(item.getProductId()).ifPresent(productos::add);
        }
        return productos;
    }

    /**
     * Bultos a declarar de un pedido, con sus partidas arancelarias. Es el mismo cálculo que se le mostró al
     * cliente en el checkout, para que lo cobrado y lo declarado coincidan.
     */
    private java.util.List<CustomsDutyLinesService.DutyParcel> customsParcelsOf(Order order) {
        java.util.List<CustomsDutyLinesService.Line> lines = new java.util.ArrayList<>();
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue;
            }
            ProductEntity p = productRepository.findById(item.getProductId()).orElse(null);
            if (p == null) {
                continue;
            }
            ProductVariantEntity v = p.getVariants().stream()
                    .filter(x -> item.getVariantId() != null && item.getVariantId().equals(x.getId())).findFirst()
                    .orElse(null);
            // La descripción tiene que ser la MISMA que se le transmite al transportista, o el arancel
            // que se cobra y el que se declara cuentan líneas distintas. Allí manda el título inglés
            // CONGELADO en el pedido y solo a falta de él el del producto (ver `englishName` en
            // YunExpressFulfillmentService); aquí se replica ese orden, que además es el correcto: un
            // pedido antiguo debe seguir contando por lo que se declaró, no por cómo se llame el
            // producto hoy.
            // El SNAPSHOT manda sobre todo lo demás: si el grupo se aprobó después de cobrar, este
            // pedido sigue contando por lo que se declaró. Sin esta prioridad, la vista previa habría
            // contado una línea y el despacho contaría dos, y esos 3 EUR los pondría el comercio.
            String descripcionDeclarada = item.getDeclaredDescription() != null
                    && !item.getDeclaredDescription().isBlank()
                            ? item.getDeclaredDescription()
                            : item.getProductTitles() != null && item.getProductTitles().get("en") != null
                                    && !item.getProductTitles().get("en").isBlank()
                                            ? item.getProductTitles().get("en")
                                            : CustomsDutyLinesService.declaredDescriptionOf(p);
            lines.add(new CustomsDutyLinesService.Line(p.getId(), p.getHsCode(), descripcionDeclarada,
                    p.getCountryOfOrigin(), Math.max(1, item.getQuantity()), item.getUnitPriceCents(),
                    ParcelAggregator.unitWeightGrams(p, v), 0, 0, 0, ParcelAggregator.hasBattery(p)));
        }
        // Mismo canal y mismo país que usará el despacho: el peso máximo por bulto sale de ese par y, si
        // aquí se contasen menos bultos, el derecho por partida cobrado se quedaría corto respecto al que
        // liquida la aduana — y la diferencia la pone el comercio.
        return customsDutyLinesService.parcelsOf(lines, order.getShippingChannelCode(), order.getShippingCountry());
    }

    /**
     * Los productos del pedido, para que la bolsa los cuente UNA VEZ CADA UNO.
     *
     * <p>La cantidad ya no interviene: la subvención la asigna el admin por producto y el proveedor
     * manda un solo bulto tenga el cliente una unidad o cinco.
     */
    private List<ProductEntity> productosDelPedido(Order order) {
        List<ProductEntity> out = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue;
            }
            ProductEntity p = productRepository.findById(item.getProductId()).orElse(null);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }
}
