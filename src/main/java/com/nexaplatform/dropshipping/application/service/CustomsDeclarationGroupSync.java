package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.Hs6DeclarationText;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsTernaRow;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Siembra los grupos de declaración a partir del catálogo: una fila por terna (partida, material, uso)
 * con un <b>borrador</b> de descripción esperando aprobación.
 *
 * <p>Se ejecuta tras cada carga masiva, porque una carga nueva puede traer ternas que no existían. Y se
 * ejecuta sobre una tabla que ya contiene textos <b>firmados</b> —descripciones que alguien aprobó para
 * declarar ante 27 aduanas—, de ahí la única regla que de verdad importa aquí:
 *
 * <p><b>Solo se crea lo que falta.</b> La siembra no reescribe el {@code ename} ni el {@code cname} de
 * ningún grupo existente, esté aprobado o no. Del aprobado, porque cambiar en silencio lo que se declara
 * es exactamente lo que la aprobación existe para impedir. Del que aún no lo está, porque puede estar a
 * medio redactar y la siembra no es quién para deshacer el trabajo de otro. Lo único que se refresca es
 * {@code product_count}, que es informativo y ordena el panel de aprobación: con la cuenta congelada en
 * el día de la siembra, quien aprueba elegiría mal por dónde empezar.
 *
 * <p>Un producto sin código HS utilizable no genera grupo: nadie ha verificado su clasificación y de ella
 * responde el declarante ante la aduana. Sigue siendo su propia línea, que cobra de más, nunca de menos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsDeclarationGroupSync {

    /** Separa las tres partes del borrador. Es un punto medio, no un guion: no aparece en los textos. */
    private static final String SEPARADOR = " · ";

    /** Lo que aguantan {@code ename} y {@code cname} en la tabla. */
    private static final int MAX_DESCRIPCION = 512;

    private final ProductRepository productRepository;
    private final CustomsDeclarationGroupRepository groupRepository;

    /**
     * Pone la tabla al día con el catálogo.
     *
     * @return cuántos grupos se han creado; los que ya existían solo ven refrescada su cuenta
     */
    @Transactional
    public int sync() {
        Map<String, Terna> ternas = ternasDelCatalogo();
        Instant ahora = Instant.now();
        int creados = 0;
        for (Terna terna : ternas.values()) {
            Optional<CustomsDeclarationGroupEntity> existente = groupRepository
                    .findByHs6AndMaterialAndUsageCode(terna.hs6(), terna.material(), terna.usageCode());
            if (existente.isEmpty()) {
                groupRepository.save(borradorDe(terna, ahora));
                creados++;
            } else {
                refrescarCuenta(existente.get(), terna.total(), ahora);
            }
        }
        log.info("Grupos de declaración: {} ternas en el catálogo, {} creadas", ternas.size(), creados);
        return creados;
    }

    /**
     * Las ternas del catálogo, ya normalizadas y fundidas.
     *
     * <p>La base las devuelve en crudo, así que «Cotton» y «  cotton » llegan como dos filas: aquí se
     * suman en una. Separarlas partiría en dos un grupo que la aduana cuenta como uno y cobraría 3 EUR de
     * más por una diferencia de tecleo. Las filas vienen ordenadas de más productos a menos, así que la
     * grafía que se queda para el borrador es la mayoritaria.
     */
    private Map<String, Terna> ternasDelCatalogo() {
        Map<String, Terna> ternas = new LinkedHashMap<>();
        for (CustomsTernaRow fila : productRepository.customsTernas()) {
            String hs6 = hs6Of(fila.hsCode());
            if (hs6 == null) {
                continue;
            }
            Terna terna = new Terna(hs6, CustomsDeclarationGroupService.normalizeKeyPart(fila.material()),
                    CustomsDeclarationGroupService.normalizeKeyPart(fila.usageCode()), limpio(fila.material()),
                    limpio(fila.usageCode()), fila.productCount() == null ? 0L : fila.productCount());
            ternas.merge(terna.clave(), terna, Terna::sumando);
        }
        return ternas;
    }

    /**
     * Lo que se escribe cuando la partida NO está en la nomenclatura que conocemos: el número y nada
     * más. Es un relleno para que el borrador exista, no una descripción de mercancía.
     */
    private static String enGenerico(String hs6) {
        return "Goods of HS heading " + hs6;
    }

    private static String zhGenerico(String hs6) {
        return "税则号列 " + hs6 + " 项下货品";
    }

    /**
     * ¿La descripción de este grupo sigue siendo el relleno con el que nació?
     *
     * <p>Existe para que NO se pueda firmar. «Goods of HS heading 611212» no describe una mercancía:
     * describe un número, y una declaración así es la que hace que la aduana retenga el paquete.
     * Mientras el grupo esté sin aprobar el relleno es inofensivo —cada producto va en su propia
     * línea y se paga de más—, pero aprobarlo lo pone en la declaración de verdad.
     *
     * <p>Se mira el TEXTO y no si la partida está en la nomenclatura: quien redacte a mano la
     * descripción de una partida que no conocemos tiene que poder firmarla igual.
     */
    public static boolean esRellenoSinRedactar(CustomsDeclarationGroupEntity grupo) {
        String hs6 = grupo.getHs6();
        if (hs6 == null || hs6.isBlank()) {
            return false;
        }
        String ename = grupo.getEname() == null ? "" : grupo.getEname().trim();
        return ename.startsWith(enGenerico(hs6));
    }

    /** Un grupo nuevo: el borrador, sin firma y por tanto sin agrupar todavía. */
    private static CustomsDeclarationGroupEntity borradorDe(Terna terna, Instant ahora) {
        Optional<Hs6DeclarationText> partida = Hs6DeclarationText.byCode(terna.hs6());
        String enPartida = partida.map(Hs6DeclarationText::ename)
                .orElseGet(() -> enGenerico(terna.hs6()));
        String zhPartida = partida.map(Hs6DeclarationText::cname)
                .orElseGet(() -> zhGenerico(terna.hs6()));
        return CustomsDeclarationGroupEntity.builder()
                .hs6(terna.hs6())
                .material(terna.material())
                .usageCode(terna.usageCode())
                .ename(recortada(enPartida + parte(terna.materialCrudo()) + parte(terna.usoCrudo())))
                .cname(recortada(zhPartida))
                .productCount(Math.toIntExact(terna.total()))
                .createdAt(ahora)
                .updatedAt(ahora)
                .build();
    }

    /** Lo único que la siembra toca de un grupo que ya existe. Ver el javadoc de la clase. */
    private void refrescarCuenta(CustomsDeclarationGroupEntity grupo, long total, Instant ahora) {
        int cuenta = Math.toIntExact(total);
        if (Objects.equals(grupo.getProductCount(), cuenta)) {
            return;
        }
        grupo.setProductCount(cuenta);
        grupo.setUpdatedAt(ahora);
        groupRepository.save(grupo);
    }

    private static String parte(String texto) {
        return texto.isBlank() ? "" : SEPARADOR + texto;
    }

    private static String recortada(String texto) {
        return texto.length() <= MAX_DESCRIPCION ? texto : texto.substring(0, MAX_DESCRIPCION);
    }

    /** Recorta y colapsa espacios, pero <b>respeta las mayúsculas</b>: esto se lee, no se compara. */
    private static String limpio(String texto) {
        return texto == null ? "" : texto.trim().replaceAll("\\s+", " ");
    }

    /**
     * Subpartida a 6 dígitos, o {@code null} si el producto no tiene clasificación utilizable. Mismo
     * criterio que {@code CustomsDeclarationGroupService}: lo que no se puede clasificar no se agrupa.
     */
    private static String hs6Of(String hsCode) {
        String digitos = hsCode == null ? "" : hsCode.replaceAll("[^0-9]", "");
        return digitos.length() < 6 ? null : digitos.substring(0, 6);
    }

    /**
     * Una terna ya normalizada, con las grafías en crudo que alimentan el borrador y cuántos productos
     * suma.
     *
     * @param materialCrudo el material tal y como lo escribieron: el borrador lo lee una persona
     */
    private record Terna(String hs6, String material, String usageCode, String materialCrudo, String usoCrudo,
            long total) {

        String clave() {
            return hs6 + '|' + material + '|' + usageCode;
        }

        /** Funde otra grafía de la misma terna: se queda con esta redacción y suma los productos. */
        Terna sumando(Terna otra) {
            return new Terna(hs6, material, usageCode, materialCrudo, usoCrudo, total + otra.total());
        }
    }
}
