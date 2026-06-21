package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanJpaRepositoryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke de la Fase 0: confirma que el contenedor Postgres arranca, que Liquibase crea el esquema y
 * que Hibernate lo VALIDA (ddl-auto=validate ⇒ si una entidad no casa con las migraciones, el
 * contexto ni arranca). También ejercita un finder derivado contra el motor real.
 */
class SchemaMigrationIT extends PersistenceITBase {

    @Autowired
    SubscriptionPlanJpaRepositoryAdapter plans;

    @Autowired
    CustomerSubscriptionJpaRepositoryAdapter subscriptions;

    @Test
    void liquibaseSchemaLoadsAndQueriesRun() {
        // Si el esquema no validara contra las entidades, el contexto fallaría al arrancar.
        assertThat(plans.findAll()).isNotNull();

        // Finder derivado (billing): sin filas devuelve vacío, pero la consulta SQL debe ejecutar bien.
        Optional<CustomerSubscriptionEntity> none = subscriptions.findByStripeSubscriptionId("sub_does_not_exist");
        assertThat(none).isEmpty();
    }
}
