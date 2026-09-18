package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ImagenOrigenEspejadaEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /** Cuántas descargas se ha ahorrado esta tabla: la suma de reaprovechamientos por encima del primero. */
    @Query("SELECT COALESCE(SUM(i.veces - 1), 0) FROM ImagenOrigenEspejadaEntity i")
    long descargasAhorradas();
}
