package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Motor de búsqueda del catálogo. Lo que se protege aquí es lo que el comprador nota: que buscar
 * encuentre lo que pide, que no le cuelen otra cosa, y que si el buscador se cae la tienda siga viva.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductSearchServiceTest {

    @Mock
    OpenSearchClient client;
    @Mock
    ProductIndexer indexer;

    @InjectMocks
    ProductSearchService service;

    @BeforeEach
    void indice() {
        when(indexer.indexName()).thenReturn("products-v2");
    }

    /** Un buscador caído degrada la búsqueda, no la tienda: quien llama debe poder tirar del SQL. */
    @Test
    void siElBuscadorNoRespondeSeAvisaConVacioParaQueSeUseElFallback() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenThrow(new IOException("caído"));

        assertThat(service.searchRelevantIds("botas", "es")).isEmpty();
    }

    /** Vacío ≠ "no sé": si el buscador SÍ responde y no hay nada, no debe reintentarse por SQL. */
    @Test
    void sinResultadosDevuelveListaVaciaQueNoEsLoMismoQueNoPoderBuscar() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(respuesta());

        Optional<List<UUID>> ids = service.searchRelevantIds("zzz-inexistente", "es");

        assertThat(ids).isPresent();
        assertThat(ids.get()).isEmpty();
    }

    /** Sin término no hay nada que buscar: se responde "no sé" y decide quien llama. */
    @Test
    void sinTerminoNoSeConsultaAlBuscador() throws IOException {
        assertThat(service.searchRelevantIds("   ", "es")).isEmpty();
        assertThat(service.searchRelevantIds(null, "es")).isEmpty();

        verify(client, times(0)).search(any(SearchRequest.class), any(Class.class));
    }

    /** El orden que devuelve el buscador ES el orden de relevancia y debe respetarse tal cual. */
    @Test
    void seConservaElOrdenDeRelevanciaDelBuscador() throws IOException {
        UUID primero = UUID.randomUUID();
        UUID segundo = UUID.randomUUID();
        when(client.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(respuesta(primero, segundo));

        assertThat(service.searchRelevantIds("botas", "es")).contains(List.of(primero, segundo));
    }

    /**
     * La segunda pasada (descripciones + erratas) es una RED DE SEGURIDAD: solo entra cuando la búsqueda
     * estricta no ha encontrado nada. Si entrara siempre, una falda cuya descripción dice "combina con
     * botas" volvería a colarse en la búsqueda de "botas".
     */
    @Test
    void laPasadaAmpliaSoloSeLanzaCuandoLaEstrictaNoEncuentraNada() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(respuesta());

        service.searchRelevantIds("botas", "es");

        verify(client, times(2)).search(any(SearchRequest.class), any(Class.class));
    }

    @Test
    void siLaEstrictaEncuentraNoSeAmplia() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(respuesta(UUID.randomUUID()));

        service.searchRelevantIds("botas", "es");

        verify(client, times(1)).search(any(SearchRequest.class), any(Class.class));
    }

    /** Se busca contra el índice VERSIONADO que resuelve el indexador, no contra el nombre lógico. */
    @Test
    void seConsultaElIndiceVersionado() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(respuesta(UUID.randomUUID()));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

        service.searchRelevantIds("botas", "es");

        verify(client).search(captor.capture(), any(Class.class));
        assertThat(captor.getValue().index()).containsExactly("products-v2");
    }

    /** Un idioma que no está indexado no puede reventar la búsqueda: se cae al español. */
    @Test
    void unIdiomaDesconocidoNoRompeLaBusqueda() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(respuesta(UUID.randomUUID()));

        assertThat(service.searchRelevantIds("botas", "eu")).isPresent();
        assertThat(service.searchRelevantIds("botas", null)).isPresent();
    }

    /** El endpoint público nunca propaga un fallo del buscador como error: devuelve un sobre vacío. */
    @Test
    void elEndpointPublicoDevuelveVacioSiElBuscadorFalla() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenThrow(new IOException("caído"));

        SearchResultDtoOut resultado = service.searchTyped("phone", "es", 2, 10);

        assertThat(resultado.getItems()).isEmpty();
        assertThat(resultado.getTotal()).isZero();
        assertThat(resultado.getPage()).isEqualTo(2);
        assertThat(resultado.getSize()).isEqualTo(10);
    }

    /** Tamaños absurdos (0, negativos, 5.000) se acotan antes de llegar al buscador. */
    @Test
    void elTamanoDePaginaSeAcota() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(respuesta());
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

        service.searchTyped("botas", "es", -3, 5000);

        verify(client).search(captor.capture(), any(Class.class));
        assertThat(captor.getValue().size()).isEqualTo(100);
        assertThat(captor.getValue().from()).isZero();
    }

    /**
     * El documento del índice lleva dentro lo que le pagamos al proveedor ({@code basePrice}, en CNY) y de
     * dónde sale la mercancía ({@code externalId}, {@code source}, {@code supplierId}). Eso NO puede salir
     * por el buscador: el endpoint solo exige tener cuenta, y una cuenta de prueba gratuita basta para
     * paginarlo entero. Con el precio de venta a la vista, una división deja la ganancia exacta; con el
     * identificador de oferta, el proveedor de origen. La ficha y el listado ya lo tapan (ProductMapper);
     * si esta prueba falla, la fuga vuelve a estar abierta por la puerta del buscador.
     */
    @Test
    void elBuscadorPublicoNoDevuelveNiElCosteDeProveedorNiDeDondeSeCompra() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(unHitCon(Map.of(
                "id", "3f6c1b2e-0000-4000-8000-000000000001",
                "slug", "botas-de-agua",
                "titleEs", "Botas de agua",
                "basePrice", new java.math.BigDecimal("12.80"),
                "externalId", "OFFER-826531947",
                "source", "1688",
                "supplierId", "9a1c1b2e-0000-4000-8000-0000000000ff",
                "titleZh", "雨靴女款")));

        SearchResultDtoOut resultado = service.searchTyped("botas", "es", 0, 10);

        Map<String, Object> devuelto = resultado.getItems().getFirst().getSource();
        assertThat(devuelto).doesNotContainKeys("basePrice", "externalId", "source", "supplierId", "titleZh");
    }

    /**
     * La contrapartida de la prueba anterior: recortar de más deja al escaparate sin nada que pintar y la
     * búsqueda se ve vacía aunque el motor haya encontrado el producto.
     */
    @Test
    void elBuscadorPublicoSigueDevolviendoLoQueElEscaparateNecesitaParaPintarElResultado() throws IOException {
        when(client.search(any(SearchRequest.class), any(Class.class))).thenReturn(unHitCon(Map.of(
                "id", "3f6c1b2e-0000-4000-8000-000000000001",
                "slug", "botas-de-agua",
                "titleEs", "Botas de agua",
                "titleEn", "Rain boots",
                "mainImage", "https://cdn.nx036.com/img/ab/cd.jpg",
                "rating", 4.6,
                "monthlySales", 320,
                "categoryId", "7c2c1b2e-0000-4000-8000-00000000000a",
                "basePrice", new java.math.BigDecimal("12.80"))));

        SearchResultDtoOut resultado = service.searchTyped("botas", "es", 0, 10);

        Map<String, Object> devuelto = resultado.getItems().getFirst().getSource();
        assertThat(devuelto).containsKeys("id", "slug", "titleEs", "titleEn", "mainImage", "rating",
                "monthlySales", "categoryId");
        // El identificador y la puntuación del motor los añade el servicio, no el índice.
        assertThat(devuelto).containsKeys("_id", "_score");
    }

    @SuppressWarnings("unchecked")
    private static SearchResponse<Map> unHitCon(Map<String, Object> fuente) {
        Hit<Map> hit = new Hit.Builder<Map>().index("products-v2")
                .id(String.valueOf(fuente.get("id"))).score(1.0).source(fuente).build();
        HitsMetadata<Map> meta = new HitsMetadata.Builder<Map>().hits(List.of(hit))
                .total(new TotalHits.Builder().value(1).relation(TotalHitsRelation.Eq).build()).build();
        return new SearchResponse.Builder<Map>().took(1).timedOut(false).hits(meta)
                .shards(s -> s.total(1).successful(1).failed(0)).build();
    }

    @SuppressWarnings("unchecked")
    private static SearchResponse<Map> respuesta(UUID... ids) {
        List<Hit<Map>> hits = java.util.Arrays.stream(ids)
                .map(id -> new Hit.Builder<Map>().index("products-v2").id(id.toString()).build()).toList();
        HitsMetadata<Map> meta = new HitsMetadata.Builder<Map>().hits(hits)
                .total(new TotalHits.Builder().value(hits.size()).relation(TotalHitsRelation.Eq).build()).build();
        return new SearchResponse.Builder<Map>().took(1).timedOut(false).hits(meta)
                .shards(s -> s.total(1).successful(1).failed(0)).build();
    }
}
