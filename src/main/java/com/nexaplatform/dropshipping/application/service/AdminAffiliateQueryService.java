package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.AdminAffiliateRow;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Listado paginado de afiliados para el panel de administración.
 *
 * <p>Vive aquí y no en el controlador porque la decisión de CÓMO se obtiene la página es de negocio, no
 * de transporte HTTP: se busca primero en el índice de OpenSearch —que devuelve los identificadores ya
 * filtrados y ordenados— y solo se cargan de la base de datos las filas de esa página. Si el índice no
 * está disponible, se arma el listado completo en memoria y se pagina ahí; el conjunto está acotado y es
 * preferible una respuesta más lenta a un panel vacío.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAffiliateQueryService {

    /** Una página ya resuelta: las filas y el total de coincidencias. */
    public record AffiliatePage(List<AdminAffiliateRow> rows, long total) {
    }

    private final AffiliateProgramService affiliates;
    private final AffiliateViewMapper mapper;
    private final AffiliateSearchService affiliateSearch;

    /** Página de afiliados filtrada por texto libre y estado. */
    @Transactional(readOnly = true)
    public AffiliatePage page(String q, String status, int page, int size) {
        String currency = affiliates.config().getCurrency();
        Optional<AffiliateSearchService.IdPage> indexed = affiliateSearch.pageIds(q, status, page, size);
        if (indexed.isPresent()) {
            return fromIndex(indexed.get(), currency);
        }
        log.debug("::> [AFFILIATE] Índice no disponible: listado paginado en memoria");
        return fromDatabase(page, size, currency);
    }

    /** Camino normal: el índice manda los ids de la página y solo se construyen esas filas. */
    private AffiliatePage fromIndex(AffiliateSearchService.IdPage indexed, String currency) {
        Map<UUID, AffiliateEntity> byId = new HashMap<>();
        affiliates.allAffiliates().forEach(a -> byId.put(a.getId(), a));
        List<AdminAffiliateRow> rows = indexed.ids().stream().map(byId::get).filter(Objects::nonNull)
                .map(a -> toRow(a, currency)).toList();
        return new AffiliatePage(rows, indexed.total());
    }

    /** Respaldo con el índice caído: se pagina sobre el listado completo. */
    private AffiliatePage fromDatabase(int page, int size, String currency) {
        List<AdminAffiliateRow> all = affiliates.allAffiliates().stream().map(a -> toRow(a, currency)).toList();
        int from = Math.min(Math.max(0, page) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new AffiliatePage(all.subList(from, to), all.size());
    }

    private AdminAffiliateRow toRow(AffiliateEntity a, String currency) {
        return mapper.toAdminRow(a, affiliates.listCodes(a.getId()), affiliates.commissionsForAffiliate(a.getId()),
                currency);
    }
}
