package com.nexaplatform.dropshipping.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexaplatform.dropshipping.api.dto.out.SearchHitDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatToolSpec;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Búsqueda en el catálogo para el asistente. Reutiliza el buscador del
 * escaparate —el mismo índice, los mismos idiomas— en vez de abrir una segunda
 * vía de consulta que habría que mantener en paralelo.
 *
 * <p>
 * <b>Lista blanca de campos, y ningún precio.</b> El documento indexado incluye
 * {@code basePrice}, que es el coste antes del margen: devolverlo al modelo
 * sería filtrar el coste a quien sepa preguntar, y además en lenguaje natural y
 * en ocho idiomas. Aquí solo salen identificador, título e imagen; el precio de
 * venta lo pinta el escaparate con la ficha, que es quien sabe calcularlo.
 */
@Component
@RequiredArgsConstructor
public class CatalogSearchTool implements ChatTool {

    /** Campos que pueden salir del índice hacia el modelo. Todo lo demás se descarta. */
    private static final List<String> CAMPOS_PUBLICOS = List.of("id", "slug", "rating", "monthlySales",
            "freeShipping", "hasVideo", "mainImage", "categoryName");

    private static final int MAX_RESULTADOS = 6;

    private final ProductSearchService search;
    private final ObjectMapper mapper;

    @Override
    public ChatToolSpec spec() {
        return new ChatToolSpec("buscar_productos",
                "Busca productos en el catálogo de la tienda por palabras clave. "
                        + "Devuelve como mucho " + MAX_RESULTADOS + " resultados con su identificador, "
                        + "título e imagen. No devuelve precios: el precio se muestra en la ficha.",
                """
                        {"type":"object",
                         "properties":{
                           "consulta":{"type":"string","description":"Palabras clave de lo que busca la persona"}},
                         "required":["consulta"],
                         "additionalProperties":false}""");
    }

    @Override
    public String execute(JsonNode arguments, ChatContext context) {
        String consulta = arguments.path("consulta").asText("");
        if (consulta.isBlank()) {
            return "{\"items\":[],\"aviso\":\"consulta vacía\"}";
        }
        SearchResultDtoOut resultado = search.searchTyped(consulta, context.language(), 0, MAX_RESULTADOS);
        ObjectNode salida = mapper.createObjectNode();
        // La consulta viaja de vuelta para que el escaparate pueda repetirla en su catálogo: el
        // panel enseña unas pocas fichas, la rejilla las enseña todas con sus filtros y su precio.
        salida.put("consulta", consulta);
        salida.put("total", resultado.getTotal());
        ArrayNode items = salida.putArray("items");
        for (SearchHitDtoOut hit : resultado.getItems()) {
            items.add(depurar(hit.getSource(), context.language()));
        }
        return salida.toString();
    }

    /** Deja pasar solo los campos públicos y el título del idioma de la conversación. */
    private ObjectNode depurar(Map<String, Object> source, String language) {
        ObjectNode limpio = mapper.createObjectNode();
        for (String campo : CAMPOS_PUBLICOS) {
            Object valor = source.get(campo);
            if (valor != null) {
                limpio.putPOJO(campo, valor);
            }
        }
        Object titulo = source.get(tituloDelIdioma(language));
        if (titulo == null) {
            titulo = source.get("titleEs");
        }
        if (titulo != null) {
            limpio.putPOJO("titulo", titulo);
        }
        return limpio;
    }

    private String tituloDelIdioma(String language) {
        if (language == null || language.isBlank()) {
            return "titleEs";
        }
        String lang = language.trim().toLowerCase();
        return "title" + Character.toUpperCase(lang.charAt(0)) + lang.substring(1);
    }
}
