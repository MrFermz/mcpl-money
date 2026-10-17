package dev.mcpl.money.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageTypeTest {

    @Test
    void parsesConfigValues() {
        assertThat(StorageType.fromConfig("sqlite")).isEqualTo(StorageType.SQLITE);
        assertThat(StorageType.fromConfig(" PostgreSQL ")).isEqualTo(StorageType.POSTGRESQL);
        assertThat(StorageType.fromConfig("postgres")).isEqualTo(StorageType.POSTGRESQL);
        assertThatThrownBy(() -> StorageType.fromConfig("mysql"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown storage.type 'mysql', expected sqlite or postgresql");
    }

    @Test
    void driversAndSchema() {
        assertThat(StorageType.SQLITE.driverClass()).isEqualTo("org.sqlite.JDBC");
        assertThat(StorageType.POSTGRESQL.driverClass()).isEqualTo("org.postgresql.Driver");
        assertThat(StorageType.SQLITE.schema()).hasSize(4);
        assertThat(StorageType.POSTGRESQL.schema()).allMatch(sql -> sql.startsWith("CREATE"));
    }
}
