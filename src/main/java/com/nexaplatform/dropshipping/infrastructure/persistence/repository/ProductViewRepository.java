package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductViewEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Historial de fichas visitadas por usuario. */
public interface ProductViewRepository extends JpaRepository<ProductViewEntity, UUID> {

    /**
     * Anota que el usuario ha abierto la ficha: la inserta si es la primera vez y, si ya estaba, mueve la
     * fecha y suma la visita.
     *
     * <p>Va como UPSERT nativo —una sola sentencia— y no como «mirar si existe, y luego insertar o
     * actualizar» por dos motivos. Abrir la misma ficha en dos pestañas es de lo más normal: dos hilos que
     * miran a la vez ven que no existe y los dos insertan, y el segundo se estrella contra el UNIQUE
     * dejando la transacción para atrás. Y con el contador, dos «leer, sumar uno y guardar» simultáneos
     * verían el mismo valor y una escritura pisaría a la otra. Resuelto en la base, ni hay carrera ni se
     * pierde ninguna de las dos visitas.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO product_view (id, user_id, product_id, viewed_at, view_count, created_at, updated_at)
            VALUES (gen_random_uuid(), :userId, :productId, :now, 1, :now, :now)
            ON CONFLICT (user_id, product_id) DO UPDATE
            SET viewed_at = EXCLUDED.viewed_at,
                view_count = product_view.view_count + 1,
                updated_at = EXCLUDED.viewed_at
            """, nativeQuery = true)
    void registrarVisita(@Param("userId") UUID userId, @Param("productId") UUID productId,
            @Param("now") Instant now);

    /** IDs del historial del usuario, de la visita más reciente a la más antigua. */
    @Query("select v.productId from ProductViewEntity v where v.userId = :userId order by v.viewedAt desc")
    List<UUID> findProductIdsByUserId(@Param("userId") UUID userId, Limit limit);

    /** IDs visitados por el usuario DENTRO de la ventana del correo, del más reciente al más antiguo. */
    @Query("select v.productId from ProductViewEntity v where v.userId = :userId and v.viewedAt >= :since "
            + "order by v.viewedAt desc")
    List<UUID> findProductIdsByUserIdSince(@Param("userId") UUID userId, @Param("since") Instant since,
            Limit limit);

    /**
     * Usuarios con alguna visita en la ventana. Es el punto de partida del correo: se parte de QUIÉN ha
     * visitado algo, no de la lista entera de usuarios, para no recorrer toda la base preguntando por un
     * historial que la inmensa mayoría no tiene esos días.
     */
    @Query("select distinct v.userId from ProductViewEntity v where v.viewedAt >= :since")
    List<UUID> findUserIdsWithViewsSince(@Param("since") Instant since);

    /** Purga de retención: borra las visitas anteriores a la fecha dada. */
    @Modifying(clearAutomatically = true)
    @Query("delete from ProductViewEntity v where v.viewedAt < :limite")
    int deleteByViewedAtBefore(@Param("limite") Instant limite);

    /**
     * Deja en el historial del usuario solo las {@code tope} visitas más recientes; el resto se borra.
     *
     * <p>El historial es una VENTANA, no un archivo: la ficha número cincuenta y uno empuja fuera a la
     * más antigua. Antes solo había un tope de lectura —se guardaban todas y se leían las primeras—, así
     * que la tabla crecía sin fin con filas que nadie iba a mirar nunca y que solo desaparecían a los
     * noventa días.
     *
     * <p>Va en SQL nativo y en una sola sentencia a propósito: traer los identificadores sobrantes para
     * borrarlos después son dos viajes y una carrera —dos pestañas del mismo usuario podrían borrar cada
     * una lo que la otra acaba de decidir conservar—. La subconsulta ordena por el mismo índice que usa
     * la lectura, así que el coste es el de leer cincuenta filas.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM product_view WHERE user_id = :userId AND id NOT IN ("
            + "SELECT id FROM product_view WHERE user_id = :userId ORDER BY viewed_at DESC LIMIT :tope)",
            nativeQuery = true)
    int podarExcedente(@Param("userId") UUID userId, @Param("tope") int tope);
}
