package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminCreateOrderDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminImportOrdersDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminImportResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminOrderMapper;
import com.nexaplatform.dropshipping.api.mapper.PartnerOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del controlador de pedidos del admin: paginación defensiva y AISLAMIENTO POR FILA/ID en las
 * operaciones de lote (una fila mala nunca puede abortar el resto).
 */
@ExtendWith(MockitoExtension.class)
class Cov01AdminOrderControllerTest {

    private static final UUID ID_OK = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_KO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    OrderUseCase orderUseCase;
    @Mock
    AdminOrderMapper adminOrderMapper;
    @Mock
    PartnerOrderDtoMapper partnerOrderDtoMapper;
    @Mock
    OrderIndexer orderIndexer;

    @InjectMocks
    AdminOrderController controller;

    private static AdminCreateOrderDtoIn row(String email) {
        return new AdminCreateOrderDtoIn(email, null, null, null, List.of(), null);
    }

    /* ============ listado ============ */

    @Test
    void elListadoConservaElTotalDelCasoDeUsoAunqueLaPaginaTraigaMenosFilas() {
        // El total es el del filtro completo, no el de la página: el paginador del admin depende de él.
        when(orderUseCase.pageAdminOrders("PAID", "NX", 0, 20))
                .thenReturn(new OrderUseCase.OrderPage(List.of(Order.builder().build()), 0, 20, 137L));
        when(adminOrderMapper.toRows(anyList())).thenReturn(List.of(AdminOrderRowDtoOut.builder().build()));

        ResponseEntity<PageResponse<AdminOrderRowDtoOut>> resp = controller.list("PAID", "NX", 0, 20);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().totalElements()).isEqualTo(137L);
        assertThat(resp.getBody().items()).hasSize(1);
        assertThat(resp.getBody().page()).isZero();
        assertThat(resp.getBody().size()).isEqualTo(20);
    }

    @Test
    void unaPaginaNegativaOUnTamanoCeroNoRompenLaRespuesta() {
        // PageRequest.of rechaza páginas negativas y tamaños 0 con IllegalArgumentException: sin el
        // acotado, un caso de uso que devolviera size=0 (sin resultados) tumbaría el endpoint con un 500.
        when(orderUseCase.pageAdminOrders(null, null, -3, 0))
                .thenReturn(new OrderUseCase.OrderPage(List.of(), -3, 0, 0L));
        when(adminOrderMapper.toRows(anyList())).thenReturn(List.of());

        ResponseEntity<PageResponse<AdminOrderRowDtoOut>> resp = controller.list(null, null, -3, 0);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().page()).isZero();
        assertThat(resp.getBody().size()).isEqualTo(1);
        assertThat(resp.getBody().items()).isEmpty();
    }

    @Test
    void reindexarDevuelveCuantosPedidosSeIndexaron() {
        when(orderIndexer.reindexAll()).thenReturn(342);

        ResponseEntity<Map<String, Object>> resp = controller.reindex();

        assertThat(resp.getBody()).containsEntry("indexed", 342);
    }

    /* ============ transiciones individuales ============ */

    @Test
    void cadaTransicionDelegaEnSuOperacionDelCasoDeUso() {
        Order order = Order.builder().build();
        AdminOrderRowDtoOut rowOut = AdminOrderRowDtoOut.builder().build();
        when(orderUseCase.forwardOrder(ID_OK)).thenReturn(order);
        when(orderUseCase.shipOrder(ID_OK)).thenReturn(order);
        when(orderUseCase.deliverOrder(ID_OK)).thenReturn(order);
        when(orderUseCase.cancelOrder(ID_OK)).thenReturn(order);
        when(orderUseCase.refundOrder(ID_OK)).thenReturn(order);
        when(adminOrderMapper.toRow(order)).thenReturn(rowOut);

        assertThat(controller.forward(ID_OK).getBody()).isSameAs(rowOut);
        assertThat(controller.ship(ID_OK).getBody()).isSameAs(rowOut);
        assertThat(controller.deliver(ID_OK).getBody()).isSameAs(rowOut);
        assertThat(controller.cancel(ID_OK).getBody()).isSameAs(rowOut);
        assertThat(controller.refund(ID_OK).getBody()).isSameAs(rowOut);
    }

    @Test
    void crearUnPedidoManualDevuelve201() {
        Order order = Order.builder().orderNumber("NX-1").build();
        when(orderUseCase.createManualOrder(any(), any())).thenReturn(order);
        when(adminOrderMapper.toRow(order)).thenReturn(AdminOrderRowDtoOut.builder().build());

        ResponseEntity<AdminOrderRowDtoOut> resp = controller.create(row("ana@test.com"));

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void crearElPedidoDeDemostracionDevuelve201() {
        when(orderUseCase.createDemoOrder()).thenReturn(Order.builder().build());

        assertThat(controller.createDemo().getStatusCode().value()).isEqualTo(201);
    }

    /* ============ importación por filas ============ */

    @Test
    void unaFilaMalaNoAbortaLaImportacionYSeReportaConSuNumeroDeFila() {
        // DROP-690: importar 300 pedidos y perderlos todos por el error de uno era el comportamiento
        // anterior. El operador necesita saber EXACTAMENTE qué fila falló para corregirla.
        when(orderUseCase.createManualOrder(eq("ok1@test.com"), any()))
                .thenReturn(Order.builder().orderNumber("NX-1").build());
        when(orderUseCase.createManualOrder(eq("malo@test.com"), any()))
                .thenThrow(new BusinessException("El producto no existe"));
        when(orderUseCase.createManualOrder(eq("ok2@test.com"), any()))
                .thenReturn(Order.builder().orderNumber("NX-2").build());

        ResponseEntity<AdminImportResultDtoOut> resp = controller.importOrders(
                new AdminImportOrdersDtoIn(List.of(row("ok1@test.com"), row("malo@test.com"), row("ok2@test.com"))));

        AdminImportResultDtoOut body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imported()).isEqualTo(2);
        assertThat(body.failed()).isEqualTo(1);
        assertThat(body.created()).containsExactly("NX-1", "NX-2");
        assertThat(body.errors()).containsExactly("Fila 2: El producto no existe");
    }

    @Test
    void unaImportacionCompletaNoDevuelveErrores() {
        when(orderUseCase.createManualOrder(any(), any())).thenReturn(Order.builder().orderNumber("NX-1").build());

        AdminImportResultDtoOut body = controller.importOrders(new AdminImportOrdersDtoIn(List.of(row("a@test.com"))))
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.failed()).isZero();
        assertThat(body.errors()).isEmpty();
    }

    /* ============ acciones en lote ============ */

    @Test
    void unIdInvalidoEnUnLoteNoImpideQueLosDemasSeProcesen() {
        // El admin selecciona varias filas de la tabla; alguna puede estar en un estado que no admite la
        // transición. Cortar el lote dejaría el resultado a medias y sin saber por dónde iba.
        when(orderUseCase.shipOrder(ID_OK)).thenReturn(Order.builder().build());
        when(orderUseCase.shipOrder(ID_KO)).thenThrow(new BusinessException("El pedido no está pagado"));

        ResponseEntity<Map<String, Object>> resp = controller.bulkShip(List.of(ID_KO, ID_OK));

        assertThat(resp.getBody()).containsEntry("succeeded", 1).containsEntry("failed", 1);
        assertThat(errorsOf(resp)).containsExactly(ID_KO + ": El pedido no está pagado");
    }

    @Test
    void unLoteVacioNoFallaYNoTocaNadaDelCasoDeUso() {
        ResponseEntity<Map<String, Object>> resp = controller.bulkCancel(List.of());

        assertThat(resp.getBody()).containsEntry("succeeded", 0).containsEntry("failed", 0);
        assertThat(errorsOf(resp)).isEmpty();
    }

    @Test
    void cadaAccionEnLoteUsaSuPropiaTransicion() {
        Order order = Order.builder().build();
        when(orderUseCase.forwardOrder(ID_OK)).thenReturn(order);
        when(orderUseCase.deliverOrder(ID_OK)).thenReturn(order);
        when(orderUseCase.refundOrder(ID_OK)).thenReturn(order);
        when(orderUseCase.cancelOrder(ID_OK)).thenReturn(order);

        controller.bulkForward(List.of(ID_OK));
        controller.bulkDeliver(List.of(ID_OK));
        controller.bulkRefund(List.of(ID_OK));
        controller.bulkCancel(List.of(ID_OK));

        verify(orderUseCase).forwardOrder(ID_OK);
        verify(orderUseCase).deliverOrder(ID_OK);
        verify(orderUseCase).refundOrder(ID_OK);
        verify(orderUseCase).cancelOrder(ID_OK);
    }

    @Test
    void unPedidoQueNoExisteEnUnLoteSeReportaComoErrorDeEseIdSinTumbarLaLlamada() {
        when(orderUseCase.deliverOrder(ID_KO)).thenThrow(new NotFoundException("Pedido no encontrado"));

        ResponseEntity<Map<String, Object>> resp = controller.bulkDeliver(List.of(ID_KO));

        assertThat(resp.getBody()).containsEntry("succeeded", 0).containsEntry("failed", 1);
        assertThat(errorsOf(resp)).containsExactly(ID_KO + ": Pedido no encontrado");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorsOf(ResponseEntity<Map<String, Object>> resp) {
        assertThat(resp.getBody()).isNotNull();
        return (List<String>) resp.getBody().get("errors");
    }
}
