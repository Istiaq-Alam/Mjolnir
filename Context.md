# Mjolnir
## 1. Server Information

```text
Minecraft Java: 26.1.2
Server Software: Paper
Paper Version: 26.1.2-74
API Version: 26.1.2.build.74-stable
Java Version Required: Java 25
```

Please specifically target **Paper 26.1.2** and Java 25. Do not use outdated Minecraft 1.21.x API instructions.

Paper's current project setup uses Java 25 and supports specific Paper API build dependencies for the 26.x versioning system. ([PaperMC Docs][1])

---

# 2. Plugin Information

```text
Plugin Name: Mjolnir
Main Command: /mjolnir
```

The plugin will create a custom weapon based on a **Trident**.

The weapon's name is:

```text
Mjolnir
```

The plugin must be lightweight and designed specifically for my small Paper SMP server.

---

# 3. Admin Command

The plugin must have this command:

```text
/mjolnir give <playername>
```

Example:

```text
/mjolnir give Istiak
```

Only server administrators/OPs or players with this permission can use it:

```text
mjolnir.admin
```

The command gives the player the custom Mjolnir trident.

---

# 4. Custom Weapon Identity

Mjolnir must not be identified only by its name or lore.

Use **Persistent Data Container (PDC)** or the appropriate modern Paper item-data system to store a unique internal identifier.

For example, conceptually:

```text
mjolnir = true
mode = travel
```

This ensures that a normal trident renamed to "Mjolnir" cannot activate the plugin's abilities. Paper supports persistent data for custom item identification. ([PaperMC Docs][2])

---

# 5. Mjolnir Must Be Truly Unbreakable

Mjolnir must:

```text
Never lose durability
Never break
```

Even though it has:

```text
Unbreaking X
Mending I
```

I want those enchantments visible, but the item itself should also be set as truly unbreakable using the appropriate modern Paper/Minecraft item API.

---

# 6. Mode 1 — Travel Mode

Mjolnir has two modes.

The first mode is:

```text
🌊 TRAVEL MODE
```

Enchantments:

```text
Riptide X
Unbreaking X
Mending I
```

Properties:

* The trident should function normally with Riptide.
* The player can use it for fast movement/travel.
* The item should remain unbreakable.
* The custom item name should remain `Mjolnir`.
* Lore should indicate that Travel Mode is active.

Example lore:

```text
🌊 Travel Mode

Riptide X
Unbreaking X
Mending

Sneak + Right Click to switch mode
```

---

# 7. Mode 2 — Fighting Mode

The second mode is:

```text
⚡ FIGHTING MODE
```

Enchantments:

```text
Channeling I
Impaling X
Loyalty X
Unbreaking X
Mending I
```

Properties:

* Riptide must be removed.
* Channeling must be active.
* Loyalty X must work.
* Impaling X must work.
* The trident remains unbreakable.
* Lore should indicate Fighting Mode.

Example:

```text
⚡ Fighting Mode

Channeling
Impaling X
Loyalty X
Unbreaking X
Mending

Sneak + Right Click to switch mode
```

---

# 8. Mode Switching

The player switches modes by:

```text
Hold Mjolnir
+
Sneak
+
Right Click
```

The plugin must:

1. Detect that the player is holding the real custom Mjolnir.
2. Detect Sneak + Right Click.
3. Prevent normal trident behavior during the mode switch.
4. Swap between Travel Mode and Fighting Mode.
5. Automatically remove incompatible enchantments.
6. Apply the enchantments for the selected mode.
7. Update the item's PDC mode value.
8. Update the lore/display information.
9. Show an actionbar message.
10. Play appropriate sound and particle effects.

---

# 9. Fighting Mode Weather Ability

When the player switches from Travel Mode to Fighting Mode:

```text
🌊 Travel Mode
        ↓
Sneak + Right Click
        ↓
⚡ Fighting Mode
        ↓
🌩 Thunderstorm starts
```

Default thunderstorm duration:

```text
180 seconds
```

This duration must be configurable.

Example:

```yaml
storm-duration: 180
```

The weather should affect the world where the player activated Mjolnir.

Use the proper Paper/Bukkit world weather API.

Important:

* Do not create multiple unnecessary scheduled tasks.
* Avoid weather-task stacking.
* Make the behavior predictable if the world is already experiencing a thunderstorm.

I want Channeling to be usable while Fighting Mode is active.

---

# 10. Mode Switch Cooldown

The mode switching must have a configurable cooldown.

Example:

```yaml
mode-switch-cooldown: 5
```

This means the player must wait 5 seconds before switching again.

If the player tries before the cooldown ends, show an actionbar message such as:

```text
⏳ Mjolnir is recharging: 3s
```

The cooldown should be tracked per player.

