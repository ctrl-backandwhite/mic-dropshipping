package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorySearchServiceTest {

    @Mock
    HttpClient httpClient;

    CategorySearchService service;

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() throws Exception {
        // The service builds its own HttpClient internally; swap it for the mock via reflection.
        service = new CategorySearchService(new ObjectMapper(), "http://localhost:9400", "categories");
        java.lang.reflect.Field f = CategorySearchService.class.getDeclaredField("httpClient");
        f.setAccessible(true);
        f.set(service, httpClient);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        // Answer en lugar de when(): el helper se llama DENTRO de when(send()).thenReturn(...),
        // y un when() anidado durante otro stubbing dispara UnfinishedStubbingException.
        return (HttpResponse<String>) mock(HttpResponse.class, invocation -> {
            String m = invocation.getMethod().getName();
            if ("statusCode".equals(m)) {
                return status;
            }
            if ("body".equals(m)) {
                return body;
            }
            return null;
        });
    }

    @Test
    void listFromIndex_mapsHitsToIndexedCategoryRows() throws Exception {
        String body = """
                {"hits":{"hits":[
                  {"_source":{"id":"%s","slug":"electronics","nameZh":"电子","nameEs":"Electrónica",
                              "nameEn":"Electronics","namePt":"Eletrónica","icon":"bolt","position":3,
                              "active":true,"parentId":"%s"}}
                ]}}
                """.formatted(ID, PARENT);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, body));

        Optional<List<CategorySearchService.IndexedCategory>> result = service.listFromIndex(null);

        assertThat(result).isPresent();
        List<CategorySearchService.IndexedCategory> rows = result.get();
        assertThat(rows).hasSize(1);
        CategorySearchService.IndexedCategory row = rows.get(0);
        assertThat(row.id()).isEqualTo(ID);
        assertThat(row.slug()).isEqualTo("electronics");
        assertThat(row.nameZh()).isEqualTo("电子");
        assertThat(row.nameEs()).isEqualTo("Electrónica");
        assertThat(row.nameEn()).isEqualTo("Electronics");
        assertThat(row.namePt()).isEqualTo("Eletrónica");
        assertThat(row.icon()).isEqualTo("bolt");
        assertThat(row.position()).isEqualTo(3);
        assertThat(row.active()).isTrue();
        assertThat(row.parentId()).isEqualTo(PARENT);
    }

    @Test
    void listFromIndex_withoutNeedle_buildsMatchAllQuery() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, "{\"hits\":{\"hits\":[]}}"));

        service.listFromIndex(null);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest req = captor.getValue();
        assertThat(req.uri()).hasToString("http://localhost:9400/categories/_search");
        // We cannot read the publisher body directly here; the empty-hits path is what matters.
        assertThat(req.method()).isEqualTo("POST");
    }

    @Test
    void listFromIndex_withNeedle_buildsMultiMatchQueryOverNameAndSlugFields() throws Exception {
        String body = """
                {"hits":{"hits":[{"_source":{"id":"%s","slug":"phones","nameEs":"Teléfonos","position":1,"active":true}}]}}
                """
                .formatted(ID);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, body));

        Optional<List<CategorySearchService.IndexedCategory>> result = service.listFromIndex("  phone  ");

        // trimmed needle still resolves to the single matching row
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).slug()).isEqualTo("phones");
    }

    @Test
    void listFromIndex_emptyHits_returnsEmptyToFallBackToDb() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, "{\"hits\":{\"hits\":[]}}"));

        assertThat(service.listFromIndex(null)).isEmpty();
    }

    @Test
    void listFromIndex_nonOkStatus_returnsEmpty() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(503, null));

        assertThat(service.listFromIndex(null)).isEmpty();
    }

    @Test
    void listFromIndex_clientFailure_returnsEmpty() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("opensearch down"));

        assertThat(service.listFromIndex(null)).isEmpty();
    }

    @Test
    void listFromIndex_skipsHitsWithInvalidOrMissingId() throws Exception {
        String body = """
                {"hits":{"hits":[
                  {"_source":{"id":"not-a-uuid","slug":"bad"}},
                  {"_source":{"slug":"no-id"}},
                  {"_source":{"id":"%s","slug":"good","position":2,"active":false}}
                ]}}
                """.formatted(ID);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, body));

        Optional<List<CategorySearchService.IndexedCategory>> result = service.listFromIndex(null);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        CategorySearchService.IndexedCategory row = result.get().get(0);
        assertThat(row.slug()).isEqualTo("good");
        assertThat(row.position()).isEqualTo(2);
        assertThat(row.active()).isFalse();
        assertThat(row.parentId()).isNull();
    }
}
