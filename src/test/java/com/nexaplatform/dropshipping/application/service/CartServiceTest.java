package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CartItemDto;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CartItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CartItemJpaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del carrito sincronizado: guardar FIJA la cantidad, el merge la SUMA, el MOQ del catálogo es el
 * suelo, el vaciado se acota al usuario y una referencia a producto inexistente se rechaza.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    @Mock
    CartItemJpaRepository repo;
    @Mock
    ProductRepository productRepository;

    private CartService service;
    private UUID userId;
    private UUID productId;
    private UUID variantId;

    @BeforeEach
    void setUp() {
        service = new CartService(repo, productRepository);
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        catalogoConMoq(1);
        when(repo.findByUserIdOrderByCreatedAtAscIdAsc(any())).thenReturn(List.of());
    }

    /** El catálogo responde a cualquier id con un producto del MOQ indicado. */
    private void catalogoConMoq(int moq) {
        ProductEntity producto = ProductEntity.builder().slug("camisa-roja").moq(moq).build();
        when(productRepository.findById(any())).thenReturn(Optional.of(producto));
    }

    private CartItemDto dto(UUID product, UUID variant, int qty) {
        return new CartItemDto(product, variant, "SKU1", "camisa-roja", "Camisa roja", "http://img/1.jpg",
                "Color: Rojo", new BigDecimal("10.00"), "EUR", qty, 2, new BigDecimal("12.00"), "EUR", "€");
    }

    private CartItemEntity lineaGuardada(int cantidad) {
        return CartItemEntity.builder()
                .userId(userId).productId(productId).variantId(variantId).quantity(cantidad).title("viejo").build();
    }

    private CartItemEntity capturarGuardado() {
        ArgumentCaptor<CartItemEntity> captor = ArgumentCaptor.forClass(CartItemEntity.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("una línea nueva se persiste con su cantidad, su variante y el usuario de la sesión")
    void guardarUnaLineaNuevaLaPersisteConSuCantidadYUsuario() {
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId)).thenReturn(Optional.empty());

        service.upsert(userId, dto(productId, variantId, 3));

        CartItemEntity guardada = capturarGuardado();
        assertThat(guardada.getUserId()).isEqualTo(userId);
        assertThat(guardada.getProductId()).isEqualTo(productId);
        assertThat(guardada.getVariantId()).isEqualTo(variantId);
        assertThat(guardada.getQuantity()).isEqualTo(3);
        assertThat(guardada.getTitle()).isEqualTo("Camisa roja");
    }

    /**
     * La diferencia con "guardar para más tarde": aquí guardar FIJA la cantidad. Si sumara, la pantalla
     * del carrito no podría bajar de 5 a 2 unidades y un reintento por red inestable duplicaría el pedido.
     */
    @Test
    @DisplayName("guardar sobre una línea existente FIJA la cantidad (no la suma) y refresca el snapshot")
    void guardarSobreUnaLineaExistenteFijaLaCantidad() {
        CartItemEntity existente = lineaGuardada(5);
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId))
                .thenReturn(Optional.of(existente));

        service.upsert(userId, dto(productId, variantId, 2));

        verify(repo).save(existente);
        assertThat(existente.getQuantity()).isEqualTo(2);
        assertThat(existente.getTitle()).isEqualTo("Camisa roja");
    }

    /**
     * Quien llenó la cesta sin sesión y luego entra no puede perder unidades por ninguno de los dos lados:
     * 2 en el navegador + 3 en la cuenta = 5.
     */
    @Test
    @DisplayName("el merge SUMA las cantidades de la misma línea")
    void elMergeSumaLasCantidades() {
        CartItemEntity existente = lineaGuardada(3);
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId))
                .thenReturn(Optional.of(existente));

        service.merge(userId, List.of(dto(productId, variantId, 2)));

        assertThat(existente.getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("el merge sube cada línea válida e ignora los nulos de la lista")
    void elMergeSubeCadaLineaValidaEIgnoraLosNulos() {
        when(repo.findByUserIdAndProductIdAndVariantId(any(), any(), any())).thenReturn(Optional.empty());

        service.merge(userId, Arrays.asList(dto(productId, null, 1), null, dto(UUID.randomUUID(), variantId, 2)));

        verify(repo, times(2)).save(any());
    }

    @Test
    @DisplayName("una lista de merge nula no toca nada")
    void elMergeNuloNoPersisteNada() {
        service.merge(userId, null);

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("la cantidad nunca baja del MOQ del catálogo")
    void laCantidadNuncaBajaDelMoq() {
        catalogoConMoq(10);
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId)).thenReturn(Optional.empty());

        service.upsert(userId, dto(productId, variantId, 4));

        assertThat(capturarGuardado().getQuantity()).isEqualTo(10);
    }

    /**
     * El MOQ que se devuelve al cliente es el del CATÁLOGO, no el que venía en el cuerpo (2 en el dto):
     * el selector de unidades de la web y el de la app tienen que enseñar el mismo mínimo real.
     */
    @Test
    @DisplayName("el MOQ devuelto es el del catálogo, no el que manda el cliente")
    void elMoqDevueltoEsElDelCatalogo() {
        catalogoConMoq(6);
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId)).thenReturn(Optional.empty());

        service.upsert(userId, dto(productId, variantId, 6));

        assertThat(capturarGuardado().getMoq()).isEqualTo(6);
    }

    /** Un producto sin mínimo declarado (moq 0) no puede dejar una línea de cero unidades. */
    @Test
    @DisplayName("sin MOQ declarado el suelo sigue siendo una unidad")
    void sinMoqElSueloEsUnaUnidad() {
        catalogoConMoq(0);
        when(repo.findByUserIdAndProductIdAndVariantId(any(), any(), any())).thenReturn(Optional.empty());

        service.merge(userId, List.of(dto(productId, variantId, 0)));

        assertThat(capturarGuardado().getQuantity()).isEqualTo(1);
    }

    /**
     * Un desbordamiento de cantidad ya provocó un cobro incorrecto en el checkout: sumar en int habría
     * dado negativo. La suma se hace en long y el tope duro recorta.
     */
    @Test
    @DisplayName("una cantidad desmesurada en el merge se recorta al tope, nunca desborda a negativo")
    void unaCantidadDesmesuradaSeRecortaAlTope() {
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId))
                .thenReturn(Optional.of(lineaGuardada(100_000)));

        service.merge(userId, List.of(dto(productId, variantId, Integer.MAX_VALUE)));

        assertThat(capturarGuardado().getQuantity()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("un producto que ya no está en el catálogo se rechaza y no persiste nada")
    void guardarUnProductoInexistenteFallaYNoPersiste() {
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(userId, dto(productId, variantId, 1)))
                .isInstanceOf(NotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("quitar una línea borra por usuario, producto y variante")
    void quitarUnaLineaBorraPorUsuarioProductoYVariante() {
        service.remove(userId, productId, variantId);

        verify(repo).deleteByUserIdAndProductIdAndVariantId(userId, productId, variantId);
    }

    @Test
    @DisplayName("vaciar la cesta borra SOLO la del usuario y devuelve la lista vacía")
    void vaciarBorraSoloLaDelUsuario() {
        assertThat(service.clear(userId)).isEmpty();

        verify(repo).deleteByUserId(userId);
    }

    // ------------------------------------------------- vaciado de lo comprado al quedar el pedido PAGADO

    /** Pedido del {@code userId} de la clase con las líneas indicadas. */
    private Order pedido(OrderItem... lineas) {
        Order o = Order.builder().userId(userId).status(OrderStatus.PAID).items(Arrays.asList(lineas)).build();
        o.setId(UUID.randomUUID());
        return o;
    }

    private static OrderItem linea(UUID producto, UUID variante) {
        return OrderItem.builder().productId(producto).variantId(variante).quantity(1).build();
    }

    /**
     * Lo que se compra desaparece de la cesta; lo que no se compró se queda. Quien tramita solo una parte
     * de su cesta no puede perder el resto — y como el checkout suele llevarlo todo, lo habitual será que
     * la cesta quede vacía.
     */
    @Test
    @DisplayName("un pedido pagado saca de la cesta SOLO sus líneas, no la cesta entera")
    void elPedidoPagadoSacaDeLaCestaSoloSusLineas() {
        UUID otroProducto = UUID.randomUUID();

        service.removePurchased(pedido(linea(productId, variantId), linea(otroProducto, null)));

        verify(repo).deleteByUserIdAndProductIdAndVariantId(userId, productId, variantId);
        verify(repo).deleteByUserIdAndProductIdAndVariantId(userId, otroProducto, null);
        // La cesta entera NO se borra: el resto de líneas sobrevive al pedido.
        verify(repo, never()).deleteByUserId(any());
    }

    /**
     * El usuario sale del PEDIDO, nunca de un parámetro de fuera: es lo que impide vaciar la cesta de
     * otra persona conociendo su identificador.
     */
    @Test
    @DisplayName("se borra por el usuario DEL PEDIDO, no por ningún otro")
    void seBorraPorElUsuarioDelPedido() {
        UUID duenoDelPedido = UUID.randomUUID();
        Order o = Order.builder().userId(duenoDelPedido).items(List.of(linea(productId, variantId))).build();

        service.removePurchased(o);

        verify(repo).deleteByUserIdAndProductIdAndVariantId(duenoDelPedido, productId, variantId);
        verify(repo, never()).deleteByUserIdAndProductIdAndVariantId(eq(userId), any(), any());
    }

    /**
     * Un webhook repetido vuelve a pasar por aquí: borrar una línea que ya no está es un no-op, así que
     * la segunda vuelta no falla ni deja la cesta en un estado raro.
     */
    @Test
    @DisplayName("repetir el vaciado del mismo pedido no falla (webhook duplicado)")
    void repetirElVaciadoDelMismoPedidoNoFalla() {
        Order o = pedido(linea(productId, variantId));

        service.removePurchased(o);
        service.removePurchased(o);

        verify(repo, times(2)).deleteByUserIdAndProductIdAndVariantId(userId, productId, variantId);
    }

    /**
     * Casos en los que no hay nada que quitar: pedido de partner/API sin dueño, pedido sin líneas, línea
     * sin producto o quien compró desde un cliente antiguo y no tiene cesta en el servidor. Ninguno puede
     * reventar el cobro que acaba de completarse.
     */
    @Test
    @DisplayName("sin pedido, sin dueño, sin líneas o con líneas rotas no se toca la cesta")
    void sinPedidoSinDuenoOSinLineasNoSeTocaLaCesta() {
        service.removePurchased(null);
        service.removePurchased(Order.builder().items(List.of(linea(productId, variantId))).build());
        service.removePurchased(Order.builder().userId(userId).items(null).build());
        service.removePurchased(Order.builder().userId(userId).items(List.of()).build());
        service.removePurchased(pedido(linea(null, variantId)));

        verify(repo, never()).deleteByUserIdAndProductIdAndVariantId(any(), any(), any());
        verify(repo, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("listar mapea las entidades a su dto")
    void listarMapeaLasEntidadesAsuDto() {
        CartItemEntity entity = CartItemEntity.builder()
                .userId(userId).productId(productId).variantId(variantId).quantity(4)
                .slug("camisa-roja").title("Camisa roja").unitPriceSource(new BigDecimal("10.00"))
                .sourceCurrency("EUR").build();
        when(repo.findByUserIdOrderByCreatedAtAscIdAsc(userId)).thenReturn(List.of(entity));

        List<CartItemDto> out = service.list(userId);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).productId()).isEqualTo(productId);
        assertThat(out.get(0).variantId()).isEqualTo(variantId);
        assertThat(out.get(0).quantity()).isEqualTo(4);
        assertThat(out.get(0).title()).isEqualTo("Camisa roja");
    }
}
