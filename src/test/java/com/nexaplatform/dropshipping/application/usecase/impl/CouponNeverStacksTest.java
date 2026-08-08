package com.nexaplatform.dropshipping.application.usecase.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regla de los descuentos al cobrar: gana el mayor, nunca se suman.
 *
 * <p>Estas cuentas son las mismas que hace la vista previa del checkout. Si dejan de coincidir, al
 * cliente se le enseña un total y se le cobra otro.
 */
class CouponNeverStacksTest {

    /** Carrito de 100,00 € de tarifa, ya rebajado un 30 % en las líneas (queda en 70,00 €). */
    private static final int TARIFA = 10_000;
    private static final int REBAJADO = 7_000;

    @Test
    @DisplayName("un cupón peor que la rebaja ya aplicada no descuenta nada")
    void cuponPeorNoDescuenta() {
        // 10 % de 100,00 € = 10,00 €, menos que los 30,00 € que la rebaja ya quitó.
        assertThat(OrderUseCaseImpl.couponExtraDiscountCents(1_000, TARIFA, REBAJADO, 0)).isZero();
    }

    @Test
    @DisplayName("un cupón mejor solo cobra la diferencia, no se suma a la rebaja")
    void cuponMejorCobraLaDiferencia() {
        // 50 % de 100,00 € = 50,00 €. La rebaja ya quitó 30,00 €, así que faltan 20,00 €.
        assertThat(OrderUseCaseImpl.couponExtraDiscountCents(5_000, TARIFA, REBAJADO, 0)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("un cupón igual a la rebaja no añade nada")
    void empateNoAniade() {
        assertThat(OrderUseCaseImpl.couponExtraDiscountCents(3_000, TARIFA, REBAJADO, 0)).isZero();
    }

    @Test
    @DisplayName("el cupón también compite con el descuento de referido")
    void compiteConElReferido() {
        // Referido de 40,00 € contra un cupón de 35,00 €: gana el referido y el cupón no entra.
        assertThat(OrderUseCaseImpl.couponExtraDiscountCents(3_500, TARIFA, REBAJADO, 4_000)).isZero();
        // Con el cupón por encima del referido, sí entra y descuenta la diferencia sobre la rebaja.
        assertThat(OrderUseCaseImpl.couponExtraDiscountCents(4_500, TARIFA, REBAJADO, 4_000)).isEqualTo(1_500);
    }

    @Test
    @DisplayName("sin rebaja previa el cupón se cobra entero")
    void sinRebajaPreviaSeCobraEntero() {
        assertThat(OrderUseCaseImpl.couponExtraDiscountCents(1_500, TARIFA, TARIFA, 0)).isEqualTo(1_500);
    }
}
