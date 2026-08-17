package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.application.service.FulfillmentService.ParcelItemView;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.ShipmentTrackingView;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingEventView;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Mapeo de las entidades de seguimiento a las vistas que consume la interfaz: los eventos del timeline,
 * el desglose por bulto y las incidencias de envío del panel.
 *
 * <p>Se declara aquí, con MapStruct, como el resto de fronteras de la API: construir estas vistas a mano
 * dentro del servicio y del controlador dejaba el mapeo repartido y fuera de la convención del proyecto.
 */
@Mapper(componentModel = "spring")
public interface TrackingViewMapper {

    /** Un paso del timeline tal cual se guardó (estado, descripción, ubicación y quién lo trajo). */
    TrackingEventView toEventView(OrderTrackingEventEntity event);

    List<TrackingEventView> toEventViews(List<OrderTrackingEventEntity> events);

    /**
     * Un bulto con sus propios pasos. Los eventos NO salen de la entidad —se filtran por bulto en el
     * servicio— así que se pasan aparte.
     */
    @Mapping(target = "events", source = "events")
    @Mapping(target = "sequenceNo", source = "shipment.sequenceNo")
    @Mapping(target = "carrier", source = "shipment.carrier")
    @Mapping(target = "trackingNumber", source = "shipment.trackingNumber")
    @Mapping(target = "status", source = "shipment.status")
    @Mapping(target = "weightGrams", source = "shipment.weightGrams")
    @Mapping(target = "estimatedDeliveryAt", source = "shipment.estimatedDeliveryAt")
    @Mapping(target = "items", source = "items")
    ShipmentTrackingView toShipmentView(OrderShipmentEntity shipment, List<TrackingEventView> events,
            List<ParcelItemView> items);

    /** Fila de la bandeja de incidencias: qué pedido, cuántos intentos y por qué se abandonó. */
    @Mapping(target = "orderId", source = "id")
    @Mapping(target = "attempts", source = "fulfillmentAttempts")
    @Mapping(target = "error", source = "fulfillmentError")
    @Mapping(target = "failedAt", source = "fulfillmentFailedAt")
    com.nexaplatform.dropshipping.api.controller.TrackingController.FailedFulfillmentView toFailedView(Order order);

    List<com.nexaplatform.dropshipping.api.controller.TrackingController.FailedFulfillmentView> toFailedViews(
            List<Order> orders);
}
