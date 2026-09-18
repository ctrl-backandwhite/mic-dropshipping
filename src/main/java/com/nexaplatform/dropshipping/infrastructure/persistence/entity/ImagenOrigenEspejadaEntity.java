package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Memoria de qué URL de origen ya se descargó y dónde quedó guardada.
 *
 * <p>El almacenamiento ya deduplica por CONTENIDO —la clave del objeto es el sha256 del fichero—, así
 * que dos fichas con la misma foto ocupan un solo objeto. Pero eso ahorra disco, no descarga: para
 * calcular ese hash hay que bajarse la imagen igualmente. Y el disco no es el recurso escaso.
 *
 * <p>Lo escaso es el proveedor: responde 403 a quien enlaza sus imágenes desde otra web y limita por
 * tasa a quien insiste. Con 100.000 productos y unas 52 imágenes por ficha son 5,2 millones de
 * descargas; las que se repiten entre fichas del mismo vendedor —tablas de tallas, fotos de material,
 * de embalaje— se sirven desde aquí sin tocar la red.
 *
 * <p>No hereda de {@link BaseEntity} a propósito: su clave no es un identificador nuestro sino el
 * sha256 de la propia URL. Se usa el hash y no la URL porque un índice B-tree de Postgres no admite
 * entradas de más de unos 2.700 bytes, y una URL de 800 caracteres en UTF-8 puede pasarse.
 */
@Entity
@Table(name = "imagen_origen_espejada")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImagenOrigenEspejadaEntity {

    @Id
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(name = "url_origen", nullable = false, length = 800)
    private String urlOrigen;

    @Column(name = "cdn_url", nullable = false, length = 800)
    private String cdnUrl;

    @Column(name = "bytes")
    private Long bytes;

    @Column(name = "hash", length = 80)
    private String hash;

    @Column(name = "ancho")
    private Integer ancho;

    @Column(name = "alto")
    private Integer alto;

    /** Si lo guardado ya pasó por el compresor. La segunda pasada lo usa para saber qué le falta. */
    @Column(name = "comprimida", nullable = false)
    private boolean comprimida;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "usada_en", nullable = false)
    private Instant usadaEn;

    /** Cuántas veces se ha reaprovechado. Es la medida de si esta tabla está sirviendo de algo. */
    @Column(name = "veces", nullable = false)
    private long veces;
}
