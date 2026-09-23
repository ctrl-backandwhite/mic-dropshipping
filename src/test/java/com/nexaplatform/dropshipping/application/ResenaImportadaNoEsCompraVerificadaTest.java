package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.domain.enums.ReviewSource;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Una reseña que no viene de una compra en esta tienda no puede anunciarse como compra verificada.
 *
 * <p>El catálogo se cargó desde 1688 arrastrando sus reseñas: 77.694 filas, ninguna con usuario, y
 * 77.390 marcadas como compra verificada. Se estaban sirviendo al comprador con ese distintivo, con
 * autores que delatan el origen («Cliente anónimo», «l**7», que es cómo 1688 censura los nombres).
 *
 * <p>Presentar una reseña como escrita por alguien que compró el producto sin haber tomado medidas
 * razonables para comprobarlo está en la LISTA NEGRA de prácticas comerciales desleales que introdujo
 * la Directiva Omnibus (UE) 2019/2161. Estar en la lista negra significa que se sanciona en todo caso:
 * no hay que demostrar que alguien resultó engañado.
 *
 * <p>Las reseñas se siguen mostrando —son información útil— pero declarando su procedencia.
 */
@DisplayName("Sólo una compra real puede llevar el distintivo de verificada")
class ResenaImportadaNoEsCompraVerificadaTest {

    @Test
    void unaResenaDelProveedorNuncaEsCompraVerificada() {
        assertThat(ReviewSource.SUPPLIER.canBeVerified()).isFalse();
    }

    @Test
    void unaResenaDeUnCompradorSiPuedeSerlo() {
        assertThat(ReviewSource.CUSTOMER.canBeVerified()).isTrue();
    }

    @Test
    void porDefectoUnaResenaSeConsideraImportada() {
        // El lado seguro: si alguien añade un camino nuevo y olvida fijar el origen, la reseña se
        // presenta como del proveedor. Lo contrario —dar por propia una reseña ajena— es lo sancionable.
        ProductReviewEntity review = ProductReviewEntity.builder().rating((short) 5).build();

        assertThat(review.getSource()).isEqualTo(ReviewSource.SUPPLIER);
        assertThat(review.isVerifiedPurchase()).isFalse();
    }

    @Test
    void elDistintivoNoSeActivaSolo() {
        // Construir una reseña sin decir nada del distintivo no debe activarlo por omisión.
        ProductReviewEntity review = ProductReviewEntity.builder().rating((short) 5).authorName("l**7")
                .body("Muy buen producto").build();

        assertThat(review.isVerifiedPurchase()).isFalse();
    }
}
