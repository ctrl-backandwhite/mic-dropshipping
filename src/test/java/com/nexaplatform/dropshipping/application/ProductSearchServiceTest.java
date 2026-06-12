package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.SearchResultDtoOut;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

    @Mock
    OpenSearchClient client;

    @InjectMocks
    ProductSearchService service;

    @Test
    void searchTyped_returnsEmptyEnvelopeOnClientFailure() throws IOException {
        when(client.search(any(SearchRequest.class), any())).thenThrow(new IOException("down"));

        SearchResultDtoOut result = service.searchTyped("phone", "es", 2, 10);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
    }
}
