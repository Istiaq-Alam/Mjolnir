# Mjolnir 1.0.0

Custom two-mode Trident weapon for Paper **26.1.2** (Java 25).

## 1. Build on Linux

You need a **Gradle 9.1+** install (Gradle only added Java 25 toolchain support in 9.1) and a JDK — Gradle's toolchain support will auto-provision JDK 25 for compiling even if your machine's default JDK is older, as long as auto-download is enabled (default).

```bash
java --version          # any JDK Gradle itself can run on (17+ is fine for running Gradle)
gradle --version        # must be 9.1 or newer

cd Mjolnir
gradle build
```

If you don't have a system Gradle install, generate the wrapper once (requires any local Gradle 9.1+, or the Gradle install shipped with an IDE) and commit it to the project so future builds don't need Gradle preinstalled:

```bash
gradle wrapper --gradle-version 9.5.1
./gradlew build
```

## 2. Where the jar appears

```text
Mjolnir/build/libs/Mjolnir-1.0.0.jar
```

## 3. Install on your Paper server

```bash
cp build/libs/Mjolnir-1.0.0.jar /path/to/your/server/plugins/
```

Then restart (or `/reload confirm`, though a full restart is safer) the server. Paper 26.1.2 requires **Java 25** to run at all, so make sure the server JVM itself is Java 25, separate from whatever JDK you used to build.

## 4. Testing checklist

1. Start the server and check the console for `Mjolnir 1.0.0 enabled for Paper 26.1.2.` with no errors/stack traces.
2. As an op (or a player with `mjolnir.admin`), run:
   ```text
   /mjolnir give <yourname>
   ```
3. You should receive a Trident named **Mjolnir** in 🌊 Travel Mode (Riptide X, Unbreaking X, Mending I) with lore showing the mode and switch hint.
4. Hold it, then **Sneak + Right Click**:
   - Actionbar shows `⚡ Mjolnir: FIGHTING MODE`.
   - Enchants swap to Channeling I, Impaling X, Loyalty X, Unbreaking X, Mending I (Riptide gone).
   - A thunderstorm starts in that world; particles/sound play.
   - Trident does **not** throw and you are **not** launched by Riptide during the switch.
5. Sneak + Right Click again within the cooldown window: you should get `⏳ Mjolnir recharging: Xs` and nothing else happens.
6. Wait out the cooldown, switch back to Travel Mode: Riptide returns, Channeling/Impaling/Loyalty are removed, actionbar shows `🌊 Mjolnir: TRAVEL MODE`.
7. Throw the trident during the storm while in Fighting Mode and hit a mob/player — Channeling should strike lightning on hit (vanilla mechanic, works automatically since the world is thundering).
8. Try to damage the item's durability (e.g. via an anvil combine attempt, or just heavy use) — the durability bar should never move; it's marked unbreakable and `PlayerItemDamageEvent` is also cancelled as a backstop.
9. Restart the server with the item still in an inventory — mode and identity should persist (stored in the item's PDC, not runtime state).
10. Give it to a second player and confirm a plain trident renamed to "Mjolnir" via an anvil does **not** trigger mode switching (PDC-based identity check).

## 5. Notable design decisions / limitations

- **Riptide X / Impaling X / Loyalty X / Unbreaking X** are enchantment level 10, which exceeds vanilla's normal caps. This only works because the enchantments are applied directly via `ItemMeta.addEnchant(..., ignoreLevelRestriction=true)` at the API level, not through an enchanting table — that's the reliable way to get "Riptide X" as specified.
- **Weather is world-scoped, not player-scoped.** Minecraft has no concept of "storm just for one player" without client-side packet trickery (out of scope for a lightweight plugin), so switching to Fighting Mode starts a real thunderstorm in the world the player is standing in. The `startThunderstorm` logic never *shortens* an existing longer storm, to avoid fighting with other storms/plugins or stacking scheduled tasks — it just extends duration to at least your configured value.
- **Channeling "usable while Fighting Mode is active"** relies on the vanilla mechanic: Channeling only strikes lightning when the trident hits an entity during a thunderstorm and the entity can see the sky. Since switching to Fighting Mode starts a storm, this works without extra custom code — building a custom lightning-strike system would be redundant and riskier.
- **Sneak + Right Click fully suppresses normal trident behavior** during the switch via `PlayerInteractEvent#setUseItemInHand(Result.DENY)` in addition to cancelling the event, so you won't get thrown by Riptide or throw the trident while switching modes.
- Only the main hand is checked (`EquipmentSlot.HAND`) to avoid Paper's known double-fire of `PlayerInteractEvent` for main+off hand on a single click.
