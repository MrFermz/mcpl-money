package dev.mcpl.money.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the transaction log. Money moves from {@code from} to {@code to}; either side is
 * null when money is created or removed (for example {@link TransactionType#GIVE} has no
 * {@code from}). Names are stored as they were at the time, so the log stays readable after a
 * player renames.
 *
 * @param id     database id, or 0 before the row is saved
 * @param actor  who caused it: a player or console name, {@code Vault}, or {@code SYSTEM}
 */
public record Transaction(
        long id,
        Instant time,
        TransactionType type,
        UUID fromUuid,
        String fromName,
        UUID toUuid,
        String toName,
        BigDecimal amount,
        String actor) {
}
