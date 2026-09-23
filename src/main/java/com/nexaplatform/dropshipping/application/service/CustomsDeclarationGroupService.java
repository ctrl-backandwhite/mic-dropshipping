package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Con qué descripción se declara un producto en la aduana.
 *
 * <p>El derecho temporal de 3 EUR de la Unión se cobra <b>por línea de declaración</b>, y lo que separa
 * una línea de otra es la terna del art. 1(61) del Reglamento Delegado (UE) 2015/2446: clasificación,
 * descripción y origen. Hasta ahora cada producto viajaba con <i>su</i> título en inglés, así que dos
 * productos distintos eran siempre dos líneas aunque compartieran partida — unas zapatillas y unos
 * boxers pagaban 5,99 EUR sobre 9,90 EUR de mercancía.
 *
 * <p>Aquí se decide si ese producto viaja con la descripción <b>genérica de su grupo</b>, en cuyo caso
 * agrupa con todos los que compartan terna y pagan un solo derecho, o con la suya, en cuyo caso no
 * agrupa con nadie. La decisión es dinero en las dos direcciones: agrupar sin permiso cobra de menos y
 * la diferencia la pone el comercio al despachar; no agrupar cuando se debe cobra de más al cliente.
 *
 * <p><b>Solo agrupa un grupo aprobado.</b> Aprobar es firmar lo que se declara ante 27 aduanas, así que
 * no puede ocurrir por efecto colateral de cargar un catálogo. Mientras nadie firme, cada producto es su
 * propia línea: se cobra de más en el peor caso, nunca de menos.
 */
@Service
@RequiredArgsConstructor
public class CustomsDeclarationGroupService {

    /** Lo que aguantan {@code material} y {@code usage_code} en la tabla de grupos. */
    private static final int MAX_PARTE_TERNA = 120;

    private final CustomsDeclarationGroupRepository groupRepository;
    private final CustomsValuationService customsValuation;

    /**
     * Interruptor general. Existe para apagar la agrupación en caliente —por variable de entorno, sin
     * desplegar— si una aduana empezara a contar distinto de lo previsto.
     */
    @Value("${nexadrop.customs.group-declaration-lines:true}")
    private boolean agrupacionActiva;

    /**
     * La descripción con la que se declarará este producto en un destino concreto.
     *
     * <p>Se comprueban tres apagados <b>antes</b> de tocar la base de datos, del más grueso al más fino:
     *
     * <ol>
     *   <li><b>El interruptor general</b>, para sacarlo entero sin desplegar.</li>
     *   <li><b>El del país</b>, para sacar un destino sin parar los otros 26.</li>
     *   <li><b>El importe por artículo</b>: si el país no cobra nada por línea, no hay nada que agrupar.
     *       Es el que hará desaparecer esto solo cuando el régimen de 3 EUR caduque el 1-jul-2028
     *       (Reglamento (UE) 2026/382) — bastará con poner el importe a cero en los 27. Deliberadamente
     *       NO hay un interruptor aparte para la interfaz: sería una segunda fuente de verdad que
     *       alguien olvidaría mover.</li>
     * </ol>
     *
     * <p>Apagar <b>nunca</b> borra los grupos aprobados: quedan en la tabla y vuelven a funcionar al
     * encenderlo, sin repetir la aprobación de 185 descripciones.
     */
    public String describeFor(ProductEntity product, String countryCode) {
        if (!agrupacionActiva || !customsValuation.groupsDeclarationLinesFor(countryCode)
                || customsValuation.perArticleFeeUsdCents(countryCode) <= 0) {
            return CustomsDutyLinesService.declaredDescriptionOf(product);
        }
        return describeFor(product);
    }

