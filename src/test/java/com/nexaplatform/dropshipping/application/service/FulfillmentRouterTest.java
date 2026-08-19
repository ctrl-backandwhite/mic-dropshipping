package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Una sola lista de formas de envío, con los dos transportistas dentro.
 *
 * <p>Decisión del dueño (18-ago-2026): el cliente ve <b>todas las opciones mezcladas y ordenadas por
 * precio</b>, sin saber qué empresa lleva cada una. Es coherente con el selector que ya existe, donde la
 * más barata se llama «económica» y el plazo va escrito debajo.
 *
 * <p>Lo que este enrutador tiene que resolver bien no es fundir dos listas —eso es fácil— sino los tres
 * casos en que fundirlas sale mal:
 *
 * <ul>
 *   <li><b>Que un transportista no pueda llevar la mercancía.</b> La línea de ropa de YunExpress solo
 *       admite textil; ofrecerla para una taza acaba en guía rechazada con el pedido cobrado.</li>
 *   <li><b>Que un transportista falle.</b> Si uno se cae y arrastra al otro, se pierde la venta por un
 *       problema que no era nuestro y que además tenía alternativa.</li>
 *   <li><b>Que la misma línea aparezca dos veces.</b> CJ revende YunExpress: entre sus 18 opciones para
 *       España, la más barata se llama «YunExpress Ordinary». Enseñar dos veces lo mismo a precios
 *       distintos parece un error de la tienda.</li>
 * </ul>
 */
class FulfillmentRouterTest {

    private static final String PAIS = "ES";

    private final FulfillmentProvider yunexpress = mock(FulfillmentProvider.class);
    private final FulfillmentProvider cj = mock(FulfillmentProvider.class);
    private final CarrierEligibilityService elegibilidad = new CarrierEligibilityService();

    private FulfillmentRouter enrutador;

    /** Una camiseta: partida 6109, textil, admitida por la línea de ropa. */
    private static ProductEntity camiseta() {
        ProductEntity p = new ProductEntity();
        p.setHsCode("610910");
        return p;
    }

    /** Una taza: partida 6912, cerámica. La línea de ropa no la acepta. */
    private static ProductEntity taza() {
        ProductEntity p = new ProductEntity();
        p.setHsCode("691200");
        return p;
    }

