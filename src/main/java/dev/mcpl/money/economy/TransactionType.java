package dev.mcpl.money.economy;

/** Why a balance changed. Stored by name in the {@code type} column of {@code money_transactions}. */
public enum TransactionType {
    /** Account created with the starting balance. */
    CREATE,
    /** {@code /money pay} between two players. */
    PAY,
    /** {@code /money give}. */
    GIVE,
    /** {@code /money take}. */
    TAKE,
    /** {@code /money set}. The amount is the new balance. */
    SET,
    /** {@code /money reset}. The amount is the new balance (the starting balance). */
    RESET,
    /** Deposit made by another plugin through Vault. */
    VAULT_DEPOSIT,
    /** Withdrawal made by another plugin through Vault. */
    VAULT_WITHDRAW
}
