package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.ShippingSubsidyService;
import com.nexaplatform.dropshipping.application.service.ShippingSubsidyService.Linea;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * La bolsa que subvenciona el envío: de dónde sale el dinero.
 *
 * <p>Dos fuentes, y ninguna es una promoción inventada. La primera es <b>dinero que entra y no se
 * gasta</b>: el porte del proveedor va dentro del precio unitario, así que tres unidades del mismo
 * producto cobran tres portes chinos cuando el proveedor manda UN solo bulto al almacén —da igual que
 * las unidades sean de colores o tallas distintas—. La segunda es una decisión comercial: de la ganancia
 * del pedido se apartan 5 EUR y el resto se devuelve en forma de envío más barato.
 *
 * <p>Lo que se protege aquí es que la cuenta no se pase de generosa en ninguna de las dos direcciones:
 * regalar de más sale del margen, y no devolver el porte repetido es cobrarle al cliente un gasto que no
 * existe.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShippingSubsidyServiceTest {

    private static final UUID CAMISETA = UUID.randomUUID();
    private static final UUID VESTIDO = UUID.randomUUID();

    /** Porte del proveedor por unidad: los 16 CNY de suelo, ~2,20 USD. */
    private static final int PORTE = 220;

    @Mock
    CustomsValuationService customsValuation;
    @Mock
    CurrencyRateService currencyService;

    @InjectMocks
    ShippingSubsidyService service;

    @BeforeEach
    void enLaUnionYConTasa() {
        // El suelo llega por configuración (@Value), y a un @InjectMocks no se lo pone nadie.
        ReflectionTestUtils.setField(service, "sueloDeGananciaEur", new BigDecimal("5"));
        // Destino de la UE: es el único donde la regla en euros aplica.
        when(customsValuation.perArticleFeeUsdCents(anyString())).thenReturn(300);
        // 5 EUR = 5,43 USD con la tasa sembrada (0,92).
        when(currencyService.toUsd(any(), anyString())).thenAnswer(inv ->
                ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("0.92"), 4, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void dosUnidadesDelMismoProductoAportanUNPorteAunqueSeanVariantesDISTINTAS() {
        // Es el caso que motiva todo: una talla M y una talla L del mismo producto viajan en el mismo
        // bulto desde el proveedor. Cobrar dos portes es cobrar un gasto que no existe.
        List<Linea> carrito = List.of(
                new Linea(CAMISETA, 1, 0, PORTE),
                new Linea(CAMISETA, 1, 0, PORTE));

        assertThat(service.subsidyUsdCents(carrito, "ES")).isEqualTo(PORTE);
    }

    @Test
    void unaSolaUnidadNoAportaNada() {
        // El primer porte sí se paga: es el envío del proveedor al almacén.
        assertThat(service.subsidyUsdCents(List.of(new Linea(CAMISETA, 1, 0, PORTE)), "ES")).isZero();
    }

    @Test
    void productosDISTINTOSNoSeMezclan() {
        // Dos productos distintos son dos bultos del proveedor: los dos portes se pagan de verdad.
        List<Linea> carrito = List.of(
                new Linea(CAMISETA, 1, 0, PORTE),
                new Linea(VESTIDO, 1, 0, PORTE));

        assertThat(service.subsidyUsdCents(carrito, "ES")).isZero();
    }

    @Test
    void cincoUnidadesAportanCuatroPortes() {
        assertThat(service.subsidyUsdCents(List.of(new Linea(CAMISETA, 5, 0, PORTE)), "ES"))
                .isEqualTo(4 * PORTE);
    }

    @Test
    void laGananciaAportaSoloSuExcesoSobreCincoEuros() {
        // 20,00 USD de ganancia menos los 5 EUR (5,43 USD) que se aparta el negocio.
        List<Linea> carrito = List.of(new Linea(CAMISETA, 1, 2000, PORTE));

        assertThat(service.subsidyUsdCents(carrito, "ES")).isEqualTo(2000 - 543);
    }

    @Test
    void unaGananciaPorDEBAJODeCincoEurosNoAportaNada() {
        // El suelo es del negocio: por debajo de él no se subvenciona nada, o cada venta pequeña se
        // comería su propio margen.
        assertThat(service.subsidyUsdCents(List.of(new Linea(CAMISETA, 1, 400, PORTE)), "ES")).isZero();
    }

    @Test
    void lasDosFuentesSeSuman() {
        // Tres unidades del mismo producto: dos portes que vuelven, MÁS el exceso de ganancia del pedido.
        List<Linea> carrito = List.of(new Linea(CAMISETA, 3, 1000, PORTE));

        assertThat(service.subsidyUsdCents(carrito, "ES")).isEqualTo(2 * PORTE + (3000 - 543));
    }

    @Test
    void fueraDeLaUnionLaGananciaNoAportaPeroElPorteREPETIDOSI() {
        // La regla en euros es de la UE-27 y solo de ella. El porte repetido, en cambio, no es una
        // promoción: es devolver un gasto que no se ha hecho, y eso vale en cualquier destino.
        when(customsValuation.perArticleFeeUsdCents("MX")).thenReturn(0);
        List<Linea> carrito = List.of(new Linea(CAMISETA, 3, 5000, PORTE));

        assertThat(service.subsidyUsdCents(carrito, "MX")).isEqualTo(2 * PORTE);
    }

    @Test
    void elCarritoVacioNoRevienta() {
        assertThat(service.subsidyUsdCents(List.of(), "ES")).isZero();
        assertThat(service.subsidyUsdCents(null, "ES")).isZero();
    }

    @Test
    void unaLineaConPerdidaNoRestaDeLaBolsaDeLasOtras() {
        // Un producto vendido por debajo de coste no puede «pagarse» reduciendo la subvención del resto
        // por debajo de cero: la ganancia del pedido se mira entera, pero la bolsa nunca es negativa.
        List<Linea> carrito = List.of(new Linea(CAMISETA, 1, -8000, PORTE));

        assertThat(service.subsidyUsdCents(carrito, "ES")).isZero();
    }
}
