package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cuerpos de las peticiones a la YunExpress Open Platform, tipados.
 *
 * <p>Antes se armaban como {@code Map<String, Object>}: un nombre de campo mal escrito no lo detectaba
 * nadie hasta que el transportista rechazaba la guía en producción, y leer el payload obligaba a seguir
 * el orden de los {@code put}. Con records, el contrato queda a la vista y lo comprueba el compilador.
 *
 * <p>Los nombres van en {@code snake_case} como los espera la API, y los campos nulos NO se serializan:
 * mandar {@code null} en un opcional hace que su validador lo rechace.
 */
public final class YunExpressRequests {

    private YunExpressRequests() {
    }

    /** Alta de un envío: {@code POST /v1/order/package/create}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateShipment(@JsonProperty("product_code") String productCode,
            @JsonProperty("customer_order_number") String customerOrderNumber,
            @JsonProperty("weight_unit") String weightUnit, @JsonProperty("size_unit") String sizeUnit,
            @JsonProperty("sensitive_type") String sensitiveType, @JsonProperty("label_type") String labelType,
            @JsonProperty("packages") List<Parcel> packages, @JsonProperty("receiver") Receiver receiver,
            @JsonProperty("declaration_info") List<DeclarationLine> declarationInfo,
            @JsonProperty("customs_number") CustomsNumber customsNumber,
            @JsonProperty("extra_services") List<ExtraService> extraServices) {
    }

    /**
     * Servicio adicional del envío. El que importa aquí es {@code V1} (云途预缴, «prepago YunExpress»):
     * es como se le pide al transportista que liquide el IVA de la UE con SU número IOSS.
     *
     * <p>La tienda vende DDP —cobra el impuesto en el checkout y al cliente no le reclaman nada al
     * recibir—, y esa promesa solo se cumple si el envío lleva este extra: sin él el paquete se despacha
     * como si el impuesto no estuviera pagado y quien acaba pagándolo en destino es el cliente.
     */
    public record ExtraService(@JsonProperty("extra_code") String extraCode,
            @JsonProperty("extra_value") String extraValue) {
    }

    /** Medidas y peso del bulto. Las dimensiones son opcionales: no todo el catálogo las tiene. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parcel(@JsonProperty("weight") BigDecimal weight, @JsonProperty("length") BigDecimal length,
            @JsonProperty("width") BigDecimal width, @JsonProperty("height") BigDecimal height) {
    }

    /** Destinatario. La API pide nombre y apellidos por separado. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receiver(@JsonProperty("first_name") String firstName, @JsonProperty("last_name") String lastName,
            @JsonProperty("country_code") String countryCode, @JsonProperty("province") String province,
            @JsonProperty("city") String city, @JsonProperty("address_lines") List<String> addressLines,
            @JsonProperty("postal_code") String postalCode, @JsonProperty("phone_number") String phoneNumber,
            @JsonProperty("email") String email) {
    }

    /**
     * Una línea de la declaración aduanera. {@code nameLocal} (CName) es obligatorio y debe llevar
     * ideogramas: el transportista rechaza la guía si falta o si va en alfabeto latino.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeclarationLine(@JsonProperty("name_en") String nameEn, @JsonProperty("name_local") String nameLocal,
            @JsonProperty("quantity") int quantity, @JsonProperty("unit_price") BigDecimal unitPrice,
            @JsonProperty("unit_weight") BigDecimal unitWeight, @JsonProperty("currency") String currency,
            @JsonProperty("hs_code") String hsCode, @JsonProperty("material") String material,
            @JsonProperty("purpose") String purpose, @JsonProperty("sales_url") String salesUrl,
            @JsonProperty("sku_code") String skuCode) {
    }

    /** Identificadores fiscales del despacho. Solo se envía el IOSS cuando el pedido no supera el umbral. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomsNumber(@JsonProperty("ioss_code") String iossCode) {
    }

    /** Suscripción al push de trazabilidad: {@code POST /v1/track-service/subscribe-by-order}. */
    public record SubscribeTracking(@JsonProperty("waybill_numbers") List<String> waybillNumbers,
            @JsonProperty("subscribe_type") String subscribeType, @JsonProperty("query_type") List<String> queryType) {
    }

    /** Anulación de una guía: {@code POST /v1/order/cancel}. */
    public record CancelShipment(@JsonProperty("waybill_number") String waybillNumber) {
    }
}
