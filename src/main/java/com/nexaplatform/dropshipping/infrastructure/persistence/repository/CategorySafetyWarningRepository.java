package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategorySafetyWarningEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Advertencias de seguridad colgadas de las categorías (art. 19.d del Reglamento (UE) 2023/988). */
public interface CategorySafetyWarningRepository extends JpaRepository<CategorySafetyWarningEntity, UUID> {

    /**
     * Textos de las advertencias que alcanzan a una categoría, subiendo por toda la cadena de ancestros.
     *
     * <p>Es recursiva porque la taxonomía es jerárquica y la advertencia se pone en el nivel donde vive el
     * riesgo: lo declarado en "Belleza" tiene que llegar a "Secador de pelo" sin repetirlo en cada hoja.
     *
     * <p>El {@code DISTINCT ON (code)} evita que una advertencia declarada a la vez en el padre y en el hijo
     * salga dos veces; gana la del nivel con menor {@code position}. El texto cae a español cuando falta la
     * traducción del idioma pedido, porque una advertencia que no se muestra es peor que una en otro idioma.
     */
    @Query(value = """
            WITH RECURSIVE cadena AS (
                SELECT id, parent_id FROM category WHERE id = :categoryId
                UNION ALL
                SELECT c.id, c.parent_id FROM category c JOIN cadena ON c.id = cadena.parent_id
            )
            SELECT x.texto FROM (
                SELECT DISTINCT ON (w.code)
                       w.code,
                       COALESCE(t.text, tes.text) AS texto,
                       w.position AS pos
                FROM category_safety_warning w
                JOIN cadena ON cadena.id = w.category_id
                LEFT JOIN category_safety_warning_translation t
                       ON t.warning_id = w.id AND t.language = :lang
                LEFT JOIN category_safety_warning_translation tes
                       ON tes.warning_id = w.id AND tes.language = 'es'
                WHERE w.active AND COALESCE(t.text, tes.text) IS NOT NULL
                ORDER BY w.code, w.position
            ) x
            ORDER BY x.pos, x.code
            """, nativeQuery = true)
    List<String> textosParaCategoria(@Param("categoryId") UUID categoryId, @Param("lang") String lang);

    /** Advertencias declaradas directamente en una categoría (sin heredar), para la pantalla de admin. */
    List<CategorySafetyWarningEntity> findByCategoryIdOrderByPositionAsc(UUID categoryId);
}
