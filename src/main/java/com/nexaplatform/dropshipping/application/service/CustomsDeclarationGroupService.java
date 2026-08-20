package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import lombok.RequiredArgsConstructor;
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

    private final CustomsDeclarationGroupRepository groupRepository;

    /**
     * La descripción con la que se declarará este producto.
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
                .filter(grupo -> grupo.getApprovedAt() != null)
                .map(CustomsDeclarationGroupEntity::getEname)
                .orElse(propia);
    }

    /**
     * Deja una parte de la terna comparable: sin espacios de sobra y sin distinguir mayúsculas.
     *
     * <p>«Cotton» y «  cotton » son el mismo material descrito por dos personas distintas. Tratarlos
     * como grupos separados partiría en dos un grupo que la aduana cuenta como uno.
     */
    public static String normalizeKeyPart(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * Subpartida a 6 dígitos, o {@code null} si el producto no tiene clasificación utilizable.
     *
     * <p>Sin código HS no se agrupa con nadie: agruparlo sería atribuirle una clasificación que nadie ha
     * verificado, y de eso responde el declarante ante la aduana.
     */
    private static String hs6Of(ProductEntity product) {
        String digits = product == null || product.getHsCode() == null ? ""
                : product.getHsCode().replaceAll("[^0-9]", "");
        return digits.length() < 6 ? null : digits.substring(0, 6);
    }
}
