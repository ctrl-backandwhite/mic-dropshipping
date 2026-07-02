package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.out.InvoiceVerifyDtoOut;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicInvoiceVerifyControllerTest {

    @Mock
    OrderRepository orderRepository;
    @InjectMocks
    PublicInvoiceVerifyController controller;

    @Test
    void verify_returnsValidForExistingOrder() {
        Order o = Order.builder().orderNumber("NX-100").status(OrderStatus.PAID).placedAt(Instant.now()).build();
        when(orderRepository.findByOrderNumber("NX-100")).thenReturn(Optional.of(o));

        ResponseEntity<InvoiceVerifyDtoOut> resp = controller.verify("NX-100");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().isValid()).isTrue();
        assertThat(resp.getBody().getOrderNumber()).isEqualTo("NX-100");
        assertThat(resp.getBody().getStatus()).isEqualTo("PAID");
    }

    @Test
    void verify_returns404WhenNotFound() {
        when(orderRepository.findByOrderNumber("NX-UNKNOWN")).thenReturn(Optional.empty());

        ResponseEntity<InvoiceVerifyDtoOut> resp = controller.verify("NX-UNKNOWN");

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().isValid()).isFalse();
    }
}
