package com.nexaplatform.dropshipping.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Levanta un Postgres real (Testcontainers) y lo conecta al contexto vía {@link ServiceConnection},
 * que sobreescribe {@code spring.datasource.*} automáticamente. Como no tenemos el core, esta config
 * vive en el propio repo. Se importa en las bases de integración ({@code BaseIntegration},
 * {@code PersistenceITBase}). El contenedor se reutiliza entre tests porque Spring cachea el contexto.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
}
