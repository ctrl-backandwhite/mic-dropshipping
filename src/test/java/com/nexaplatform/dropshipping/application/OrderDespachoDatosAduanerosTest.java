package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * «Enviar al proveedor» no avanza con la declaración aduanera incompleta.
 *
 * <p>Es el último punto en el que el administrador puede enterarse a tiempo: a partir de aquí la guía se
 * emite sola y lo que salga mal lo descubre el cliente. El pedido ya está cobrado, así que se rechaza la
 * transición con un 422 que dice QUÉ falta y de QUÉ producto —el administrador corrige el catálogo y
 * vuelve a pulsar—, en vez de despacharlo y dejar el problema para el transportista o la aduana.
 *
 * <p>No se bloquea antes, en el checkout: el dato que falta es del catálogo, nuestro, y cortarle la compra
 * a quien no tiene nada que ver con eso castiga al cliente por un fallo nuestro.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderDespachoDatosAduanerosTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    ProductRepository productRepository;
    @Mock
    WebhookDispatcherService webhooks;
    @Mock
    OrderIndexer orderIndexer;

    @InjectMocks
    OrderUseCaseImpl orderUseCase;

    private final UUID productId = UUID.randomUUID();

    private static ProductTranslationEntity traduccion(String idioma, String titulo) {
        ProductTranslationEntity t = new ProductTranslationEntity();
        t.setLanguage(idioma);
        t.setTitle(titulo);
        return t;
    }

    /** Producto con todo lo obligatorio; cada test le quita el dato que quiere provocar. */
    private ProductEntity producto() {
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setTitleZh("男士石英手表");
        p.setHsCode("9102190000");
        p.setWeightGrams(300);
        p.setBasePrice(new BigDecimal("12.50"));
        p.setTranslations(List.of(traduccion("en", "Men's quartz watch"), traduccion("zh", "男士石英手表")));
        when(productRepository.findById(productId)).thenReturn(Optional.of(p));
        return p;
    }

    private Order pedidoPagado() {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setQuantity(1);
        item.setUnitPriceCents(1250);
        item.setSkuSnapshot("SKU-1");
        item.setTitleSnapshot("Reloj de pulsera para hombre");
        Order order = Order.builder().status(OrderStatus.PAID).orderNumber("NX-1").build();
        order.setId(UUID.randomUUID());
        order.setItems(List.of(item));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        return order;
    }

    @Test
    @DisplayName("sin partida arancelaria el despacho se rechaza diciendo qué falta y de qué producto")
    void sinPartidaArancelariaSeRechazaElDespacho() {
        ProductEntity p = producto();
        p.setHsCode(null);
        Order pedido = pedidoPagado();

        assertThatThrownBy(() -> orderUseCase.forwardOrder(pedido.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INCOMPLETE_CUSTOMS_DATA");
                    assertThat(ex.getMessage()).contains("partida arancelaria (HSCode)")
                            .contains("Reloj de pulsera para hombre");
                });
    }

    @Test
    @DisplayName("el pedido rechazado se queda como estaba, listo para reintentarlo tras corregirlo")
    void elPedidoRechazadoSeQuedaComoEstaba() {
        // Lo importante para el cliente: su pedido pagado no queda en un estado intermedio del que haya
        // que rescatarlo a mano. Sigue PAGADO y el mismo botón vuelve a funcionar en cuanto se corrija.
        ProductEntity p = producto();
        p.setHsCode(null);
        Order pedido = pedidoPagado();

        assertThatThrownBy(() -> orderUseCase.forwardOrder(pedido.getId()))
                .isInstanceOf(BusinessException.class);

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(pedido.getForwardedAt()).isNull();
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("se enumeran de golpe todos los datos que faltan del pedido")
    void seEnumeranDeGolpeTodosLosDatosQueFaltan() {
        ProductEntity p = producto();
        p.setHsCode(null);
        p.setWeightGrams(null);
        p.setTitleZh("Reloj de pulsera");           // sin ideogramas: para YunExpress es como no tenerlo
        p.setTranslations(List.of(traduccion("zh", "Reloj de pulsera")));
        Order pedido = pedidoPagado();

        assertThatThrownBy(() -> orderUseCase.forwardOrder(pedido.getId()))
                .hasMessageContaining("partida arancelaria (HSCode)")
                .hasMessageContaining("peso unitario (UnitWeight)")
                .hasMessageContaining("nombre en chino (CName)")
                .hasMessageContaining("nombre en inglés (EName)");
    }

    @Test
    @DisplayName("con los datos completos el pedido se despacha con normalidad")
    void conLosDatosCompletosElPedidoSeDespacha() {
        producto();
        Order pedido = pedidoPagado();

        assertThatCode(() -> orderUseCase.forwardOrder(pedido.getId())).doesNotThrowAnyException();

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.FORWARDED);
    }

    @Test
    @DisplayName("una línea sin producto en catálogo no impide despachar")
    void unaLineaSinProductoEnCatalogoNoImpideDespachar() {
        // Los pedidos manuales y los de productos ya retirados no tienen ficha que consultar. Bloquearlos
        // aquí dejaría pedidos cobrados sin salida por un dato que ya no existe en ningún sitio; ese caso
        // lo sigue cubriendo el corte de la transmisión, que mira la declaración real.
        Order pedido = pedidoPagado();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatCode(() -> orderUseCase.forwardOrder(pedido.getId())).doesNotThrowAnyException();

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.FORWARDED);
    }
}
