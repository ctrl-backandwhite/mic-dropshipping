package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminOrderApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import com.nexaplatform.dropshipping.application.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin orders controller. Pure implementation of {@link AdminOrderApi}:
 * no business logic and no manual mapping — delegates to {@link OrderService}
 * and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController implements AdminOrderApi {

    private final OrderService orderService;

    @Override
    public ResponseEntity<PageResponse<AdminOrderRowDtoOut>> list(String status, String q, int page, int size) {
        return ResponseEntity.ok(orderService.listAdminOrders(status, q, page, size));
    }

    @Override
    public ResponseEntity<AdminOrderDetailDtoOut> detail(UUID id) {
        return ResponseEntity.ok(orderService.getAdminOrderDetail(id));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> forward(UUID id) {
        return ResponseEntity.ok(orderService.forwardOrder(id));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> ship(UUID id) {
        return ResponseEntity.ok(orderService.shipOrder(id));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> deliver(UUID id) {
        return ResponseEntity.ok(orderService.deliverOrder(id));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> cancel(UUID id) {
        return ResponseEntity.ok(orderService.cancelOrder(id));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> refund(UUID id) {
        return ResponseEntity.ok(orderService.refundOrder(id));
    }

    @Override
    public ResponseEntity<PartnerOrderDtoOut> createDemo() {
        return new ResponseEntity<>(orderService.createDemoOrderDto(), HttpStatus.CREATED);
    }
}
