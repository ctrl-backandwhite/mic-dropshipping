package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos;
import com.nexaplatform.dropshipping.application.service.Texts;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.ParcelAggregator;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

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
    private final PricingService pricingService;
    private final AffiliateProgramService affiliateProgramService;
    private final StockService stockService;
    private final PaymentUseCase paymentUseCase;
    private final OrderEmailService orderEmailService;
    private final FulfillmentProvider fulfillment;
    private final CheckoutTotalsService checkoutTotalsService;
    private final OperatorCommissionService operatorCommissionService;
    private final OrderIndexer orderIndexer;
    private final OrderSearchService orderSearchService;

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
                .source(orderSource)
                .externalOrderId(req.externalOrderId()).status(OrderStatus.PENDING).currency("USD").notes(req.notes())
                .placedAt(Instant.now()).items(new ArrayList<>()).build();

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
        for (OrderItemInput itemReq : req.items()) {
            OrderItem line = buildLine(itemReq, orderLang, parcel);
            order.getItems().add(line);
            subtotal = Math.addExact(subtotal, line.getLineTotalCents());
        }

        applyTotals(order, userId, subtotal, parcel);

        Order saved = orderRepository.save(order);
        orderIndexer.indexOrder(saved.getId()); // auto-sync del índice al crear la orden
        return saved;
    }

    /**
     * Construye una línea del pedido a partir de lo pedido, congelando precio, coste y textos.
     *
     * <p>Todo lo que se guarda aquí es una FOTO del momento de la compra: si mañana cambia el precio, el
     * título o la imagen del producto, la línea vendida sigue diciendo lo que se vendió.
     */
    private OrderItem buildLine(OrderItemInput itemReq, String orderLang,
            ParcelAggregator parcel) {
        // Cantidad dentro de un rango sano ANTES de calcular importes. Cierra el desbordamiento de
        // enteros del cobro (unitCents * quantity) y rechaza cantidades ≤ 0 aunque el DTO no valide.
        if (itemReq.quantity() <= 0 || itemReq.quantity() > MAX_LINE_QUANTITY) {
            throw new BusinessException("INVALID_QUANTITY",
                    "La cantidad por línea debe estar entre 1 y " + MAX_LINE_QUANTITY);
        }
        ProductEntity product = productRepository.findById(itemReq.productId())
                .orElseThrow(() -> new NotFoundException("Product not found: " + itemReq.productId()));
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
        PricedAmount priced = pricingService.priceFor(product, variant);
        BigDecimal unitPrice = priced.retailUsd();
        if (unitPrice == null) {
            throw new BusinessException("Product " + product.getSlug() + " has no price");
        }
        // El precio de línea debe COINCIDIR con el precio que ve el usuario en el catálogo/carrito. El
        // catálogo redondea a 2 decimales al céntimo MÁS CERCANO (HALF_UP, ver CurrencyRateService), así
        // que el cobro usa el MISMO redondeo → catálogo == carrito == cobro, sin céntimos de más ni de menos.
        int unitCents = unitPrice.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
        int costCents = priced.costUsd() != null
                ? priced.costUsd().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue()
                : unitCents;
        // DROP: coste en YUAN (CNY) congelado al crear la orden = precio del proveedor (variante o base),
        // SIEMPRE en CNY (los productos se persisten solo en CNY). Base de la comisión del operador (15%).
        BigDecimal cnyUnit = variant != null && variant.getPrice() != null ? variant.getPrice()
                : product.getBasePrice();
        long costCnyCents = cnyUnit != null
                ? cnyUnit.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
                : 0L;
        // multiplyExact: si el producto se saliera de rango (int), lanza en vez de envolver a un
        // valor pequeño (que se cobraría de menos). Con MAX_LINE_QUANTITY nunca ocurre en la práctica.
        int lineTotal = Math.multiplyExact(unitCents, itemReq.quantity());

        parcel.add(product, variant, itemReq.quantity());
        return OrderItem.builder().productId(product.getId())
                .variantId(variant != null ? variant.getId() : null).titleSnapshot(orderTitle(product, orderLang))
                .imageUrlSnapshot(snapshotImage(product, variant))
                .skuSnapshot(variant != null ? variant.getSku() : null).unitPriceCents(unitCents)
                .costCents(costCents).costCnyCents(costCnyCents).quantity(itemReq.quantity())
                .lineTotalCents(lineTotal).build();
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
    private void applyTotals(Order order, UUID userId, int subtotal, ParcelAggregator parcel) {
        // Envío: tarifa por destino del carrier. Si el país no está cubierto, el envío queda en 0 aquí
        // (el checkout del storefront bloquea antes el destino no soportado). El bulto se arma con el
        // MISMO agregador que la vista previa del checkout: peso, medidas del paquete y batería.
        ShippingQuote quote = fulfillment.quote(order.getShippingCountry(), parcel.build());
        int shippingCents = quote.supported() ? quote.amountUsdCents() : 0;

        // Descuento de referido para el COMPRADOR: 10% del subtotal de producto si tiene una atribución
        // de afiliado viva (y no es su propio código). El envío y el IVA se calculan sobre (subtotal −
        // descuento).
        int discount = (int) affiliateProgramService.referralDiscountCents(userId, subtotal);
        int discountedSubtotal = subtotal - discount;

        // Impuesto + despacho aduanero. Incluye:
        //  · IVA por estado/provincia (US/CA/BR) o tasa nacional, sobre (subtotal − descuento) + envío.
        //  · Recargo del despacho DDP del país (lo que el transportista cobra por adelantar el impuesto).
        //  · Recargo de despacho formal si el valor de los bienes supera el umbral de minimis del destino.
        CheckoutTotalsService.CheckoutTotals totals = checkoutTotalsService
                .compute(order.getShippingCountry(), order.getShippingState(), discountedSubtotal, shippingCents);
        // Destino cuya política prohíbe vender por encima del umbral: se rechaza ANTES de cobrar, en vez de
        // aceptar un pedido que costaría aranceles y despacho formal no repercutidos.
        if (totals.blocked()) {
            throw new BusinessException("CUSTOMS_THRESHOLD_EXCEEDED",
                    "El valor del pedido supera el límite de importación de " + order.getShippingCountry()
                            + ". Reduce el importe del carrito o divídelo en varios pedidos.");
        }
        order.setSubtotalCents(subtotal);
        order.setDiscountCents(discount);
        order.setShippingCents(totals.shippingCents());
        order.setTaxCents(totals.taxCents());
        order.setTotalCents(totals.totalCents(discountedSubtotal));
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
        log.info("Admin manual order {} created ({} items, customer={})", order.getOrderNumber(),
                req.items().size(), customerEmail != null ? customerEmail : "guest");
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
    public Order getPartnerOrder(UUID id) {
        return requireOrder(id);
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
        if (o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.AWAITING_PAYMENT
                || o.getStatus() == OrderStatus.PAID) {
            o.setStatus(OrderStatus.FORWARDED);
            o.setForwardedAt(Instant.now());
            o = orderRepository.save(o);
        }
        return publishAndEnrich(o, "order.forwarded");
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
        sendOrderEmail(o, "shipped");
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
        // Acredita al operador que entrega la comisión del 15% (CNY) y registra la operación (histórico).
        operatorCommissionService.recordDelivery(o);
        sendOrderEmail(o, "delivered");
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
        // El cliente elige: wallet (inmediato) o su método original (tarjeta/PayPal, con sus tiempos).
        issueRefund(o, "cancel-", refundToWallet);
        stockService.restoreForOrder(o); // estaba PAID (stock ya descontado) → lo devolvemos al no concretarse
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
        long amountCents = o.getTotalCents();
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
        CustomerOrderEntity reusable = idemKeyTrim == null ? null
                : orderEntityRepository.findFirstByUserIdAndIdempotencyKeyAndStatusInOrderByCreatedAtDesc(
                        userId, idemKeyTrim, List.of(OrderStatus.PENDING, OrderStatus.AWAITING_PAYMENT)).orElse(null);
        boolean reused = reusable != null;

        Order created;
        if (reused) {
            created = orderRepository.findById(reusable.getId())
                    .orElseThrow(() -> new NotFoundException(ORDER_RESOURCE));
        } else {
            CreateOrderRequest orderReq = new CreateOrderRequest("ME-" + Instant.now().getEpochSecond(), addr, null,
                    items, req.getNotes());
            created = newOrder(null, userId, orderReq);
        }

        // DROP-549: only charge the wallet when the requested method is WALLET.
        // For CARD/PAYPAL/USDT the order stays PENDING and the client follows up
        // with /me/orders/{id}/payment-intent for the external flow.
        String method = req.getPaymentMethod() == null ? WALLET : req.getPaymentMethod().toUpperCase();
        Order o = orderRepository.findById(created.getId())
                .orElseThrow(() -> new NotFoundException(ORDER_RESOURCE));
        if (WALLET.equals(method)) {
            long charge = created.getTotalCents();
            String idemKey = idem != null ? idem : ("checkout-" + created.getId());
            walletUseCase.charge(userId, charge, created.getId(), idemKey, "Order " + created.getOrderNumber());
            o.setStatus(OrderStatus.PAID);
        } else {
            o.setStatus(OrderStatus.PENDING);
        }
        o = orderRepository.save(o);

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

        return o;
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
        return addr;
    }

    /**
     * Avisos del checkout. Se publican en la misma transacción que el pedido para que nunca quede un
     * "pedido sin notificación": el correo de "pedido recibido" solo la primera vez —en el reintento de un
     * carrito ya existente se envió antes—, y el de pago confirmado con su factura solo cuando la orden ya
     * sale PAID, que es el caso de haber pagado con el saldo del monedero.
     */
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
        String ccy = Texts.firstNonBlankOr("USD",
                paid != null ? paid.getSettlementCurrency() : null, o.getCurrency());
        userRepository.findById(o.getUserId()).ifPresent(u -> orderEmailService.refunded(
                o, u.getEmail(), u.getLanguage(), toWallet, ccy, method));
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
        return p.getTranslations().stream()
                .filter(tr -> lang.equalsIgnoreCase(tr.getLanguage()) && tr.getTitle() != null
                        && !tr.getTitle().isBlank())
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
}
