package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminOrderMapper;
import com.nexaplatform.dropshipping.api.mapper.MeOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pedidos del usuario autenticado.
 *
 * <p>Dos reglas que ya han dado problemas en producción: el importe de la LISTA tiene que salir del
 * mismo formateador que el DETALLE (si no, el mismo pedido se ve con dos importes distintos), y el
 * método de pago que se devuelve es el del pago SATISFACTORIO — es lo que decide a dónde se ofrece el
 * reembolso al cancelar, y devolver a una tarjeta que no llegó a cobrarse sería devolver dinero a nadie.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov04MeOrderControllerTest {

    @Mock
    OrderUseCase orderUseCase;
    @Mock
    MeOrderDtoMapper meOrderDtoMapper;
    @Mock
    AdminOrderMapper adminOrderMapper;
    @Mock
    PaymentJpaRepositoryAdapter paymentRepository;
    @Mock
    com.nexaplatform.dropshipping.application.service.SupplierPurchaseService supplierPurchaseService;

    @InjectMocks
    MeOrderController controller;

    private UUID userId;
    private UUID orderId;
    private Order pedido;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        pedido = Order.builder().id(orderId).orderNumber("NX-100").userId(userId).build();
        auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        when(meOrderDtoMapper.toDetailDtoOut(any(Order.class)))
                .thenReturn(MeOrderDetailDtoOut.builder().id(orderId).orderNumber("NX-100").build());
    }

    private static PaymentEntity pago(PaymentMethod metodo, PaymentStatus estado) {
        PaymentEntity p = new PaymentEntity();
        p.setMethod(metodo);
        p.setStatus(estado);
        return p;
    }

    @Test
    void unPedidoNuevoSeCreaConCodigo201() {
        MeCheckoutDtoIn req = new MeCheckoutDtoIn();
        when(orderUseCase.checkout(userId, req, "idem-1")).thenReturn(pedido);

        ResponseEntity<MeOrderDetailDtoOut> resp = controller.checkout(auth, req, "idem-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void elTotalDeLaListaSeFormateaConElMismoMetodoQueElDetalle() {
        // Lista y detalle mostraban importes distintos porque la fila traía el total sin convertir.
        when(orderUseCase.listMyOrders(userId)).thenReturn(List.of(pedido));
        when(adminOrderMapper.toMeRows(List.of(pedido)))
                .thenReturn(List.of(MeOrderRowDtoOut.builder().id(orderId).totalFormatted("SIN FORMATO")
                        .build()));
        when(meOrderDtoMapper.formatOrderTotal(pedido)).thenReturn("19,22 €");

        ResponseEntity<List<MeOrderRowDtoOut>> resp = controller.list(auth);

        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().getFirst().getTotalFormatted()).isEqualTo("19,22 €");
    }

    @Test
    void laListaDiceQuePedidosSePuedenCancelarTodavia() {
        // No se deduce del estado: un pedido PAGADO deja de poder cancelarse en cuanto se compra el
        // género en 1688, y esa compra avanza en su propio tablero sin mover el estado del pedido. Si la
        // fila no lo dijera, la pantalla ofrecería un botón que el servidor va a rechazar.
        UUID compradoId = UUID.randomUUID();
        Order comprado = Order.builder().id(compradoId).orderNumber("NX-300").userId(userId)
                .status(OrderStatus.PAID).build();
        pedido.setStatus(OrderStatus.PAID);
        when(orderUseCase.listMyOrders(userId)).thenReturn(List.of(pedido, comprado));
        when(adminOrderMapper.toMeRows(List.of(pedido, comprado))).thenReturn(List.of(
                MeOrderRowDtoOut.builder().id(orderId).build(),
                MeOrderRowDtoOut.builder().id(compradoId).build()));
        when(supplierPurchaseService.ordersAlreadyBought(List.of(orderId, compradoId)))
                .thenReturn(java.util.Set.of(compradoId));

        List<MeOrderRowDtoOut> filas = controller.list(auth).getBody();

        assertThat(filas).extracting(MeOrderRowDtoOut::isCancellable).containsExactly(true, false);
    }

    @Test
    void unPedidoQueYaAvanzoNoSeAnunciaComoCancelable() {
        // Enviado al proveedor: aunque no haya compra registrada, el estado ya lo impide.
        pedido.setStatus(OrderStatus.FORWARDED);
        when(orderUseCase.listMyOrders(userId)).thenReturn(List.of(pedido));
        when(adminOrderMapper.toMeRows(List.of(pedido)))
                .thenReturn(List.of(MeOrderRowDtoOut.builder().id(orderId).build()));

        assertThat(controller.list(auth).getBody().getFirst().isCancellable()).isFalse();
    }

    @Test
    void cadaFilaSeEmparejaConSuPedidoRespetandoElOrden() {
        // toMeRows conserva el orden: si se cruzaran, cada pedido mostraría el importe de otro.
        UUID otroId = UUID.randomUUID();
        Order otro = Order.builder().id(otroId).orderNumber("NX-200").userId(userId).build();
        when(orderUseCase.listMyOrders(userId)).thenReturn(List.of(pedido, otro));
        when(adminOrderMapper.toMeRows(List.of(pedido, otro))).thenReturn(List.of(
                MeOrderRowDtoOut.builder().id(orderId).build(),
                MeOrderRowDtoOut.builder().id(otroId).build()));
        when(meOrderDtoMapper.formatOrderTotal(pedido)).thenReturn("10,00 €");
        when(meOrderDtoMapper.formatOrderTotal(otro)).thenReturn("20,00 €");
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId))
                .thenReturn(List.of(pago(PaymentMethod.CARD, PaymentStatus.SUCCEEDED)));

        List<MeOrderRowDtoOut> filas = controller.list(auth).getBody();

        assertThat(filas).extracting(MeOrderRowDtoOut::getId).containsExactly(orderId, otroId);
        assertThat(filas).extracting(MeOrderRowDtoOut::getTotalFormatted)
                .containsExactly("10,00 €", "20,00 €");
        // El pedido sin pago externo se paga con saldo: el botón de cancelar debe ofrecer la wallet.
        assertThat(filas).extracting(MeOrderRowDtoOut::getPaymentMethod).containsExactly("CARD", "WALLET");
    }

    @Test
    void elDetalleDevuelveElMetodoDelPagoQueDeVerdadSeCobro() {
        when(orderUseCase.getMyOrderDetail(userId, orderId, "es")).thenReturn(pedido);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId))
                .thenReturn(List.of(pago(PaymentMethod.PAYPAL, PaymentStatus.SUCCEEDED)));

        ResponseEntity<MeOrderDetailDtoOut> resp = controller.detail(auth, orderId, "es");

        assertThat(resp.getBody().getPaymentMethod()).isEqualTo("PAYPAL");
    }

    @Test
    void unIntentoDePagoFallidoNoCuentaComoMetodoDeReembolso() {
        // Reembolsar a una tarjeta que nunca llegó a cobrarse sería devolver dinero a ninguna parte.
        when(orderUseCase.getMyOrderDetail(userId, orderId, "es")).thenReturn(pedido);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId))
                .thenReturn(List.of(pago(PaymentMethod.CARD, PaymentStatus.FAILED)));

        assertThat(controller.detail(auth, orderId, "es").getBody().getPaymentMethod()).isEqualTo("WALLET");
    }

    @Test
    void sinNingunPagoRegistradoElPedidoSeConsideraPagadoConSaldo() {
        when(orderUseCase.getMyOrderDetail(userId, orderId, "es")).thenReturn(pedido);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of());

        assertThat(controller.detail(auth, orderId, "es").getBody().getPaymentMethod()).isEqualTo("WALLET");
    }

    @Test
    void cancelarPrimeroAnulaYDespuesReleeElDetalleYaActualizado() {
        // Al revés devolvería el estado anterior a la cancelación y el front pintaría el pedido vivo.
        when(orderUseCase.getMyOrderDetail(userId, orderId, "es")).thenReturn(pedido);

        controller.cancel(auth, orderId, "es", true);

        InOrder orden = inOrder(orderUseCase);
        orden.verify(orderUseCase).cancelMyOrder(userId, orderId, true);
        orden.verify(orderUseCase).getMyOrderDetail(userId, orderId, "es");
    }

    @Test
    void alCancelarTambienSeDevuelveElMetodoDePagoParaElReembolso() {
        when(orderUseCase.getMyOrderDetail(userId, orderId, null)).thenReturn(pedido);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId))
                .thenReturn(List.of(pago(PaymentMethod.USDT, PaymentStatus.SUCCEEDED)));

        ResponseEntity<MeOrderDetailDtoOut> resp = controller.cancel(auth, orderId, null, false);

        assertThat(resp.getBody().getPaymentMethod()).isEqualTo("USDT");
    }

    @Test
    void unPagoSinMetodoRegistradoNoSeOfreceComoDestinoDelReembolso() {
        when(orderUseCase.getMyOrderDetail(userId, orderId, "es")).thenReturn(pedido);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId))
                .thenReturn(List.of(pago(null, PaymentStatus.SUCCEEDED)));

        assertThat(controller.detail(auth, orderId, "es").getBody().getPaymentMethod()).isEqualTo("WALLET");
    }

    @Test
    void unaListaVaciaDePedidosNoRompeElListado() {
        when(orderUseCase.listMyOrders(userId)).thenReturn(List.of());
        when(adminOrderMapper.toMeRows(List.of())).thenReturn(List.of());

        assertThat(controller.list(auth).getBody()).isEmpty();
    }
}
