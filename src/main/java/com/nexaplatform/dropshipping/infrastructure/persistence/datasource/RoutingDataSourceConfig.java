package com.nexaplatform.dropshipping.infrastructure.persistence.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Plan 300k req/min — Fase 3: routing transparente lectura/escritura.
 * <p>
 * Cualquier método anotado con {@code @Transactional(readOnly = true)}
 * (todos los GETs de catálogo / PDP / search / dashboard) usa
 * la conexión hacia la replica. Las mutaciones (POST/PUT/DELETE) usan
 * primary. La decisión la toma {@link RoutingDataSource} en función del
 * flag de transacción Spring.
 * <p>
 * En local apunto a la misma BD (no hay replica todavía). En prod cambias
 * {@code DB_REPLICA_URL} a la URL del nodo read-only y listo, sin tocar
 * código de servicios.
 * <p>
 * Activable con {@code nexadrop.db.replica-enabled=true}. Por defecto FALSE
 * en local para no duplicar el pool si sólo hay un Postgres.
 */
@Configuration
@ConditionalOnProperty(prefix = "nexadrop.db", name = "replica-enabled", havingValue = "true")
public class RoutingDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    HikariDataSource primaryDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource-replica")
    HikariDataSource replicaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    DataSource routingDataSource(@Qualifier("primaryDataSource") DataSource primary,
            @Qualifier("replicaDataSource") DataSource replica) {
        RoutingDataSource routing = new RoutingDataSource();
        Map<Object, Object> map = new HashMap<>();
        map.put(DataSourceRole.PRIMARY, primary);
        map.put(DataSourceRole.REPLICA, replica);
        routing.setTargetDataSources(map);
        routing.setDefaultTargetDataSource(primary);
        // LazyConnectionDataSourceProxy: la conexión sólo se reserva cuando
        // efectivamente se ejecuta un statement, lo que evita ocupar conexión
        // del pool durante el setup de la transacción.
        return new LazyConnectionDataSourceProxy(routing);
    }

    public enum DataSourceRole {
        PRIMARY, REPLICA
    }

    /**
     * Decide PRIMARY vs REPLICA según la transacción actual.
     */
    public static class RoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            // Si Spring marcó esta tx como readOnly, vamos a la replica.
            // Fuera de tx → primary (típicamente acceso directo desde Hikari).
            boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            return readOnly ? DataSourceRole.REPLICA : DataSourceRole.PRIMARY;
        }
    }
}
