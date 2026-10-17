# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Rules (always follow)

1. **Never stage or commit.** Don't run `git add`, `git commit`, `git commit --amend` or `git push`, and don't change the index in any other way. When a change is done, list the files you changed and write a proposed commit message in your reply. The maintainer stages and commits the change.
   - Format: a summary in the imperative, 72 characters or fewer; a blank line; then a body explaining why, wrapped at 72 characters.
2. **Check the README on every change.** Before handing over a change, check whether `README.md` needs updating and update it in the same change. The table under "Keep the README up to date" in `README.md` maps each kind of change to its section. If nothing needs changing, say "README checked, no update needed".
3. **Keep 100% test coverage.** Every change to `src/main/java` needs tests. `./gradlew build` fails if line or branch coverage drops below 100% (JaCoCo). Don't lower the threshold, and don't exclude code from coverage to make it pass.

At the end of every change, in this order: `./gradlew build` (runs tests and the coverage check) → README check → list of changed files → proposed commit message.

## Project

`mcpl-money` is a player economy plugin for **Paper 26.3**, written in **Java 25**.

- Build: `./gradlew build` (Windows: `gradlew.bat build`). The jar is written to `build/libs/`.
- Tests: `./gradlew test`. Coverage check: `./gradlew check`. One class: `./gradlew test --tests "dev.mcpl.money.economy.EconomyTest"`.
- Needs JDK 25. If `java` isn't on PATH, set `JAVA_HOME` before building.
- Gradle comes from the wrapper (9.8.1), pinned with `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`. When you upgrade Gradle, pass `--gradle-distribution-sha256-sum` so the checksum stays set.
- `paper-api`, Vault API (`com.github.MilkBowl:VaultAPI`, from jitpack) and HikariCP are `compileOnly`. The runtime libraries (HikariCP, sqlite-jdbc, postgresql) are **not shaded**. They're listed once in `runtimeLibraries` in `build.gradle.kts`, which fills `libraries:` in `plugin.yml`, and Paper downloads them at server start. Change versions there only.
- The plugin has **not been run on a live server**. Passing tests are not proof it works in-game. Say so, and don't claim otherwise.

## Code layout

- `MoneyPlugin`: the entry point. Refuses to start in offline mode, opens the database, loads accounts, registers the join listener, the Vault hook and `/money`.
- `MoneyConfig`: immutable snapshot of `config.yml`. Formats amounts and builds MiniMessage messages. Rebuilt on `/money reload`. Throws `IllegalArgumentException` on invalid config.
- `economy/Economy`: all accounts in memory, the source of truth at runtime. Every successful change emits an `Economy.Change` (updated accounts plus a `Transaction`) to a listener. Methods are `synchronized` because Vault can call from any thread.
- `storage/*`: `SqlStorage` does plain JDBC (shared SQL; dialect differences live in `StorageType`). `Database` runs it on one background thread so writes stay ordered. Each `Change` is saved in one DB transaction.
- `vault/*`: `VaultEconomy` implements Vault's `Economy`. `VaultHook` registers it, and is only touched when the Vault plugin is present, so Vault classes never load without Vault.
- `command/MoneyCommand`: the whole `/money` Brigadier tree, registered through `LifecycleEvents.COMMANDS` (not declared in `plugin.yml`).

## Conventions

- **Money is always `BigDecimal`**, never `double`/`float`, except at the Vault API boundary. Round with `Economy.normalize()` (round down to `currency.decimals`). Amounts typed in commands are parsed as `BigDecimal` from a string argument.
- Every balance change goes through `Economy` so it's logged. Pick or add a `TransactionType`.
- Every table has an `id` auto-increment primary key; `money_accounts.uuid` is `UNIQUE` and is what upserts conflict on.
- Dates are real timestamps: `TIMESTAMPTZ` in PostgreSQL, UTC `DATETIME` text (`yyyy-MM-dd HH:mm:ss.SSS`) in SQLite, which has no date type. Always bind and read them through `StorageType.bindTime()`/`readTime()`, never as epoch numbers.
- Schema changes: edit `StorageType` and the "Database schema" section of the README together. Tables are created with `IF NOT EXISTS`. There's no migration system yet, so changing an existing column needs a migration plan first.
- Every message shown to players goes in `config.yml` under `messages.` and is sent with `MoneyConfig.message(key, placeholders...)`.
- Use `Placeholder.unparsed` for player-supplied text, such as names, so players can't inject MiniMessage tags.
- Permission nodes use the `money.` prefix and are declared in `plugin.yml`. Constants are in `MoneyCommand`. Viewing another player's data is op-only by default (`money.balance.others`, `money.history.others`). `PluginYmlTest` pins every default, so a default change must update that test and the README Permissions table.
- In `/money history`, the `page` node is registered before the `player` node on purpose: Brigadier picks the first child that parses, so numbers are pages. Player arguments use `StringArgumentType.string()` so digit-only names can be quoted.
- Data safety: never start without the real balances. If the database can't be opened, the plugin disables itself (see `MoneyPlugin.onEnable`).

## Tests

- JUnit 6, Mockito (loaded as a `-javaagent`), AssertJ. JaCoCo enforces 100% line and branch coverage.
- Commands are tested through a real Brigadier `CommandDispatcher` with mocked `CommandSourceStack`/`Player`. `TestSupport.messages(mock)` returns the plain-text messages a mock received.
- `SqlStorageContract` runs the same storage tests against SQLite (temp file) and PostgreSQL (`EmbeddedPostgres`, real PostgreSQL 18, no Docker).
- `MoneyPluginTest` runs the plugin as a Mockito mock with `CALLS_REAL_METHODS`, with `getServer()`, `getConfig()` and similar stubbed.
- Stand-ins for Paper internals, so the plugin can be loaded without a server:
  - `src/test/java/io/papermc/.../TestLifecycleEventTypeProvider` (for `LifecycleEvents`)
  - `testsupport/TestInternalApiBridge` and `testsupport/TestPluginClassLoader` (for `JavaPlugin`'s constructor)
  - All are registered through `src/test/resources/META-INF/services`. After a Paper upgrade, fix compile errors there first.
