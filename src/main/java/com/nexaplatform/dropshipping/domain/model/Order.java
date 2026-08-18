package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure domain model for a customer order (aggregate root). Use cases operate on
 * this model; mappers translate to/from the JPA entity (infra) and the transport
 * DTOs (api). The {@code items} list is the nested sub-entity model; the address
 * relations are resolved by the repository adapter from the flattened ids.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private UUID id;
    private String orderNumber;
    private UUID partnerAppId;
    /** Origen: PLATFORM (tienda propia) o INTEGRATION (tienda conectada Shopify/WooCommerce/API). */
    private String source;
    private UUID userId;
    private String externalOrderId;
    private UUID shippingAddressId;
    private UUID billingAddressId;
    private OrderStatus status;
    private int subtotalCents;
    /**
     * Canal del transportista con el que se cotizó el envío que eligió el cliente. La guía se emite por
     * este y no por el que resulte más barato al despachar: entre el pedido y el despacho la tarifa
     * cambia, y entonces se cobraría una cosa y se enviaría otra.
     */
    private String shippingChannelCode;
    /**
     * Quién lleva el envío: {@code YUNEXPRESS}, {@code CJ}.
     *
     * <p>Con un solo transportista bastaba el código del canal. Con dos no se pueden distinguir
     * mirándolos —{@code FZZXR} contra {@code 1868922929754472449}— y despachar por el que no era
     * significa cobrar un porte y pagar otro. Lo rellena el cobro con el transportista de la opción que
     * el cliente eligió, y lo lee el despacho para saber a quién pedirle la guía.
     */
    private String shippingCarrier;
    /**
     * Cómo llama el transportista a la línea contratada.
     *
     * <p>Se guarda además del código porque CJ pide el NOMBRE para emitir la guía y el pedido solo
     * tenía el código: sin esto, el despacho tendría que volver a cotizar horas después solo para
     * cruzarlos, gastando una llamada de una API limitada a una por segundo y quedándose sin despachar
     * si para entonces CJ ya no ofrece esa línea.
     */
    private String shippingChannelName;

    private int shippingCents;
    private int taxCents;
    /**
     * Derecho de aduana de la Unión cobrado en el pedido, INCLUIDO ya en {@code shippingCents}. Viaja
     * aparte para que la factura pueda desglosarlo: un tributo que se recauda y se entrega a la aduana no
     * puede figurar escondido dentro del precio del transporte.
     */
    private int customsDutyCents;
    private int totalCents;
    // Descuento de referido aplicado al comprador (céntimos USD). totalCents ya lo resta.
    private int discountCents;
    private String currency;
    private String notes;
    private Instant placedAt;
    private Instant forwardedAt;
    private Instant shippedAt;
    private Instant deliveredAt;
    private Instant cancelledAt;

    // Fulfillment / tracking de Cainiao.
    private String carrier;
    private String trackingNumber;
    private String fulfillmentRef;
    private String trackingStatus;
    private Instant estimatedDeliveryAt;
    private Instant lastTrackedAt;

    // Estado del intento de creación del envío en el transportista. Permite espaciar los reintentos,
    // rendirse ante un fallo definitivo y enseñar el motivo al admin en vez de dejarlo solo en el log.
    private int fulfillmentAttempts;
    private String fulfillmentError;
    private Instant fulfillmentFailedAt;
    private Instant fulfillmentNextAttemptAt;

    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    // Read-only enrichment (filled by the use case from cross-aggregate relations).
    private String customerEmail;
    private String shopName;
    private String shopHandle;
    private String supplierName;

    // Flattened shipping-address snapshot (resolved by the repository adapter from
    // the managed AddressEntity) so the api mappers can build the address blocks
    // without touching the entity.
    private String shippingFullName;
    private String shippingPhone;
    private String shippingEmail;
    private String shippingLine1;
    private String shippingLine2;
    private String shippingCity;
    private String shippingState;
    private String shippingPostalCode;
    private String shippingCountry;

    // Flattened billing-address snapshot (nullable; resolved by the adapter).
    private String billingFullName;
    private String billingPhone;
    private String billingEmail;
    private String billingLine1;
    private String billingLine2;
    private String billingCity;
    private String billingState;
    private String billingPostalCode;
    private String billingCountry;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
