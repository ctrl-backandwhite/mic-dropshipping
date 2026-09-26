package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ImagenOrigenEspejadaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Acceso a la memoria de URLs ya descargadas, para no volver a bajar lo que ya tenemos. */
public interface ImagenOrigenEspejadaRepository extends JpaRepository<ImagenOrigenEspejadaEntity, String> {

    /**
     * Anota que esta URL se ha vuelto a aprovechar.
     *
     * <p>Va como UPDATE directo y no leyendo-modificando-guardando porque lo llaman varios hilos a la
     * vez sobre la misma fila: la foto de la tabla de tallas de un vendedor se pide desde muchas fichas
     * al mismo tiempo. Sumar en la base evita perder cuentas y evita el cerrojo optimista.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ImagenOrigenEspejadaEntity i SET i.veces = i.veces + 1, i.usadaEn = :ahora "
            + "WHERE i.urlHash = :urlHash")
    void anotaUso(@Param("urlHash") String urlHash, @Param("ahora") Instant ahora);

    /**
     * Mueve la memoria al objeto nuevo cuando el viejo deja de existir.
     *
     * <p><b>Por qué hace falta (25-sep-2026).</b> Esta tabla es lo que evita volver a bajar una foto que
     * ya se bajó: guarda {@code url_de_origen → cdn_url}. Cuando la compresión sustituye el original por
     * el {@code .webp} y borra el original, la memoria se queda apuntando a un objeto que ya no existe.
     *
     * <p>Y entonces reencolar la imagen NO la arregla: el espejador consulta esta tabla, encuentra una
     * URL, y da la imagen por espejada <b>sin descargar ni subir nada</b>. La fila vuelve a MIRRORED
     * apuntando al mismo objeto muerto. Medido en preproducción: 8.083 entradas envenenadas así, y 806
     * imágenes que el auto-sanado reencolaba una y otra vez sin que nada cambiara.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ImagenOrigenEspejadaEntity i SET i.cdnUrl = :nueva WHERE i.cdnUrl = :anterior")
    int reapunta(@Param("anterior") String anterior, @Param("nueva") String nueva);

    /** Cuántas descargas se ha ahorrado esta tabla: la suma de reaprovechamientos por encima del primero. */
    @Query("SELECT COALESCE(SUM(i.veces - 1), 0) FROM ImagenOrigenEspejadaEntity i")
    long descargasAhorradas();
}
