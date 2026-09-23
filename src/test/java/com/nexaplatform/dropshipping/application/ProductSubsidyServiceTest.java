package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.ProductSubsidyService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cómo se compone la bolsa de subvención a partir de los importes que el admin asigna al producto.
 *
 * <p>Lo que fijan estas pruebas, y que es justo lo que distingue esta bolsa de la que se calculaba
 * antes desde el margen: se cuenta UNA VEZ POR PRODUCTO, no por unidad. El proveedor manda un solo
 * bulto tenga el cliente una unidad o cinco.
 */
class ProductSubsidyServiceTest {

    private CurrencyRateService currencyService;
    private ProductSubsidyService service;

    @BeforeEach
    void setUp() {
        currencyService = mock(CurrencyRateService.class);
        // 1 CNY = 0,50 USD, para que las cuentas se lean de un vistazo.
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("0.5")));
        service = new ProductSubsidyService(currencyService);
    }

    @Test
    void lista_vacia_da_dos_bolsas_a_cero() {
        ProductSubsidyService.Bags bags = service.bagsFor(List.of());

        assertThat(bags.shippingCents()).isZero();
        assertThat(bags.dutyCents()).isZero();
    }

    @Test
    void lista_nula_da_dos_bolsas_a_cero() {
        ProductSubsidyService.Bags bags = service.bagsFor(null);

        assertThat(bags.shippingCents()).isZero();
        assertThat(bags.dutyCents()).isZero();
    }

    @Test
    void el_mismo_producto_repetido_aporta_una_sola_vez() {
        ProductEntity p = producto(new BigDecimal("4.00"), new BigDecimal("2.00"));

        ProductSubsidyService.Bags bags = service.bagsFor(List.of(p, p, p));

        assertThat(bags.shippingCents()).isEqualTo(200);
        assertThat(bags.dutyCents()).isEqualTo(100);
    }

    @Test
    void productos_distintos_suman_sus_dos_bolsas() {
        ProductEntity a = producto(new BigDecimal("4.00"), new BigDecimal("2.00"));
        ProductEntity b = producto(new BigDecimal("6.00"), new BigDecimal("0.00"));

        ProductSubsidyService.Bags bags = service.bagsFor(List.of(a, b));

        assertThat(bags.shippingCents()).isEqualTo(500);
        assertThat(bags.dutyCents()).isEqualTo(100);
    }

    @Test
    void producto_sin_valores_aporta_cero() {
        ProductSubsidyService.Bags bags = service.bagsFor(List.of(producto(null, null)));

        assertThat(bags.shippingCents()).isZero();
        assertThat(bags.dutyCents()).isZero();
    }

    @Test
    void un_importe_negativo_cuenta_como_cero() {
        // Un negativo no es una subvención al revés —que cobraría MÁS envío del cotizado—, es un dato
        // mal metido; se ignora en vez de encarecerle el pedido al cliente.
        ProductSubsidyService.Bags bags = service
                .bagsFor(List.of(producto(new BigDecimal("-5.00"), new BigDecimal("2.00"))));

        assertThat(bags.shippingCents()).isZero();
        assertThat(bags.dutyCents()).isEqualTo(100);
    }

    @Test
    void un_nulo_en_la_lista_no_rompe() {
        ProductEntity p = producto(new BigDecimal("4.00"), new BigDecimal("2.00"));

        ProductSubsidyService.Bags bags = service.bagsFor(Arrays.asList(p, null));

        assertThat(bags.shippingCents()).isEqualTo(200);
    }

    @Test
    void un_producto_sin_id_no_se_cuenta() {
        // Sin id no se puede deduplicar, y contarlo abriría la puerta a sumar dos veces el mismo bulto.
        ProductEntity p = producto(new BigDecimal("4.00"), new BigDecimal("2.00"));
        p.setId(null);

        ProductSubsidyService.Bags bags = service.bagsFor(List.of(p));

        assertThat(bags.shippingCents()).isZero();
    }

    private ProductEntity producto(BigDecimal envio, BigDecimal arancel) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setCurrency("CNY");
        p.setShippingUserCny(envio);
        p.setDutyUserCny(arancel);
        return p;
    }
}
