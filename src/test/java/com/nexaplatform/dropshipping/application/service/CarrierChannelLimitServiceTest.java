package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService.ChannelLimit;
import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService.Origen;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierChannelLimitEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CarrierChannelLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolución del límite de bulto por (canal, país).
 *
 * <p>El peso máximo por bulto NO es un número global: lo fija el transportista canal a canal y país a
 * país. La línea de ropa admite 30 kg a España y solo 15 kg a Dinamarca; la de carga general divide el
 * peso volumétrico entre 8000 mientras que la de ropa no lo aplica en absoluto. Con un escalar único se
 * cotiza y se despacha por un límite que no es el del envío que se está haciendo: o se parte de más
 * —guías y coste de sobra— o de menos, y entonces el transportista rechaza el bulto o lo repesa y
 * factura la diferencia contra el margen.
 *
 * <p>Lo que se fija aquí es el ORDEN de resolución, que es lo único que puede romperse en silencio:
 * fila exacta del país → fila comodín del canal → configuración global. Ese último escalón se conserva
 * a propósito para el entorno de pruebas, cuyo canal {@code BPA} no está en la tabla.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarrierChannelLimitServiceTest {

    private static final String ROPA = "FZZXR";
    private static final String CARGA_GENERAL = "THPHR";
    private static final String SANDBOX = "BPA";

    @Mock
    private CarrierChannelLimitRepository repository;

    private CarrierChannelLimitService service;

    @BeforeEach
    void configuracionGlobalDelSandbox() {
        service = new CarrierChannelLimitService(repository);
        // Los valores que hoy vienen de las variables de entorno: son el último recurso.
        ReflectionTestUtils.setField(service, "maxParcelWeightGramsGlobal", 2000);
        ReflectionTestUtils.setField(service, "volumetricDivisorGlobal", 6000);
    }

    /** Fila de la tabla tal y como la siembra la migración. */
    private static CarrierChannelLimitEntity fila(String canal, String pais, int pesoMaximo, int divisor) {
        CarrierChannelLimitEntity e = new CarrierChannelLimitEntity();
        e.setChannelCode(canal);
        e.setCountryCode(pais);
        e.setMaxWeightGrams(pesoMaximo);
        e.setVolumetricDivisor(divisor);
        e.setMinBillableGrams(0);
        e.setMaxLengthMm(600);
        e.setMaxWidthMm(400);
        e.setMaxHeightMm(350);
        e.setSingleParcelOnly(true);
        e.setActive(true);
        return e;
    }

    private void hayFila(String canal, String pais, CarrierChannelLimitEntity fila) {
        when(repository.findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(canal, pais))
                .thenReturn(Optional.ofNullable(fila));
    }

    @Test
    @DisplayName("la fila exacta del país manda sobre el comodín del canal")
    void laFilaExactaDelPaisManda() {
        hayFila(ROPA, "DK", fila(ROPA, "DK", 15000, 0));
        hayFila(ROPA, "*", fila(ROPA, "*", 30000, 0));

        ChannelLimit limite = service.resolve(ROPA, "DK");

        assertThat(limite.maxWeightGrams()).as("Dinamarca solo admite 15 kg en la línea de ropa").isEqualTo(15000);
        assertThat(limite.origen()).isEqualTo(Origen.EXACTA);
    }

    @Test
    @DisplayName("sin fila del país se usa el comodín del canal")
    void sinFilaDelPaisSeUsaElComodinDelCanal() {
        hayFila(ROPA, "ES", null);
        hayFila(ROPA, "*", fila(ROPA, "*", 30000, 0));

        ChannelLimit limite = service.resolve(ROPA, "ES");

        assertThat(limite.maxWeightGrams()).isEqualTo(30000);
        assertThat(limite.origen()).isEqualTo(Origen.CANAL);
    }

    @Test
    @DisplayName("el canal del sandbox, que no está en la tabla, conserva la configuración global")
    void elCanalDelSandboxConservaLaConfiguracionGlobal() {
        // BPA no se sembró: no existe en producción. Si la resolución no cayera a la configuración, el
        // entorno de pruebas dejaría de partir los pedidos y sus guías de 2 kg empezarían a rechazarse.
        hayFila(SANDBOX, "ES", null);
        hayFila(SANDBOX, "*", null);

        ChannelLimit limite = service.resolve(SANDBOX, "ES");

        assertThat(limite.maxWeightGrams()).isEqualTo(2000);
        assertThat(limite.volumetricDivisor()).isEqualTo(6000);
        assertThat(limite.origen()).isEqualTo(Origen.GLOBAL);
    }

    @Test
    @DisplayName("sin canal conocido no se consulta la tabla: se responde con la configuración")
    void sinCanalConocidoNoSeConsultaLaTabla() {
        // Hay puntos del flujo donde todavía no se ha elegido canal. Preguntar por un canal vacío solo
        // gastaría una consulta para no encontrar nada.
        assertThat(service.resolve(null, "ES").origen()).isEqualTo(Origen.GLOBAL);
        assertThat(service.resolve("   ", "ES").origen()).isEqualTo(Origen.GLOBAL);

        verify(repository, never()).findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(anyString(), anyString());
    }

    @Test
    @DisplayName("una fila desactivada no cuenta y se sigue bajando por la cadena")
    void unaFilaDesactivadaNoCuenta() {
        // Desactivar una excepción es la forma de volver al valor del canal sin borrar el dato ni perder
        // de dónde salía.
        CarrierChannelLimitEntity apagada = fila(ROPA, "DK", 15000, 0);
        apagada.setActive(false);
        hayFila(ROPA, "DK", apagada);
        hayFila(ROPA, "*", fila(ROPA, "*", 30000, 0));

        ChannelLimit limite = service.resolve(ROPA, "DK");

        assertThat(limite.maxWeightGrams()).isEqualTo(30000);
        assertThat(limite.origen()).isEqualTo(Origen.CANAL);
    }

    @Test
    @DisplayName("el país se busca sin distinguir mayúsculas ni espacios sobrantes")
    void elPaisSeNormalizaAntesDeBuscar() {
        hayFila(ROPA, "DK", fila(ROPA, "DK", 15000, 0));

        assertThat(service.resolve(" fzzxr ", " dk ").maxWeightGrams()).isEqualTo(15000);
    }

    @Test
    @DisplayName("divisor 0 significa que el canal NO aplica peso volumétrico")
    void elDivisorCeroSignificaSinVolumetrico() {
        // La ficha de la línea de ropa lo dice literalmente: «所有国家：包裹实际重量不计材积». Tratar el 0
        // como «usa el divisor por defecto» facturaría un peso que el transportista no cobra.
        hayFila(ROPA, "*", fila(ROPA, "*", 30000, 0));
        hayFila(CARGA_GENERAL, "*", fila(CARGA_GENERAL, "*", 30000, 8000));

        assertThat(service.resolve(ROPA, null).aplicaVolumetrico()).isFalse();
        assertThat(service.resolve(CARGA_GENERAL, null).aplicaVolumetrico()).isTrue();
        assertThat(service.resolve(CARGA_GENERAL, null).volumetricDivisor()).isEqualTo(8000);
    }

    @Test
    @DisplayName("sin país se salta la búsqueda exacta y se va directo al comodín del canal")
    void sinPaisSeVaDirectoAlComodin() {
        hayFila(ROPA, "*", fila(ROPA, "*", 30000, 0));

        assertThat(service.resolve(ROPA, null).origen()).isEqualTo(Origen.CANAL);
        assertThat(service.resolve(ROPA, "").origen()).isEqualTo(Origen.CANAL);
    }
}
