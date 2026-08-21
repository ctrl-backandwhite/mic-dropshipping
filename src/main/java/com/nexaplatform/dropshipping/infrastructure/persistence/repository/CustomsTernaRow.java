package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

/**
 * Una terna aduanera del catálogo —partida, material y uso— con cuántos productos la comparten.
 *
 * <p>Sale de la base <b>sin normalizar</b>, tal y como se tecleó. Normalizar en SQL obligaría a repetir
 * en la consulta el mismo criterio que aplica
 * {@code CustomsDeclarationGroupService.normalizeKeyPart}, y dos copias de esa regla acaban
 * separándose: el día que difieran, la siembra crearía un grupo que la resolución no encuentra y la
 * agrupación dejaría de funcionar en silencio. Se agrupa en Java, con la única copia que hay.
 *
 * @param productCount lo devuelve {@code COUNT(*)}, así que llega como {@code Long}
 */
public record CustomsTernaRow(String hsCode, String material, String usageCode, Long productCount) {
}
