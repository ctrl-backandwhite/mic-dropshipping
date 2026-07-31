package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase.BulkOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resultado de las operaciones en lote del catálogo.
 *
 * <p>La regla que encierra —y que antes vivía dentro del controlador— es que un lote NO se detiene ante
 * el primer error: si un producto no se puede borrar porque tiene pedidos, los demás de la selección sí
 * deben borrarse, y el motivo de cada fallo tiene que llegar al admin para que sepa qué revisar.
 */
class CatalogBulkOutcomeTest {

    @Test
    void cuentaAciertosYFallosPorSeparado() {
        BulkOutcome outcome = new BulkOutcome(3, List.of("id-1: tiene pedidos", "id-2: no existe"));

        assertThat(outcome.succeeded()).isEqualTo(3);
        assertThat(outcome.failed()).isEqualTo(2);
        assertThat(outcome.errors()).hasSize(2);
    }

    @Test
    void unLoteCompletamenteCorrectoNoReportaFallos() {
        BulkOutcome outcome = new BulkOutcome(5, List.of());

        assertThat(outcome.failed()).isZero();
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    void losMotivosDeFalloSeConservanParaElAdmin() {
        BulkOutcome outcome = new BulkOutcome(0, List.of("id-9: el producto tiene pedidos"));

        assertThat(outcome.errors().get(0)).contains("tiene pedidos");
    }
}
