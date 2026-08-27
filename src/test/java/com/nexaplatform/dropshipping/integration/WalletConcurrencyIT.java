package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletJpaRepositoryAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El doble gasto solo aparece con peticiones SIMULTÁNEAS, así que solo lo caza una prueba que las lance de
 * verdad y contra un Postgres real.
 *
 * <p>Contexto: el bloqueo pesimista de la wallet se añadió en el pentest del 11-ago-2026 y se dio por
 * bueno sin una prueba como esta. El 14-ago, ocho checkouts simultáneos con saldo para uno solo dejaron
 * cuatro pedidos pagados cobrando uno: los apuntes concurrentes escribían todos el mismo saldo final. La
 * suite entera estaba en verde, porque todas las pruebas de la wallet eran secuenciales y con dobles.
 *
 * <p>Ataca el método REAL del repositorio que usa producción, no un SQL escrito para la ocasión: si alguien
 * vuelve a mover el saldo con un leer-comprobar-escribir, aquí se cae.
 */
// SIN transacción de test: los hilos atacan por conexiones distintas y no verían una wallet creada dentro
// de una transacción que nunca se confirma (el primer intento devolvió cero débitos aplicados justo por
// eso). Los datos quedan escritos, pero el contenedor de Testcontainers muere con la prueba.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletConcurrencyIT extends PersistenceITBase {

    private static final long SALDO_INICIAL = 2000L;
    private static final long IMPORTE = 1446L;
    private static final int HILOS = 8;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WalletJpaRepositoryAdapter walletJpa;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("ocho débitos simultáneos con saldo para uno: solo prospera uno y el saldo nunca queda negativo")
    void ochoDebitosSimultaneosSoloCobranUno() throws Exception {
        UUID[] ids = prepararWalletConSaldo();
        UUID userId = ids[0];
        UUID walletId = ids[1];

        List<Callable<Boolean>> intentos = new ArrayList<>();
        for (int i = 0; i < HILOS; i++) {
            intentos.add(() -> debitarSiAlcanza(userId));
        }

        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        List<Future<Boolean>> futuros = pool.invokeAll(intentos);
        int prosperaron = 0;
        for (Future<Boolean> f : futuros) {
            if (Boolean.TRUE.equals(f.get())) {
                prosperaron++;
            }
        }
        pool.shutdown();

        Long saldoFinal = jdbc.queryForObject(
                "SELECT balance_usd_cents FROM wallet WHERE id = ?", Long.class, walletId);

        assertThat(prosperaron)
                .as("con %d de saldo y débitos de %d, solo puede prosperar uno", SALDO_INICIAL, IMPORTE)
                .isEqualTo(1);
        assertThat(saldoFinal)
                .as("el saldo tiene que reflejar exactamente el único débito aplicado")
                .isEqualTo(SALDO_INICIAL - IMPORTE);
    }

    @Test
    @DisplayName("el saldo nunca queda en negativo aunque se lancen muchos más débitos de los que caben")
    void elSaldoNuncaQuedaNegativo() throws Exception {
        UUID[] ids = prepararWalletConSaldo();
        UUID userId = ids[0];
        UUID walletId = ids[1];

        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        List<Callable<Boolean>> intentos = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            intentos.add(() -> debitarSiAlcanza(userId));
        }
        pool.invokeAll(intentos);
        pool.shutdown();

        Long saldoFinal = jdbc.queryForObject(
                "SELECT balance_usd_cents FROM wallet WHERE id = ?", Long.class, walletId);
        assertThat(saldoFinal).as("el saldo no puede bajar de cero en ningún caso").isGreaterThanOrEqualTo(0L);
    }

    /**
     * Se ataca el MÉTODO REAL del repositorio, no un SQL escrito aquí: si alguien vuelve a mover el saldo
     * con un leer-comprobar-escribir, esta prueba se cae. Atacar la base por libre solo demostraría que
     * PostgreSQL sabe hacer un UPDATE condicional, que nunca estuvo en duda.
     */
    private boolean debitarSiAlcanza(UUID userId) {
        // Cada hilo abre SU transacción, igual que cada petición HTTP en producción. Es justo lo que hace
        // aparecer el problema: varias transacciones vivas a la vez sobre el mismo saldo.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return Boolean.TRUE.equals(tx.execute(estado -> walletJpa.applyBalanceDelta(userId, -IMPORTE) == 1));
    }

    /** Usuario y wallet mínimos creados a mano: la prueba va sobre el movimiento del saldo, no sobre el alta. */
    private UUID[] prepararWalletConSaldo() {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role, active, created_at, updated_at)"
                        + " VALUES (?, ?, 'USER', true, now(), now())",
                userId, "concurrencia-" + userId + "@example.com");
        jdbc.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents, currency_default,"
                        + " status, created_at, updated_at) VALUES (?, ?, ?, 0, 'USD', 'ACTIVE', now(), now())",
                walletId, userId, SALDO_INICIAL);
        return new UUID[]{userId, walletId};
    }
}
