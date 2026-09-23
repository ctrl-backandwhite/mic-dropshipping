package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WelcomeExampleSettingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WelcomeExampleSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Elige los tres productos con los que la guía de bienvenida enseña a comprar en la Unión Europea.
 *
 * <p>La guía explica dos reglas que le ahorran dinero al comprador: el <b>arancel se paga por partida
 * declarada</b>, no por unidad, y el <b>envío se paga por bulto</b>, no por producto. Con números
 * inventados nadie se las cree; con productos del catálogo que se pueden sumar y restar, se ven solas.
 *
 * <p>Para que se vean, los tres ejemplos no pueden salir al azar: hacen falta <b>dos que compartan
 * partida</b> —así, al añadir el segundo, el arancel NO sube— y <b>uno de otra distinta</b>, que sí la
 * sube. Si los tres cayeran en la misma partida, la mitad de la lección desaparecería.
 *
 * <p>El admin puede fijarlos a mano. Cuando lo hace manda su elección, salvo que alguno de esos productos
 * haya dejado de estar disponible: un ejemplo que no se puede comprar es peor que ninguno, así que
 * entonces se vuelve a la automática.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WelcomeExamplesService {

    /** Cuántos ejemplos ilustran la guía. Tres son los mínimos para enseñar «misma partida» y «otra». */
    public static final int CUANTOS = 3;

    /**
     * De cuántos candidatos se elige. Se miran los más vendidos y no el catálogo entero: basta para
     * encontrar dos partidas distintas y evita traerse miles de filas para escoger tres.
     */
    private static final int CANDIDATOS = 300;

    private final ProductRepository productRepository;
    private final WelcomeExampleSettingRepository settingRepository;
    private final CustomsDeclarationGroupService declarationGroups;

    /**
     * Los tres productos de ejemplo, en el orden en que se enseñan: los dos de partida compartida
     * primero y el de partida distinta al final, que es el que hace subir el arancel al añadirlo.
     *
     * <p>Devuelve lo que haya cuando el catálogo no da para tres —una tienda recién sembrada, o sin
     * productos declarables—: la guía sabe pintar menos de tres, y quedarse sin guía por no tener el
     * tercero sería peor.
     */
    @Transactional(readOnly = true)
    public List<ProductEntity> examples() {
        List<ProductEntity> fijados = fijadosPorElAdmin();
        if (!fijados.isEmpty()) {
            return fijados;
        }
        return elegirAutomaticamente();
    }

    /** La clave de partida arancelaria de un producto: es la que el simulador cuenta para el arancel. */
    public String dutyGroupOf(ProductEntity product) {
        String hs = product.getHsCode() == null ? "" : product.getHsCode().replaceAll("[^0-9]", "");
        if (hs.length() < 6) {
            return "SIN-HS:" + product.getId();
        }
        String descripcion = declarationGroups.describeFor(product);
        String origen = product.getCountryOfOrigin() == null ? "" : product.getCountryOfOrigin();
        return (hs.substring(0, 6) + "|" + descripcion + "|" + origen).toLowerCase();
    }

    /**
     * Los que el admin haya fijado, si siguen siendo comprables. Se descartan en bloque y no uno a uno:
     * una selección a medias perdería el emparejamiento de partidas que el admin quiso montar.
     */
    private List<ProductEntity> fijadosPorElAdmin() {
        Optional<WelcomeExampleSettingEntity> ajuste = settingRepository.findById((short) 1);
        if (ajuste.isEmpty()) {
            return List.of();
        }
        // Stream.of y no List.of: el admin puede haber fijado solo uno o dos, y List.of no admite nulos
        // —revienta con NullPointerException antes de llegar a filtrarlos—.
        List<UUID> ids = Stream
                .of(ajuste.get().getProductId1(), ajuste.get().getProductId2(), ajuste.get().getProductId3())
                .filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<ProductEntity> encontrados = productRepository.findAllById(ids).stream()
                .filter(p -> p.getStatus() == ProductStatus.ACTIVE).toList();
        if (encontrados.size() != ids.size()) {
            log.info("Ejemplos de bienvenida: {} de {} fijados ya no están disponibles; se eligen solos",
                    ids.size() - encontrados.size(), ids.size());
            return List.of();
        }
        // Se devuelven en el orden en que el admin los grabó, no en el que los saque la consulta.
        return ids.stream().map(id -> encontrados.stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null))
                .filter(Objects::nonNull).toList();
    }

    /**
     * Dos de la partida más poblada y uno de cualquier otra.
     *
     * <p>La partida más poblada primero porque es la que el comprador se va a encontrar de verdad al
     * navegar: si la pareja se formara con una partida rara, el ejemplo enseñaría un caso que casi nunca
     * le va a tocar.
     */
    private List<ProductEntity> elegirAutomaticamente() {
        List<ProductEntity> candidatos = productRepository.findWelcomeExampleCandidates(ProductStatus.ACTIVE,
                PageRequest.of(0, CANDIDATOS));
        if (candidatos.isEmpty()) {
            return List.of();
        }
        Map<String, List<ProductEntity>> porPartida = new LinkedHashMap<>();
        for (ProductEntity p : candidatos) {
            porPartida.computeIfAbsent(dutyGroupOf(p), k -> new ArrayList<>()).add(p);
        }
        String masPoblada = porPartida.entrySet().stream().max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey).orElse(null);
        List<ProductEntity> pareja = porPartida.getOrDefault(masPoblada, List.of());

        List<ProductEntity> elegidos = new ArrayList<>(pareja.stream().limit(2).toList());
        porPartida.entrySet().stream().filter(e -> !e.getKey().equals(masPoblada)).map(e -> e.getValue().get(0))
                .findFirst().ifPresent(elegidos::add);
        return elegidos.stream().limit(CUANTOS).toList();
    }

    /** Fija a mano los tres ejemplos. Con la lista vacía se vuelve a la elección automática. */
    @Transactional
    public void fijar(List<UUID> productIds, String quien) {
        WelcomeExampleSettingEntity ajuste = settingRepository.findById((short) 1).orElseGet(() -> {
            WelcomeExampleSettingEntity nuevo = new WelcomeExampleSettingEntity();
            nuevo.setId((short) 1);
            return nuevo;
        });
        List<UUID> ids = productIds == null ? List.of() : productIds;
        ajuste.setProductId1(ids.size() > 0 ? ids.get(0) : null);
        ajuste.setProductId2(ids.size() > 1 ? ids.get(1) : null);
        ajuste.setProductId3(ids.size() > 2 ? ids.get(2) : null);
        ajuste.setUpdatedAt(Instant.now());
        ajuste.setUpdatedBy(quien);
        settingRepository.save(ajuste);
    }
}
