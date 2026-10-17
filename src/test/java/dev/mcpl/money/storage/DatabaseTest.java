package dev.mcpl.money.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mcpl.money.economy.Economy;
import dev.mcpl.money.economy.Transaction;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    final SqlStorage storage = mock(SqlStorage.class);
    final List<LogRecord> logs = new ArrayList<>();
    Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private Database database() {
        return new Database(storage, Executors.newSingleThreadExecutor(), logger, 5);
    }

    @Test
    void saveWritesInBackgroundAndCloseWaitsForIt() throws Exception {
        Database database = database();
        Economy.Change change = new Economy.Change(List.of(), null);

        database.save(change);
        database.close();

        verify(storage).apply(change);
        verify(storage).close();
        assertThat(logs).isEmpty();
    }

    @Test
    void failedSaveIsLogged() throws Exception {
        Economy.Change change = new Economy.Change(List.of(), null);
        doThrow(new SQLException("disk full")).when(storage).apply(change);
        Database database = database();

        database.save(change);
        database.close();

        assertThat(logs).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(record.getThrown()).hasMessage("disk full");
        });
    }

    @Test
    void historyCalculatesPagesAndOffset() throws Exception {
        List<Transaction> rows = List.of(mock(Transaction.class));
        when(storage.countHistory(A)).thenReturn(25);
        when(storage.history(A, 10, 10)).thenReturn(rows);
        Database database = database();

        HistoryPage page = database.history(A, 2, 10).join();

        assertThat(page).isEqualTo(new HistoryPage(2, 3, 25, rows));
        database.close();
    }

    @Test
    void historyClampsPageToRange() throws Exception {
        when(storage.countHistory(A)).thenReturn(25);
        when(storage.history(A, 10, 20)).thenReturn(List.of());
        Database database = database();

        assertThat(database.history(A, 99, 10).join().page()).isEqualTo(3);
        database.close();
    }

    @Test
    void emptyHistoryHasOnePage() throws Exception {
        when(storage.countHistory(A)).thenReturn(0);
        when(storage.history(A, 10, 0)).thenReturn(List.of());
        Database database = database();

        assertThat(database.history(A, 5, 10).join()).isEqualTo(new HistoryPage(1, 1, 0, List.of()));
        database.close();
    }

    @Test
    void historyErrorCompletesExceptionally() throws Exception {
        when(storage.countHistory(A)).thenThrow(new SQLException("gone"));
        Database database = database();

        assertThatThrownBy(() -> database.history(A, 1, 10).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("gone");
        database.close();
    }

    @Test
    void closeLogsWhenWritesDoNotFinishInTime() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(false);

        new Database(storage, executor, logger, 5).close();

        verify(executor).shutdown();
        verify(storage).close();
        assertThat(logs).singleElement().satisfies(r -> assertThat(r.getMessage()).startsWith("Timed out"));
    }

    @Test
    void closeKeepsInterruptFlagWhenInterrupted() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.awaitTermination(anyLong(), any())).thenThrow(new InterruptedException());

        new Database(storage, executor, logger, 5).close();

        assertThat(Thread.interrupted()).isTrue(); // also clears the flag for later tests
        verify(storage).close();
        assertThat(logs).singleElement().satisfies(r -> assertThat(r.getMessage()).startsWith("Interrupted"));
    }
}
