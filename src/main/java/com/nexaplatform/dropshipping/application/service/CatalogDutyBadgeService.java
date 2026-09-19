package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.Line;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cuánto sube el arancel del carrito por añadir cada producto de la página — el distintivo «✓ sin arancel
 * adicional» / «+3,00 €» del catálogo.
 *
 * <p><b>Por qué se calcula en el servidor.</b> El derecho de 3 EUR se cobra por línea de declaración
 * <b>y por bulto</b>: dos productos del mismo grupo que acaben en bultos distintos pagan 3 EUR cada uno.
 * Comparar grupos en el navegador daría un ✓ en cuanto dos productos compartieran partida, y sería falso
 * en cuanto el carrito superara el límite del canal y se partiera en dos. Esa diferencia la pondría el
 * comercio al despachar, así que la promesa se calcula con el <b>mismo repartidor de bultos y el mismo
 * contador de líneas</b> que después cobran, y restando: {@code arancel(carrito + producto) −
 * arancel(carrito)}.
 *
 * <p><b>Cuándo no se dice nada.</b> Con el carrito vacío no hay importe que prometer —sin referencia el
 * mensaje no significa nada—, y con el importe por artículo del país a cero el distintivo desaparece
 * entero. Eso último es deliberado: cuando el régimen de 3 EUR caduque (1-jul-2028) bastará con poner el
 * importe a cero en los 27 países para que el catálogo deje de hablar de aranceles, sin tocar código. Un
 * interruptor de interfaz aparte sería una segunda fuente de verdad que alguien olvidaría mover.
 *
 * <p><b>La cantidad del carrito no se conoce aquí</b> —llegan identificadores, no líneas—, así que se
 * cuenta una unidad de cada producto. Afecta solo al reparto en bultos de carritos grandes; la respuesta a
 * «¿abre línea nueva?», que es lo que promete el distintivo, no depende de la cantidad: el derecho se
 * cobra por línea «irrespective of the quantity».
 */
@Service
@RequiredArgsConstructor
public class CatalogDutyBadgeService {

    /**
     * Lo que el catálogo cuenta de un producto sobre el arancel.
     *
     * @param extraDutyCents      cuánto sube el arancel del carrito al añadirlo, en céntimos de dólar;
     *                            {@code null} = no hay nada que prometer (carrito vacío) y el distintivo
     *                            no se pinta
     * @param extraDutyFormatted  el mismo importe ya formateado por el backend: el front solo lo pinta
     * @param dutyGroupId         el grupo APROBADO al que pertenece, para el filtro «ver los que no suman
     *                            arancel»; {@code null} si no tiene grupo o si nadie lo ha firmado
     * @param dutyCovered         la tienda paga el derecho de aduana de este producto: su bolsa de
     *                            arancel llega al importe por artículo del país. A diferencia de
     *                            {@code extraDutyCents}, NO depende del carrito —es una propiedad del
     *                            producto—, así que se sabe también con el carrito vacío
     */
    public record DutyBadge(Integer extraDutyCents, String extraDutyFormatted, UUID dutyGroupId,
            boolean dutyCovered) {
    }

    /**
     * Una línea de declaración del carrito: el grupo aprobado más el ORIGEN.
     *
     * <p>Van juntos porque lo que separa una línea de otra es clasificación + descripción + origen. Dos
     * productos del mismo grupo con orígenes distintos son dos líneas y pagan dos derechos.
     *
     * @param originCountry normalizado; cadena vacía cuando el producto no declara país
     */
    public record LineaDeclarada(UUID grupoId, String originCountry) {
    }

    private final ProductRepository productRepository;
    private final CustomsDeclarationGroupRepository groupRepository;
    private final CustomsDutyLinesService customsDutyLines;
    private final CustomsValuationService customsValuation;
    private final CustomsDeclarationGroupService declarationGroups;
    private final CurrencyRateService currencyService;
    private final ProductSubsidyService subsidies;

