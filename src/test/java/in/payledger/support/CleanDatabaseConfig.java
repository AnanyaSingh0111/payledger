package in.payledger.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Every test application context starts from an empty schema built by the real Flyway
 * migrations, so the tests also prove the migrations work (including the CHECK constraints
 * and the append-only triggers).
 */
@TestConfiguration
public class CleanDatabaseConfig {

    @Bean
    public FlywayMigrationStrategy cleanMigrateStrategy() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
