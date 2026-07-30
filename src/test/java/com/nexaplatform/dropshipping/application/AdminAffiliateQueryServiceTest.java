package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.AdminAffiliateRow;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.application.service.AdminAffiliateQueryService;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Listado de afiliados del panel, extraído del controlador.
 *
 * <p>Lo que se protege es el comportamiento que antes vivía dentro del endpoint: cuando el índice de
 * búsqueda responde manda él (y solo se cargan las filas de esa página), y cuando NO responde el panel
 * sigue funcionando paginando en memoria. Un panel vacío porque OpenSearch está caído sería peor que una
 * respuesta algo más lenta.
 */
class AdminAffiliateQueryServiceTest {

    private AffiliateProgramService affiliates;
    private AffiliateSearchService search;
    private AdminAffiliateQueryService service;
    private final List<AffiliateEntity> todos = new ArrayList<>();

    @BeforeEach
    void setUp() {
        affiliates = mock(AffiliateProgramService.class);
        search = mock(AffiliateSearchService.class);
        AffiliateViewMapper mapper = mock(AffiliateViewMapper.class);
        service = new AdminAffiliateQueryService(affiliates, mapper, search);

        AffiliateProgramConfigEntity cfg = new AffiliateProgramConfigEntity();
        cfg.setCurrency("EUR");
        lenient().when(affiliates.config()).thenReturn(cfg);

        for (int i = 0; i < 5; i++) {
            AffiliateEntity a = new AffiliateEntity();
            a.setId(UUID.randomUUID());
            todos.add(a);
        }
        lenient().when(affiliates.allAffiliates()).thenReturn(todos);
        lenient().when(affiliates.listCodes(any())).thenReturn(List.of());
        lenient().when(affiliates.commissionsForAffiliate(any())).thenReturn(List.of());
        lenient().when(mapper.toAdminRow(any(), any(), any(), anyString()))
                .thenReturn(mock(AdminAffiliateRow.class));
    }

    @Test
    void cuandoElIndiceRespondeSeUsaSuPaginaYSuTotal() {
        List<UUID> ids = List.of(todos.get(1).getId(), todos.get(3).getId());
        when(search.pageIds(any(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new AffiliateSearchService.IdPage(ids, 42L)));

        AdminAffiliateQueryService.AffiliatePage page = service.page(null, null, 0, 20);

        assertThat(page.rows()).hasSize(2);
        assertThat(page.total()).isEqualTo(42L);
    }

    @Test
    void conElIndiceCaidoElPanelSigueFuncionandoPaginandoEnMemoria() {
        when(search.pageIds(any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());

        AdminAffiliateQueryService.AffiliatePage page = service.page(null, null, 0, 2);

        assertThat(page.rows()).hasSize(2);
        assertThat(page.total()).isEqualTo(5);
    }

    @Test
    void laUltimaPaginaNoSeSaleDelRango() {
        when(search.pageIds(any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());

        // 5 elementos, tamaño 2 -> la página 2 solo tiene uno, y la 9 ninguno (sin desbordar).
        assertThat(service.page(null, null, 2, 2).rows()).hasSize(1);
        assertThat(service.page(null, null, 9, 2).rows()).isEmpty();
    }

    @Test
    void unIdDelIndiceQueYaNoExisteNoRompeElListado() {
        // El índice puede ir por detrás de la base de datos: un afiliado borrado sigue indexado.
        List<UUID> ids = List.of(todos.get(0).getId(), UUID.randomUUID());
        when(search.pageIds(any(), any(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new AffiliateSearchService.IdPage(ids, 2L)));

        assertThat(service.page(null, null, 0, 20).rows()).hasSize(1);
    }
}
