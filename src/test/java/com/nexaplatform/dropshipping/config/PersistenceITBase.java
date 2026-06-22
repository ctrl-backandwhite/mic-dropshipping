package com.nexaplatform.dropshipping.config;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base de los tests de integración de PERSISTENCIA (Fase 3): slice {@link DataJpaTest} sobre un
 * Postgres real (Testcontainers), con el esquema creado por Liquibase ({@code replace = NONE} evita
 * el datasource embebido). Valida el JPQL/{@code @Query}/{@code @Modifying} contra el motor real,
 * que es donde fallan en silencio frente a H2.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestContainersConfiguration.class, AuditingConfig.class})
public abstract class PersistenceITBase {
}
