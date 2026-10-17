package dev.mcpl.money.storage;

import dev.mcpl.money.economy.Economy;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs every database call on one background thread, so writes happen in the same order as
 * the balance changes and never block the server thread. Reads use the same thread, so they
 * always see every earlier write.
 */
public final class Database implements AutoCloseable {

    private final SqlStorage storage;
    private final ExecutorService executor;
    private final Logger logger;
    private final long shutdownTimeoutSeconds;

    public Database(SqlStorage storage, ExecutorService executor, Logger logger, long shutdownTimeoutSeconds) {
        this.storage = storage;
        this.executor = executor;
        this.logger = logger;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /** Queues a change to be saved. Errors are logged, since the change has already happened in memory. */
    public void save(Economy.Change change) {
        executor.execute(() -> {
            try {
                storage.apply(change);
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Could not save change to the database: " + change, e);
            }
        });
    }

    public CompletableFuture<HistoryPage> history(UUID uuid, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int total = storage.countHistory(uuid);
                int pages = Math.max(1, (total + pageSize - 1) / pageSize);
                int clamped = Math.clamp(page, 1, pages);
                return new HistoryPage(clamped, pages, total,
                        storage.history(uuid, pageSize, (clamped - 1) * pageSize));
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Waits for queued writes to finish, then closes the connection pool. */
    @Override
    public void close() throws Exception {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                logger.severe("Timed out waiting for database writes; some recent changes may not be saved");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.severe("Interrupted while waiting for database writes; some recent changes may not be saved");
        }
        storage.close();
    }
}