    /**
     * El distintivo de cada producto de la página.
     *
     * @param cartProductIds lo que el comprador ya lleva; vacío = no hay importe que prometer
     * @param pageProductIds los productos de la página que se está pintando
     * @param countryCode    el país del comprador (X-Country): el importe por artículo es configurable por
     *                       destino, no un 3 fijo
     * @return un distintivo por producto; vacío cuando ese país ya no cobra derecho por artículo
     */
    @Transactional(readOnly = true)
    public Map<UUID, DutyBadge> badgesFor(List<UUID> cartProductIds, List<UUID> pageProductIds,
            String countryCode) {
        if (pageProductIds == null || pageProductIds.isEmpty()
                || customsValuation.perArticleFeeUsdCents(countryCode) <= 0) {
            return Map.of();
        }
        List<ProductEntity> pagina = productRepository.findAllById(pageProductIds);
        Map<UUID, UUID> grupos = gruposAprobadosDe(pagina);

        List<UUID> carrito = cartProductIds == null ? List.of() : cartProductIds;
        if (carrito.isEmpty()) {
            // Sin carrito no hay importe, pero el grupo sí se devuelve: el filtro «ver los que no suman
            // arancel» tiene que funcionar desde la ficha de un producto aunque no haya nada comprado.
            Map<UUID, DutyBadge> soloGrupo = new HashMap<>();
            for (ProductEntity p : pagina) {
                soloGrupo.put(p.getId(), new DutyBadge(null, null, grupos.get(p.getId()), cubierto(p, countryCode)));
            }
            return soloGrupo;
        }

        List<Line> base = lineasDe(productRepository.findAllById(carrito), countryCode);
        int arancelBase = arancelDe(base, countryCode);

        Map<UUID, DutyBadge> badges = new HashMap<>();
        for (ProductEntity p : pagina) {
            List<Line> conElProducto = new ArrayList<>(base);
            conElProducto.add(lineaDe(p, countryCode));
            int extra = Math.max(0, arancelDe(conElProducto, countryCode) - arancelBase);
            badges.put(p.getId(),
                    new DutyBadge(extra, formateado(extra), grupos.get(p.getId()), cubierto(p, countryCode)));
        }
        return badges;
    }

    /**
     * ¿Paga la tienda el derecho de aduana de este producto?
     *
     * <p>Se compara su bolsa de arancel con el importe por artículo del país, que es lo que cuesta abrir
     * una línea de declaración: si la bolsa llega, el comprador no pone nada por ese concepto. Se mide
     * contra el derecho de UNA línea y no contra el arancel del carrito entero a propósito: el distintivo
     * viaja en la tarjeta, donde todavía no hay pedido, y prometer sobre un carrito que aún no existe
     * seria prometer lo que no se puede cumplir.
     *
     * <p><b>Solo lo dice donde hay derecho que pagar.</b> Ese importe por artículo son los 3 EUR del
     * régimen de la Unión y hoy solo lo cobran los 27 países de la UE; en el resto de destinos vale cero,
     * no hay arancel por línea que cubrir y el distintivo no se pinta. Sale de la tabla de reglas por
     * país, no de una lista escrita aquí: cuando el régimen caduque (1-jul-2028) bastará con poner el
     * importe a cero para que el catálogo deje de prometerlo, sin tocar código.
     *
     * <p>La conversión la hace el mismo servicio que reparte las bolsas al cobrar, así que un importe
     * nulo, cero o negativo cuenta como cero aquí y allí por igual.
     */
    private boolean cubierto(ProductEntity producto, String countryCode) {
        int derecho = customsValuation.perArticleFeeUsdCents(countryCode);
        return derecho > 0 && subsidies.bagsFor(List.of(producto)).dutyCents() >= derecho;
    }

    /**
     * Los grupos <b>aprobados</b> a los que pertenecen esos productos, sin repetir.
     *
     * <p>Es «lo que hay en mi carrito» traducido a líneas de declaración: un carrito de tres productos de
     * tres ternas distintas son tres líneas, y lo que no suma arancel es lo que encaje en <b>cualquiera</b>
     * de las tres. Devolver solo una dejaría fuera dos tercios.
     */
    @Transactional(readOnly = true)
    public List<LineaDeclarada> lineasDe(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<ProductEntity> productos = productRepository.findAllById(productIds);
        Map<UUID, UUID> grupos = gruposAprobadosDe(productos);
        Set<LineaDeclarada> lineas = new LinkedHashSet<>();
        for (ProductEntity p : productos) {
            UUID grupo = grupos.get(p.getId());
            if (grupo != null) {
                lineas.add(new LineaDeclarada(grupo, origenNormalizado(p)));
            }
        }
        return List.copyOf(lineas);
    }

