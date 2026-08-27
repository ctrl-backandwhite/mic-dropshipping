package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cuánto se devuelve cuando un pedido se cancela o se reembolsa.
 *
 * <p>El arancel fijo de la UE lo cobra el transportista al dar entrada al paquete y **no lo reintegra
 * jamás**: «我司已代收的临时固定关税均不予退还». Hasta ahora se devolvía el 100 % del total también en
 * pedidos ya enviados, así que ese importe salía del margen sin que nadie lo viera.
 *
 * <p>Las condiciones (migración v140) dicen exactamente esto y hay que cumplirlo: en desistimiento lo
 * asume el comercio —el artículo 13 de la Directiva 2011/83/UE obliga a devolver todo—, y solo se
 * descuenta cuando la devolución nace de una causa imputable al cliente. Antes de que el paquete entre
 * en el almacén no hay nada pagado, así que se devuelve íntegro.
 */
class RefundableAmountTest {

    private static Order pedido(OrderStatus status, int total, int customsCents) {
        Order o = new Order();
        o.setStatus(status);
        o.setTotalCents(total);
        o.setCustomsDutyCents(customsCents);
        return o;
    }

    @Test
    @DisplayName("antes de despachar se devuelve todo: el arancel aún no se ha pagado")
    void antesDeDespacharSeDevuelveTodo() {
        Order pendiente = pedido(OrderStatus.PAID, 5_000, 300);

        assertThat(RefundPolicy.refundableCents(pendiente, RefundPolicy.Reason.WITHDRAWAL)).isEqualTo(5_000);
        assertThat(RefundPolicy.refundableCents(pendiente, RefundPolicy.Reason.CUSTOMER_FAULT)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("en desistimiento se devuelve todo aunque el arancel esté perdido")
    void enDesistimientoLoAsumeElComercio() {
        // Directiva 2011/83/UE art. 13: hay que reembolsar todos los pagos recibidos. El arancel lo
        // perdemos igual, pero no se le puede descontar al consumidor.
        Order enviado = pedido(OrderStatus.SHIPPED, 5_000, 300);

        assertThat(RefundPolicy.refundableCents(enviado, RefundPolicy.Reason.WITHDRAWAL)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("si la devolución es por causa del cliente, el arancel no se reintegra")
    void porCausaDelClienteSeDescuentaElArancel() {
        // Rechaza el paquete, da una dirección incorrecta o la entrega falla por su parte.
        Order enviado = pedido(OrderStatus.SHIPPED, 5_000, 300);

        assertThat(RefundPolicy.refundableCents(enviado, RefundPolicy.Reason.CUSTOMER_FAULT)).isEqualTo(4_700);
    }

    @Test
    @DisplayName("un pedido entregado y devuelto por causa del cliente tampoco recupera el arancel")
    void entregadoTambienDescuenta() {
        Order entregado = pedido(OrderStatus.DELIVERED, 5_000, 300);

        assertThat(RefundPolicy.refundableCents(entregado, RefundPolicy.Reason.CUSTOMER_FAULT)).isEqualTo(4_700);
    }

    @Test
    @DisplayName("sin arancel cobrado no hay nada que descontar")
    void sinArancelNoCambiaNada() {
        Order fueraDeLaUe = pedido(OrderStatus.SHIPPED, 5_000, 0);

        assertThat(RefundPolicy.refundableCents(fueraDeLaUe, RefundPolicy.Reason.CUSTOMER_FAULT)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("el reembolso nunca sale negativo")
    void nuncaDevuelveNegativo() {
        // Defensa ante datos raros: un arancel mayor que el total no puede convertirse en un cargo.
        Order raro = pedido(OrderStatus.SHIPPED, 200, 300);

        assertThat(RefundPolicy.refundableCents(raro, RefundPolicy.Reason.CUSTOMER_FAULT)).isZero();
    }

    @Test
    @DisplayName("un pedido nulo no revienta")
    void toleraElPedidoNulo() {
        assertThat(RefundPolicy.refundableCents(null, RefundPolicy.Reason.WITHDRAWAL)).isZero();
    }
}
