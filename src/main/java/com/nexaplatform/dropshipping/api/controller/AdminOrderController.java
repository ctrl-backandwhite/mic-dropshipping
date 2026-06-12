package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminOrderApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminOrderMapper;
import com.nexaplatform.dropshipping.api.mapper.PartnerOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin orders controller. Pure implementation of {@link AdminOrderApi}: injects the
 * use case and the DtoMappers; no business logic and no manual field mapping — it only
 * paginates the enriched models and projects them to the transport DtoOut.
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController implements AdminOrderApi {

    private final OrderUseCase orderUseCase;
    private final AdminOrderMapper adminOrderMapper;
    private final PartnerOrderDtoMapper partnerOrderDtoMapper;

    @Override
    public ResponseEntity<PageResponse<AdminOrderRowDtoOut>> list(String status, String q, int page, int size) {
        List<Order> all = orderUseCase.listAdminOrders(status, q);
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AdminOrderRowDtoOut> items = adminOrderMapper.toRows(all.subList(from, to));
        var pageable = PageRequest.of(page, Math.max(1, size));
        return ResponseEntity.ok(PageResponse.from(new PageImpl<>(items, pageable, total)));
    }

    @Override
    public ResponseEntity<AdminOrderDetailDtoOut> detail(UUID id, String lang) {
        return ResponseEntity.ok(adminOrderMapper.toDetail(orderUseCase.getAdminOrderDetail(id, lang)));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> forward(UUID id) {
        return ResponseEntity.ok(adminOrderMapper.toRow(orderUseCase.forwardOrder(id)));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> ship(UUID id) {
        return ResponseEntity.ok(adminOrderMapper.toRow(orderUseCase.shipOrder(id)));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> deliver(UUID id) {
        return ResponseEntity.ok(adminOrderMapper.toRow(orderUseCase.deliverOrder(id)));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> cancel(UUID id) {
        return ResponseEntity.ok(adminOrderMapper.toRow(orderUseCase.cancelOrder(id)));
    }

    @Override
    public ResponseEntity<AdminOrderRowDtoOut> refund(UUID id) {
        return ResponseEntity.ok(adminOrderMapper.toRow(orderUseCase.refundOrder(id)));
    }

    @Override
    public ResponseEntity<PartnerOrderDtoOut> createDemo() {
        return new ResponseEntity<>(partnerOrderDtoMapper.toDtoOut(orderUseCase.createDemoOrder()), HttpStatus.CREATED);
    }
}
