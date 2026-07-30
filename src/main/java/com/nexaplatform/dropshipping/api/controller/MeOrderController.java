package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeOrderApi;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminOrderMapper;
import com.nexaplatform.dropshipping.api.mapper.MeOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated user's orders controller. Pure implementation of {@link MeOrderApi}:
 * injects the use case and DtoMappers; no business logic and no manual mapping.
 */
@RestController
@RequestMapping("/api/me/orders")
@RequiredArgsConstructor
public class MeOrderController implements MeOrderApi {

    private final OrderUseCase orderUseCase;
    private final MeOrderDtoMapper meOrderDtoMapper;
    private final AdminOrderMapper adminOrderMapper;
    private final PaymentJpaRepositoryAdapter paymentRepository;

    @Override
    public ResponseEntity<MeOrderDetailDtoOut> checkout(Authentication auth, MeCheckoutDtoIn req, String idem) {
        UUID userId = UUID.fromString(auth.getName());
        return new ResponseEntity<>(meOrderDtoMapper.toDetailDtoOut(orderUseCase.checkout(userId, req, idem)),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<MeOrderRowDtoOut>> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        List<Order> orders = orderUseCase.listMyOrders(userId);
        List<MeOrderRowDtoOut> rows = adminOrderMapper.toMeRows(orders);
        // El total de la fila se formatea en la moneda activa EXACTAMENTE como en el detalle (mismo
        // método del mapper), para que lista y detalle muestren el mismo importe. toMeRows preserva el orden.
        List<MeOrderRowDtoOut> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Order order = orders.get(i);
            out.add(rows.get(i).toBuilder()
                    .totalFormatted(meOrderDtoMapper.formatOrderTotal(order))
                    .paymentMethod(resolvePaymentMethod(order.getId())) // para el botón de cancelar de la lista
                    .build());
        }
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<MeOrderDetailDtoOut> detail(Authentication auth, UUID id, String lang) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(withPaymentMethod(
                meOrderDtoMapper.toDetailDtoOut(orderUseCase.getMyOrderDetail(userId, id, lang)), id));
    }

    @Override
    public ResponseEntity<MeOrderDetailDtoOut> cancel(Authentication auth, UUID id, String lang,
            boolean refundToWallet) {
        UUID userId = UUID.fromString(auth.getName());
        orderUseCase.cancelMyOrder(userId, id, refundToWallet);
        return ResponseEntity.ok(withPaymentMethod(
                meOrderDtoMapper.toDetailDtoOut(orderUseCase.getMyOrderDetail(userId, id, lang)), id));
    }

    /**
     * Añade al detalle el método de pago ORIGINAL (CARD/PAYPAL/USDT) del pago satisfactorio; si no hubo
     * pago externo (se pagó con saldo), es WALLET. El front lo usa para decidir a dónde ofrecer el reembolso.
     */
    private MeOrderDetailDtoOut withPaymentMethod(MeOrderDetailDtoOut dto, UUID orderId) {
        return dto.toBuilder().paymentMethod(resolvePaymentMethod(orderId)).build();
    }

    /** Método de pago ORIGINAL (CARD/PAYPAL/USDT) del pago satisfactorio; WALLET si no hubo pago externo. */
    private String resolvePaymentMethod(UUID orderId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .map(PaymentEntity::getMethod).filter(Objects::nonNull).map(PaymentMethod::name)
                .findFirst().orElse("WALLET");
    }
}
