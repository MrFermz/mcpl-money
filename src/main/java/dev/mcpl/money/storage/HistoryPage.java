package dev.mcpl.money.storage;

import dev.mcpl.money.economy.Transaction;
import java.util.List;

/** One page of a player's transactions, newest first. {@code page} is clamped to {@code 1..pages}. */
public record HistoryPage(int page, int pages, int total, List<Transaction> entries) {
}