    /** El país de origen comparable: en mayúsculas, sin espacios y con el nulo como cadena vacía. */
    private static String origenNormalizado(ProductEntity p) {
        String origen = p.getCountryOfOrigin();
        return origen == null ? "" : origen.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** El derecho por línea de esas mercancías, repartidas en bultos como las repartirá el transportista. */
    private int arancelDe(List<Line> lineas, String countryCode) {
        List<DutyParcel> bultos = customsDutyLines.parcelsOf(lineas, null, countryCode);
        return customsValuation.perLineDutyUsdCents(countryCode, bultos);
    }

    private List<Line> lineasDe(List<ProductEntity> productos, String countryCode) {
        List<Line> lineas = new ArrayList<>(productos.size());
        for (ProductEntity p : productos) {
            lineas.add(lineaDe(p, countryCode));
        }
        return lineas;
    }

    /**
     * Una unidad del producto tal y como se declararía.
     *
     * <p>La descripción sale de {@link CustomsDeclarationGroupService}, que es de donde la saca el
     * checkout: es lo que hace que dos productos del mismo grupo compartan línea. Resolverla de otra forma
     * aquí prometería una agrupación que después no ocurre.
     */
    private Line lineaDe(ProductEntity p, String countryCode) {
        return new Line(p.getId(), p.getHsCode(), declarationGroups.describeFor(p, countryCode),
                p.getCountryOfOrigin(), 1, 0, ParcelAggregator.unitWeightGrams(p, null), dimension(p, 0),
                dimension(p, 1), dimension(p, 2), ParcelAggregator.hasBattery(p));
    }

    private static int dimension(ProductEntity p, int cual) {
        Integer medida = switch (cual) {
            case 0 -> p.getLengthMm();
            case 1 -> p.getWidthMm();
            default -> p.getHeightMm();
        };
        return medida != null && medida > 0 ? medida : 0;
    }

    /**
     * A qué grupo <b>aprobado</b> pertenece cada producto de la página.
     *
     * <p>Solo los aprobados: un grupo sin firmar no agrupa nada, así que ofrecer «ver los que no suman
     * arancel» sobre él sería una promesa que la aduana no va a cumplir. Se cargan todos de una vez
     * —son 185 filas— en vez de preguntar por cada producto de la página.
     */
    private Map<UUID, UUID> gruposAprobadosDe(List<ProductEntity> pagina) {
        Map<String, UUID> porTerna = new HashMap<>();
        for (CustomsDeclarationGroupEntity g : groupRepository.findAllByOrderByProductCountDesc()) {
            if (g.getApprovedAt() != null) {
                porTerna.put(g.getHs6() + '|' + g.getMaterial() + '|' + g.getUsageCode(), g.getId());
            }
        }
        Map<UUID, UUID> deCadaProducto = new HashMap<>();
        for (ProductEntity p : pagina) {
            String hs6 = hs6Of(p.getHsCode());
            if (hs6 == null) {
                continue;
            }
            UUID grupo = porTerna.get(hs6 + '|'
                    + CustomsDeclarationGroupService.normalizeKeyPart(p.getCustomsMaterial()) + '|'
                    + CustomsDeclarationGroupService.normalizeKeyPart(p.getCustomsUsage()));
            if (grupo != null) {
                deCadaProducto.put(p.getId(), grupo);
            }
        }
        return deCadaProducto;
    }

    private static String hs6Of(String hsCode) {
        String digitos = hsCode == null ? "" : hsCode.replaceAll("[^0-9]", "");
        return digitos.length() < 6 ? null : digitos.substring(0, 6);
    }

    /** El importe ya escrito en la moneda del comprador: el front no calcula ni formatea importes. */
    private String formateado(int usdCents) {
        BigDecimal display = currencyService
                .usdToDisplay(BigDecimal.valueOf(usdCents).movePointLeft(2))
                .setScale(2, RoundingMode.HALF_UP);
        return currencyService.formatDisplay(display, CurrencyHolder.get());
    }
}