    @BeforeEach
    void dosTransportistasQueCotizan() {
        enrutador = new FulfillmentRouter(List.of(yunexpress, cj), elegibilidad);
        lenient().when(yunexpress.nombre()).thenReturn("YUNEXPRESS");
        lenient().when(cj.nombre()).thenReturn("CJ");
        lenient().when(yunexpress.isSupported(anyString())).thenReturn(true);
        lenient().when(cj.isSupported(anyString())).thenReturn(true);
        lenient().when(yunexpress.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("FZZXR", "Apparel line", 785, 5, 8),
                new ShippingOption("THPHR", "Global line", 900, 6, 10)));
        lenient().when(cj.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("1868922929754472449", "YunExpress Ordinary", 767, 8, 15),
                new ShippingOption("1564849338719199233", "CJPacket Ordinary", 883, 4, 8)));
    }

    private static ShippingQuote cotizacion(ShippingOption... opciones) {
        return new ShippingQuote(true, PAIS, opciones[0].amountUsdCents(), "Transportista",
                "Standard", opciones[0].etaMinDays(), opciones[0].etaMaxDays(), "EU", List.of(opciones));
    }

    private List<ShippingOption> opcionesPara(List<ProductEntity> productos) {
        return enrutador.cotizar(PAIS, FulfillmentProvider.ParcelSpec.ofWeight(500), productos).options();
    }

    // ------------------------------------------------------------------ la lista fundida

    @Test
    @DisplayName("con los dos cotizando, sale una sola lista ordenada de más barata a más cara")
    void fundeLasDosListasPorPrecio() {
        List<ShippingOption> opciones = opcionesPara(List.of(camiseta()));

        assertThat(opciones).extracting(ShippingOption::amountUsdCents)
                .containsExactly(767, 785, 883, 900);
    }

    @Test
    @DisplayName("cada opción lleva escrito su transportista, o no se sabría a quién pedirle la guía")
    void cadaOpcionSabeDeQuienEs() {
        List<ShippingOption> opciones = opcionesPara(List.of(camiseta()));

        assertThat(opciones).extracting(ShippingOption::carrier)
                .containsExactly("CJ", "YUNEXPRESS", "CJ", "YUNEXPRESS");
    }

    @Test
    @DisplayName("a igual precio se ofrece antes la que llega antes")
    void aIgualPrecioGanaElPlazoCorto() {
        when(cj.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("rapida", "CJPacket Fast", 785, 3, 5)));

        List<ShippingOption> opciones = opcionesPara(List.of(camiseta()));

        assertThat(opciones.get(0).code())
                .as("mismo precio que la línea de ropa (785) pero llega en 5 días en vez de en 8")
                .isEqualTo("rapida");
    }

    // ------------------------------------------------------------------ qué puede llevar cada uno

    @Test
    @DisplayName("si el pedido no es ropa, la línea de ropa no se ofrece; las demás sí")
    void unPedidoQueNoEsRopaPierdeLaLineaDeRopa() {
        List<ShippingOption> opciones = opcionesPara(List.of(taza()));

        assertThat(opciones).extracting(ShippingOption::code)
                .as("FZZXR solo admite textil: ofrecerla para una taza acaba en guía rechazada")
                .doesNotContain("FZZXR")
                .contains("THPHR", "1564849338719199233");
    }

    @Test
    @DisplayName("un pedido que mezcla ropa y otra cosa tampoco puede ir por la línea de ropa")
    void elPedidoMixtoPierdeLaLineaDeRopa() {
        List<ShippingOption> opciones = opcionesPara(List.of(camiseta(), taza()));

        assertThat(opciones).extracting(ShippingOption::code).doesNotContain("FZZXR");
    }

    // ------------------------------------------------------------------ cuando algo se cae

    @Test
    @DisplayName("si un transportista falla, se sigue vendiendo con el otro")
    void unTransportistaCaidoNoSeLlevaLaVenta() {
        when(cj.quote(anyString(), any())).thenThrow(new IllegalStateException("CJ no responde"));

        List<ShippingOption> opciones = opcionesPara(List.of(camiseta()));

        assertThat(opciones).extracting(ShippingOption::code)
                .as("quedarse sin opciones porque un proveedor está caído es perder la venta por nada")
                .containsExactly("FZZXR", "THPHR");
    }

    @Test
    @DisplayName("si fallan los dos, el checkout sigue en pie y sin opciones")
    void conLosDosCaidosNoSeRompeElCheckout() {
        when(yunexpress.quote(anyString(), any())).thenThrow(new IllegalStateException("caído"));
        when(cj.quote(anyString(), any())).thenThrow(new IllegalStateException("caído"));

        ShippingQuote cotizacion = enrutador.cotizar(PAIS,
                FulfillmentProvider.ParcelSpec.ofWeight(500), List.of(camiseta()));

        assertThat(cotizacion.options()).isEmpty();
    }

    @Test
    @DisplayName("a un transportista que no cubre el destino no se le pregunta")
    void noSePreguntaAQuienNoLlegaAlDestino() {
        when(cj.isSupported(PAIS)).thenReturn(false);

        opcionesPara(List.of(camiseta()));

        verify(cj, never()).quote(anyString(), any());
        verify(yunexpress).quote(anyString(), any());
    }

    // ------------------------------------------------------------------ la línea repetida

    @Test
    @DisplayName("dos opciones con el MISMO plazo se ofrecen una vez, la más barata")
    void noSeRepiteElMismoPlazo() {
        // Al cliente le da igual quién lleve el paquete: mira cuánto cuesta y cuándo llega. Si dos
        // opciones prometen lo mismo, la cara no le aporta nada — solo le hace elegir entre dos cosas
        // que para él son idénticas, y deja la tienda con pinta de estar cobrando de más.
        when(yunexpress.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("FZZXR", "Apparel line", 850, 8, 15)));
        when(cj.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("1868922929754472449", "YunExpress Ordinary", 767, 8, 15)));

        List<ShippingOption> opciones = opcionesPara(List.of(camiseta()));

        assertThat(opciones).hasSize(1);
        assertThat(opciones.get(0).amountUsdCents())
                .as("mismo plazo, precios distintos: se queda la barata")
                .isEqualTo(767);
        assertThat(opciones.get(0).carrier()).isEqualTo("CJ");
    }

    @Test
    @DisplayName("el nombre parecido NO basta para fundir: lo que manda es el plazo")
    void elNombreNoDecide() {
        // Medido el 19-ago-2026 con tarifas reales para España: lo que CJ revende como «YunExpress
        // Ordinary» tarda 8-15 días, mientras que la línea FZZXR contratada directamente tarda 5-8. NO
        // son el mismo servicio: CJ es más barato porque vende uno más lento. Fundirlas por el nombre
        // —que menciona a YunExpress en las dos— le quitaría al cliente la opción rápida, que además
        // es la más barata de su tramo.
        when(yunexpress.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("FZZXR", "YunExpress Apparel", 1079, 5, 8)));
        when(cj.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("1868922929754472449", "YunExpress Ordinary", 960, 8, 15)));

        List<ShippingOption> opciones = opcionesPara(List.of(camiseta()));

        assertThat(opciones)
                .as("plazos distintos son servicios distintos, aunque el nombre se parezca")
                .hasSize(2);
        assertThat(opciones.get(0).amountUsdCents()).isEqualTo(960);
    }

    @Test
    @DisplayName("misma línea vendida por los dos al mismo plazo: gana la barata, venga de quien venga")
    void ganaLaBarataSeaDeQuienSea() {
        // Y al revés que en el caso anterior: si el directo es el barato, se queda el directo. La regla
        // es el precio, no el transportista.
        when(yunexpress.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("FZZXR", "Apparel line", 700, 5, 8)));
        when(cj.quote(anyString(), any())).thenReturn(cotizacion(
                new ShippingOption("otra", "CJPacket Ordinary", 900, 5, 8)));

        List<ShippingOption> opciones = opcionesPara(List.of(camiseta()));

        assertThat(opciones).hasSize(1);
        assertThat(opciones.get(0).carrier()).isEqualTo("YUNEXPRESS");
        assertThat(opciones.get(0).amountUsdCents()).isEqualTo(700);
    }
}
