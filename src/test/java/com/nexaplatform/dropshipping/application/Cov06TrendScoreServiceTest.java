package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.TrendScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El recálculo de la puntuación de tendencia.
 *
 * <p>Lo que se protege es que la sección «Tendencia ahora» mida VENTAS DE ESTA TIENDA. Antes no la medía
 * nadie: el campo lo escribía solo el sembrador de demostración, apagado desde que el catálogo pasó a datos
 * reales, y 5.220 de 5.524 productos activos estaban a cero. La sección llevaba meses enseñando los mismos
 * 81 productos porque eran los únicos con dato, y los 5.400 cargados después competían contra un cero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov06TrendScoreServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private com.nexaplatform.dropshipping.application.service.CatalogReindexRunner reindexRunner;

    @InjectMocks
    private TrendScoreService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "habilitado", true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(7);
        // El recálculo encadena un reindexado para que el índice no se quede con la puntuación vieja.
        when(reindexRunner.tryAcquire()).thenReturn(true);
    }

    @Test
    @DisplayName("recalcula y devuelve cuántos productos tienen ventas reales")
    void recalculaYCuentaLosQueVenden() {
        assertThat(service.recompute()).isEqualTo(7);
        verify(jdbcTemplate).update(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("lo cancelado y lo reembolsado NO cuenta como venta")
    void noCuentaCanceladoNiReembolsado() {
        service.recompute();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), anyInt(), anyInt());
        // Un pedido reembolsado no es una venta: contarlo premiaría justo al producto que el comprador
        // devolvió, que es el peor candidato posible para encabezar «Tendencia ahora».
        assertThat(sql.getValue()).contains("CANCELLED").contains("REFUNDED");
    }

    @Test
    @DisplayName("solo mira los productos activos")
    void soloProductosActivos() {
        service.recompute();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), anyInt(), anyInt());
        assertThat(sql.getValue()).contains("status = 'ACTIVE'");
    }

    @Test
    @DisplayName("las ventas pesan más que la valoración")
    void lasVentasMandan() {
        service.recompute();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), anyInt(), anyInt());
        // 0,7 ventas frente a 0,3 valoración. Si se invirtiera, un producto sin vender una sola unidad
        // podría encabezar la sección solo por tener cinco estrellas — que es una forma distinta de la
        // misma mentira que había antes.
        assertThat(sql.getValue()).contains("0.7").contains("0.3");
    }

    @Test
    @DisplayName("se hace en UNA sentencia, no producto a producto")
    void unaSolaSentencia() {
        service.recompute();

        // Con 5.500 productos, un bucle en Java serían 5.500 lecturas más 5.500 escrituras y varios
        // minutos de transacción abierta sobre la tabla que sirve el escaparate.
        verify(jdbcTemplate).update(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("con el recálculo apagado no toca la base")
    void apagadoNoTocaNada() {
        ReflectionTestUtils.setField(service, "habilitado", false);

        assertThat(service.recompute()).isZero();
        verify(jdbcTemplate, never()).update(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("el barrido diario delega en el mismo recálculo")
    void elDiarioUsaElMismoCamino() {
        service.recomputeDiario();

        verify(jdbcTemplate).update(anyString(), anyInt(), anyInt());
    }
}