    /**
     * El texto en chino ({@code CName}) con el que se declarará este producto en un destino, o
     * {@code null} si esta línea no viaja con la descripción de un grupo.
     *
     * <p>Va en pareja con {@link #describeFor(ProductEntity, String)} y bajo exactamente los mismos
     * apagados, porque una línea de la declaración lleva UN inglés y UN chino y los dos describen la misma
     * mercancía. Devolver aquí el chino del grupo cuando el inglés NO es el del grupo —o al revés— dejaría
     * la línea diciendo dos cosas distintas en los dos idiomas.
     *
     * <p>Un chino sin ideogramas se descarta: YunExpress rechaza la guía si el {@code CName} no los lleva,
     * así que un grupo aprobado con el texto a medio escribir tumbaría todos los envíos de esa partida. El
     * respaldo —el título chino del producto— es el comportamiento de siempre y no rompe nada.
     */
    public String describeZhFor(ProductEntity product, String countryCode) {
        if (!agrupacionActiva || !customsValuation.groupsDeclarationLinesFor(countryCode)
                || customsValuation.perArticleFeeUsdCents(countryCode) <= 0) {
            return null;
        }
        String hs6 = hs6Of(product);
        if (hs6 == null) {
            return null;
        }
        return groupRepository
                .findByHs6AndMaterialAndUsageCode(hs6, normalizeKeyPart(product.getCustomsMaterial()),
                        normalizeKeyPart(product.getCustomsUsage()))
                .filter(grupo -> grupo.getApprovedAt() != null).map(CustomsDeclarationGroupEntity::getCname)
                .filter(CustomsDataCheck::tieneIdeogramas).orElse(null);
    }

    /**
     * La descripción con la que se declarará este producto, sin mirar el destino.
     *
     * <p>Para llamantes que de verdad no conocen el país todavía. Quien lo sepa debe usar
     * {@link #describeFor(ProductEntity, String)}: sin país no se pueden aplicar los apagados.
     *
     * @return la del grupo si su terna está aprobada; su propio título en inglés en cualquier otro caso
     */
    public String describeFor(ProductEntity product) {
        String propia = CustomsDutyLinesService.declaredDescriptionOf(product);
        String hs6 = hs6Of(product);
        if (hs6 == null) {
            return propia;
        }
        return groupRepository
                .findByHs6AndMaterialAndUsageCode(hs6, normalizeKeyPart(product.getCustomsMaterial()),
                        normalizeKeyPart(product.getCustomsUsage()))
                .filter(grupo -> grupo.getApprovedAt() != null).map(CustomsDeclarationGroupEntity::getEname)
                .orElse(propia);
    }

    /**
     * Deja una parte de la terna comparable: sin espacios de sobra y sin distinguir mayúsculas.
     *
     * <p>«Cotton» y «  cotton » son el mismo material descrito por dos personas distintas. Tratarlos
     * como grupos separados partiría en dos un grupo que la aduana cuenta como uno.
     *
     * <p>Y se recorta a lo que cabe en la columna. El catálogo trae materiales de hasta 255 caracteres y
     * la clave del grupo admite 120: sin recortar aquí, un material largo haría reventar la siembra al
     * insertar, y —peor— recortar solo al guardar dejaría la búsqueda pidiendo el texto entero contra una
     * fila que guarda el trozo, así que el grupo no se encontraría nunca y la agrupación fallaría en
     * silencio. Se recorta en el único sitio por el que pasan las dos.
     */
    public static String normalizeKeyPart(String text) {
        if (text == null) {
            return "";
        }
        String limpio = text.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return limpio.length() <= MAX_PARTE_TERNA ? limpio : limpio.substring(0, MAX_PARTE_TERNA);
    }

    /**
     * Subpartida a 6 dígitos, o {@code null} si el producto no tiene clasificación utilizable.
     *
     * <p>Sin código HS no se agrupa con nadie: agruparlo sería atribuirle una clasificación que nadie ha
     * verificado, y de eso responde el declarante ante la aduana.
     */
    private static String hs6Of(ProductEntity product) {
        String digits = product == null || product.getHsCode() == null
                ? ""
                : product.getHsCode().replaceAll("[^0-9]", "");
        return digits.length() < 6 ? null : digits.substring(0, 6);
    }
}
