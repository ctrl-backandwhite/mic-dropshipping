package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeOrderApi;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminOrderMapper;
import com.nexaplatform.dropshipping.api.mapper.MeOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @Override
    public ResponseEntity<MeOrderDetailDtoOut> checkout(Authentication auth, MeCheckoutDtoIn req, String idem) {
        UUID userId = UUID.fromString(auth.getName());
        return new ResponseEntity<>(meOrderDtoMapper.toDetailDtoOut(orderUseCase.checkout(userId, req, idem)),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<List<MeOrderRowDtoOut>> list(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        List<com.nexaplatform.dropshipping.domain.model.Order> orders = orderUseCase.listMyOrders(userId);
        List<MeOrderRowDtoOut> rows = adminOrderMapper.toMeRows(orders);
        // El total de la fila se formatea en la moneda activa EXACTAMENTE como en el detalle (mismo
        // método del mapper), para que lista y detalle muestren el mismo importe. toMeRows preserva el orden.
        List<MeOrderRowDtoOut> out = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(rows.get(i).toBuilder().totalFormatted(meOrderDtoMapper.formatOrderTotal(orders.get(i))).build());
        }
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<MeOrderDetailDtoOut> detail(Authentication auth, UUID id, String lang) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(meOrderDtoMapper.toDetailDtoOut(orderUseCase.getMyOrderDetail(userId, id, lang)));
    }
}