---

# 11. Actionbar Messages

When switching to Travel Mode:

```text
🌊 Mjolnir: TRAVEL MODE
```

When switching to Fighting Mode:

```text
⚡ Mjolnir: FIGHTING MODE
```

When the cooldown is active:

```text
⏳ Mjolnir recharging: Xs
```

Use Paper/Adventure components where appropriate.

---

# 12. Thor / Mjolnir Visual Effects

I want the weapon switching to feel similar to Thor's Mjolnir.

When switching to **Fighting Mode**, add cosmetic effects such as:

### Particles

```text
ELECTRIC_SPARK
END_ROD
```

Create a burst or electric effect around the player.

### Sounds

Use suitable Minecraft sounds, such as thunder/lightning and trident-related sounds.

Important:

* Do not damage the player during mode switching.
* Do not destroy blocks.
* Cosmetic effects are preferred.
* Do not spawn dangerous real lightning directly on the player unless explicitly configured.

When switching back to Travel Mode, use more movement/wind/trident-style effects.

---

# 13. Configuration File

Create a complete:

```text
config.yml
```

It should initially contain:

```yaml
storm-duration: 180

mode-switch-cooldown: 5

effects:
  enabled: true
  particles: true
  sounds: true
```

You can add other useful settings if necessary, but explain every additional setting.

---

# 14. Required Project Structure

Create a complete buildable project.

For example:

```text
Mjolnir/
├── pom.xml OR build.gradle.kts
├── settings.gradle.kts (if using Gradle)
│
└── src/
    └── main/
        ├── java/
        │   └── [package]/
        │       ├── MjolnirPlugin.java
        │       ├── MjolnirCommand.java
        │       ├── MjolnirItem.java
        │       └── MjolnirListener.java
        │
        └── resources/
            ├── plugin.yml
            └── config.yml
```

You may improve the class structure if necessary.

---

# 15. Build System

Please use a modern Paper-compatible build setup.

Prefer **Gradle Kotlin DSL** if it is the best choice for Paper 26.1.2, because Paper officially recommends Gradle, although Maven is also possible. ([PaperMC Docs][1])

The project must:

* Target Java 25.
* Use the PaperMC Maven repository.
* Use a Paper API dependency compatible with Paper 26.1.2.
* Compile into a `.jar`.
* Be installable by placing the `.jar` into:

```text
plugins/
```

Paper documents the normal plugin project structure and `plugin.yml` setup. ([PaperMC Docs][1])

---

# 16. What I Want You To Provide

Please give me:

### Step 1

The complete project structure.

### Step 2

Every file with its **full code**.

Do not give partial code.

I need the complete contents of:

```text
build.gradle.kts / pom.xml
settings.gradle.kts if needed
plugin.yml
config.yml

All Java source files
```

### Step 3

Explain exactly how to build the plugin on Linux.

I use Linux, so provide commands such as:

```bash
java --version
./gradlew build
```

or the appropriate commands.

### Step 4

Tell me exactly where the compiled `.jar` will appear.

### Step 5

Tell me how to upload it to my Paper server.

### Step 6

Give me testing instructions.

For example:

```text
1. Start server.
2. Check plugin loads.
3. Run:
/mjolnir give <player>
4. Test Travel Mode.
5. Sneak + Right Click.
6. Verify Fighting Mode.
7. Verify thunderstorm.
8. Test Channeling.
9. Test cooldown.
```

---

# 17. Important Development Requirements

Before writing the code:

1. Verify the current Paper 26.1.2 API/build configuration from official Paper documentation.
2. Do not assume old Paper 1.21.x APIs.
3. Make sure all imports and API methods are compatible with Paper 26.1.2.
4. Avoid deprecated APIs where a modern alternative exists.
5. Make the code compile cleanly.
6. Check for likely issues such as:

   * Main-hand/off-hand duplicate interaction events.
   * Right-click behavior conflicts.
   * Sneaking behavior.
   * Enchantment incompatibility.
   * Item identification.
   * Cooldown tracking.
   * Weather duration.
   * Plugin reload/restart behavior.
7. If something in my requested design cannot work exactly because of Minecraft mechanics, explain the limitation clearly and implement the closest reliable behavior.

**Do not just explain the plugin. Build the complete source code for it.**

---

That context should be enough to start a completely new chat without losing the important requirements. The Paper 26.1.2 setup details in it are based on current Paper documentation, including Java 25 and the 26.x API versioning. ([PaperMC Docs][1])

[1]: https://docs.papermc.io/paper/dev/project-setup/?utm_source=chatgpt.com "Project setup | PaperMC Docs"
[2]: https://docs.papermc.io/paper/dev/?utm_source=chatgpt.com "Development | PaperMC Docs"
