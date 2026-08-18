package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierChannelLimitEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CarrierChannelLimitRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * El despacho usa los límites del CANAL y del PAÍS, no un escalar global.
 *
 * <p>Estas dos cosas cuestan dinero cada vez que se equivocan:
 *
 * <ol>
 *   <li><b>Cuántos bultos.</b> La línea de ropa admite 30 kg a España y solo 15 a Dinamarca. Con un
 *       único número global, el mismo pedido se reparte igual para los dos destinos: o el danés viaja en
 *       un bulto que el transportista rechaza —con el pedido ya cobrado— o el español se parte en dos
 *       guías que se pagan enteras sin necesidad.</li>
 *   <li><b>Si hay peso volumétrico y con qué divisor.</b> La línea de ropa NO lo aplica
 *       («包裹实际重量不计材积»); la de carga general sí, pero dividiendo entre 8000, no entre los 6000 del
 *       aéreo estándar. Aplicarlo donde no toca encarece el envío al cliente por un dato que dice justo
 *       lo contrario; aplicarlo con el divisor equivocado deja la diferencia contra el margen.</li>
 * </ol>
 *
 * <p>Se monta el resolutor REAL sobre un repositorio simulado con las filas que siembra la migración: lo
 * que se comprueba es que el despacho pregunta y obedece, no que un doble devuelva lo que se le dijo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YunExpressLimitePorCanalTest {

    private static final String ROPA = "FZZXR";
    private static final String CARGA_GENERAL = "THPHR";
    /** El canal del entorno de pruebas: no existe en producción y por eso no está sembrado. */
    private static final String SANDBOX = "BPA";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CarrierChannelLimitRepository limitRepository;

    private CarrierChannelLimitService limites;

    @BeforeEach
    void sembrarLosLimitesDeLaCotizacion() {
        limites = new CarrierChannelLimitService(limitRepository);
        // Lo que hoy traen las variables de entorno del sandbox: 2 kg y divisor 6000.
        ReflectionTestUtils.setField(limites, "maxParcelWeightGramsGlobal", 2000);
        ReflectionTestUtils.setField(limites, "volumetricDivisorGlobal", 6000);

        fila(ROPA, "*", 30000, 0, 0);
        fila(ROPA, "DK", 15000, 0, 0);
        fila(ROPA, "US", 30000, 0, 30);
        fila(CARGA_GENERAL, "*", 30000, 8000, 0);
    }

    /** Una fila de {@code carrier_channel_limit} tal y como la deja la migración v145. */
    private void fila(String canal, String pais, int pesoMaximo, int divisor, int minimoFacturable) {
        CarrierChannelLimitEntity e = new CarrierChannelLimitEntity();
        e.setChannelCode(canal);
        e.setCountryCode(pais);
        e.setMaxWeightGrams(pesoMaximo);
        e.setVolumetricDivisor(divisor);
        e.setMinBillableGrams(minimoFacturable);
        e.setMaxLengthMm(600);
        e.setMaxWidthMm(400);
        e.setMaxHeightMm(350);
        e.setSingleParcelOnly(true);
        e.setActive(true);
        when(limitRepository.findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(canal, pais))
                .thenReturn(Optional.of(e));
    }

    /** El servicio con el reparto activo pero SIN topes globales de peso: el peso lo pone la tabla. */
    private YunExpressFulfillmentService service() {
        YunExpressFulfillmentService s = new YunExpressFulfillmentService(null, null, null,
                new CustomsDutyLinesService(limites), productRepository, null, null, limites);
        ReflectionTestUtils.setField(s, "maxParcelWeightGrams", 0);
        ReflectionTestUtils.setField(s, "maxParcelValueCents", 0);
        ReflectionTestUtils.setField(s, "maxParcelUnits", 0);
        ReflectionTestUtils.setField(s, "volumetricDivisor", 6000.0);
        ReflectionTestUtils.setField(s, "volumetricMinCm3", 6000.0);
        return s;
    }

    private UUID productoDe(int gramos) {
        UUID id = UUID.randomUUID();
        ProductEntity p = new ProductEntity();
        p.setId(id);
        p.setPackageWeightGrams(gramos);
        when(productRepository.findById(id)).thenReturn(Optional.of(p));
        return id;
    }

    /** Pedido de 5 abrigos de 4 kg (20 kg en total) al país y por el canal indicados. */
    private Order pedidoDe20Kg(String canal, String pais) {
        OrderItem item = new OrderItem();
        item.setProductId(productoDe(4000));
        item.setQuantity(5);
        item.setUnitPriceCents(3000);
        Order o = new Order();
        o.setOrderNumber("NX-1");
        o.setCurrency("USD");
        o.setShippingCountry(pais);
        o.setShippingChannelCode(canal);
        o.setItems(new ArrayList<>(List.of(item)));
        return o;
    }

    // ─────────────────────────────────────────────────── cuántos bultos

    @Test
    @DisplayName("el mismo pedido se parte en más bultos a Dinamarca (15 kg) que a España (30 kg)")
    void elMismoPedidoSeParteMasParaDinamarcaQueParaEspana() {
        YunExpressFulfillmentService s = service();

        List<?> espana = s.splitOrderBins(pedidoDe20Kg(ROPA, "ES")).bins();
        List<?> dinamarca = s.splitOrderBins(pedidoDe20Kg(ROPA, "DK")).bins();

        assertThat(espana).as("20 kg caben en el bulto de 30 kg que admite España").hasSize(1);
        assertThat(dinamarca).as("Dinamarca solo admite 15 kg: hacen falta dos guías").hasSize(2);
        assertThat(dinamarca.size()).isGreaterThan(espana.size());
    }

    @Test
    @DisplayName("el canal del sandbox, que no está en la tabla, sigue partiendo por el escalar global")
    void elCanalDelSandboxSigueUsandoElEscalarGlobal() {
        // 2 kg de tope global: el pedido de 20 kg se parte en cinco bultos de un abrigo cada uno, porque
        // una unidad de 4 kg ya excede el tope y no se puede partir un artículo.
        YunExpressFulfillmentService s = service();

        assertThat(s.splitOrderBins(pedidoDe20Kg(SANDBOX, "ES")).bins()).hasSize(5);
    }

    @Test
    @DisplayName("sin canal elegido se reparte por el canal configurado por defecto")
    void sinCanalElegidoMandaElCanalConfigurado() {
        // Entre la cotización y el despacho hay pasos donde el pedido aún no lleva canal. El fijado en
        // configuración es el que se va a usar para emitir la guía, así que es el que debe repartir.
        YunExpressFulfillmentService s = service();
        ReflectionTestUtils.setField(s, "productCode", ROPA);
        Order sinCanal = pedidoDe20Kg(null, "DK");

        assertThat(s.splitOrderBins(sinCanal).bins()).hasSize(2);
    }

    // ─────────────────────────────────────────────────── peso volumétrico

    /** Chaqueta voluminosa: 38 × 30 × 10 cm = 11.400 cm³ y 800 g reales. */
    private static final ParcelSpec CHAQUETA = new ParcelSpec(800, 380, 300, 100, false);

    @Test
    @DisplayName("la línea de ropa NO aplica peso volumétrico: factura el peso real")
    void laLineaDeRopaNoAplicaVolumetrico() {
        // «所有国家：包裹实际重量不计材积». Con el divisor global de 6000 se habrían facturado 1.900 g:
        // 1.100 g de más por bulto que el transportista no cobra y el cliente sí pagaba.
        assertThat(service().chargeableWeightGrams(CHAQUETA, ROPA, "ES")).isEqualTo(800);
    }

    @Test
    @DisplayName("la línea de carga general sí lo aplica, y divide entre 8000")
    void laLineaDeCargaGeneralDivideEntre8000() {
        // 11.400 / 8000 = 1,425 kg. Con el divisor de 6000 saldrían 1,9 kg.
        assertThat(service().chargeableWeightGrams(CHAQUETA, CARGA_GENERAL, "ES")).isEqualTo(1425);
    }

    @Test
    @DisplayName("el canal del sandbox conserva el divisor de la configuración")
    void elCanalDelSandboxConservaElDivisorDeLaConfiguracion() {
        assertThat(service().chargeableWeightGrams(CHAQUETA, SANDBOX, "ES")).isEqualTo(1900);
    }

    @Test
    @DisplayName("el peso real manda cuando supera al volumétrico del canal")
    void elPesoRealMandaSiSuperaAlVolumetrico() {
        ParcelSpec pesado = new ParcelSpec(3000, 380, 300, 100, false);

        assertThat(service().chargeableWeightGrams(pesado, CARGA_GENERAL, "ES")).isEqualTo(3000);
    }

    @Test
    @DisplayName("por debajo del mínimo facturable del país se cobra el mínimo")
    void seRespetaElMinimoFacturableDelPais() {
        // Estados Unidos factura desde 30 g en la línea de ropa: cotizar 10 g promete un precio que el
        // transportista no va a cobrar.
        assertThat(service().chargeableWeightGrams(ParcelSpec.ofWeight(10), ROPA, "US")).isEqualTo(30);
        assertThat(service().chargeableWeightGrams(ParcelSpec.ofWeight(500), ROPA, "US")).isEqualTo(500);
    }
}
