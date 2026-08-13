package com.nexaplatform.dropshipping.infrastructure.persistence.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registra en Hibernate las funciones SQL propias del buscador multilingüe para que HQL conozca su TIPO de
 * retorno (si no, {@code FUNCTION('nx_norm', x)} se tipa como Object y un {@code LIKE} sobre él revienta con
 * "Operand of 'like' is of type java.lang.Object").
 *
 * <ul>
 *   <li>{@code nx_norm(text) -> String}: normaliza (minúsculas + sin acentos) — definida en la migración v119.</li>
 *   <li>{@code word_similarity(text,text) -> Double}: similitud de palabra de pg_trgm (plurales/erratas).</li>
 * </ul>
 *
 * Se descubre por {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}.
 */
public class SearchFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        var registry = functionContributions.getFunctionRegistry();
        var types = functionContributions.getTypeConfiguration().getBasicTypeRegistry();

        registry.registerPattern("nx_norm", "nx_norm(?1)",
                types.resolve(StandardBasicTypes.STRING));
        registry.registerPattern("word_similarity", "word_similarity(?1,?2)",
                types.resolve(StandardBasicTypes.DOUBLE));
        // nx_wmatch(needle, col): ¿la aguja casa como PALABRA dentro de col (plurales/erratas/multi-término)?
        // Usa el OPERADOR <% de pg_trgm (a diferencia de la función word_similarity, el operador SÍ aprovecha
        // el índice GIN → búsqueda rápida). El umbral lo fija pg_trgm.word_similarity_threshold (0.45, v119).
        registry.registerPattern("nx_wmatch", "(nx_norm(?1) <% nx_norm(?2))",
                types.resolve(StandardBasicTypes.BOOLEAN));
    }
}
