package dev.mcpl.money.economy;

/** Outcome of a balance change. Nothing is changed unless the result is {@link #SUCCESS}. */
public enum EconomyResult {
    SUCCESS("Success"),
    /** The amount is zero or negative after rounding (or negative for a set). */
    INVALID_AMOUNT("Invalid amount"),
    NO_ACCOUNT("No such account"),
    /** The account paying or being charged does not hold enough money. */
    INSUFFICIENT_FUNDS("Insufficient funds"),
    /** The receiving account would go above {@code max-balance}. */
    BALANCE_LIMIT("Balance limit reached"),
    /** A transfer where the sender and receiver are the same account. */
    SAME_ACCOUNT("Cannot transfer to the same account");

    private final String description;

    EconomyResult(String description) {
        this.description = description;
    }

    /** Short English description, used as the Vault error message. */
    public String description() {
        return description;
    }
}
