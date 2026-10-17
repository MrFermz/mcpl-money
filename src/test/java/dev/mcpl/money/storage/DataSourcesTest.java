package dev.mcpl.money.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DataSourcesTest {

    @Test
    void sqliteUsesFileInDataFolderAndOneConnection() {
        HikariConfig config = DataSources.config(
                new StorageSettings(StorageType.SQLITE, "money.db", null, 0, null, null, null, 9), Path.of("data"));

        assertThat(config.getJdbcUrl())
                .startsWith("jdbc:sqlite:")
                .contains("money.db")
                .endsWith("?journal_mode=WAL&busy_timeout=5000");
        assertThat(Path.of(config.getJdbcUrl().substring("jdbc:sqlite:".length()).split("[?]")[0])).isAbsolute();
        assertThat(config.getMaximumPoolSize()).isEqualTo(1);
        assertThat(config.getDriverClassName()).isEqualTo("org.sqlite.JDBC");
        assertThat(config.getPoolName()).isEqualTo("mcpl-money");
    }

    @Test
    void postgresUsesHostPortAndCredentials() {
        HikariConfig config = DataSources.config(
                new StorageSettings(StorageType.POSTGRESQL, null, "db.local", 6543, "mc", "user", "pw", 6), Path.of("data"));

        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:postgresql://db.local:6543/mc");
        assertThat(config.getUsername()).isEqualTo("user");
        assertThat(config.getPassword()).isEqualTo("pw");
        assertThat(config.getMaximumPoolSize()).isEqualTo(6);
        assertThat(config.getDriverClassName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    void postgresPoolHasAtLeastOneConnection() {
        HikariConfig config = DataSources.config(
                new StorageSettings(StorageType.POSTGRESQL, null, "h", 1, "d", "u", "p", 0), Path.of("data"));

        assertThat(config.getMaximumPoolSize()).isEqualTo(1);
    }
}
