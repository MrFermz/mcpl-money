package dev.mcpl.money.economy;

import java.math.BigDecimal;
import java.util.UUID;

/** A player's account. Immutable; {@link Economy} replaces it whenever the balance changes. */
public record Account(UUID uuid, String name, BigDecimal balance) {

    public Account withBalance(BigDecimal newBalance) {
        return new Account(uuid, name, newBalance);
    }
}
