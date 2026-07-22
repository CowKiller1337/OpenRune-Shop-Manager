# OpenRune Shop Manager

A desktop and IntelliJ shop editor for OpenRune server projects.

The tool edits existing native OpenRune shops without manually writing every inventory parameter by
hand. It is designed as a drop-in Gradle module for an OpenRune server checkout.

## Features

- Search cache NPCs that have a `Trade` option.
- Search cache items and build shop stock rows.
- Recreate existing in-world shops without moving the NPC.
- Repair missing existing-shop Trade scripts with normal Aubury-style OpenRune code.
- Load the stock from a selected existing shop NPC.
- Edit native shop variants on one NPC, such as base, skillcape, or trimmed skillcape stock.
- Includes a disabled custom-currency selector for future item currencies, such as Tokkul,
  trading sticks, marks of grace, pearls, or stardust.

## Install

Copy these files into the root of an OpenRune server project:

- `tools/shop-maker`
- `shop-manager.bat`

Then add the module to `settings.gradle.kts`:

```kotlin
include("tools:shop-maker")
```

The IntelliJ plugin is optional. To install its source too, copy:

- `tools/shop-manager-intellij`

Then add:

```kotlin
include("tools:shop-manager-intellij")
```

Optionally add a root Gradle shortcut to `build.gradle.kts`:

```kotlin
tasks.register("shopManager") {
    group = "application"
    description = "Opens the OpenRune shop manager desktop tool."
    dependsOn(":tools:shop-maker:run")
}
```

## Run

From the OpenRune server root:

Windows:

```text
.\gradlew.bat --no-daemon :tools:shop-maker:run
```

Or double-click:

```text
shop-manager.bat
```

Linux/macOS:

```text
./gradlew --no-daemon :tools:shop-maker:run
```

## IntelliJ Plugin

The IntelliJ plugin embeds the same Shop Manager UI as a right-side tool window named
**OpenRune Shops**.

Build it from the OpenRune server root after copying the files and adding the Gradle include:

```text
.\gradlew.bat --no-daemon :tools:shop-manager-intellij:buildPlugin
```

Install the generated plugin ZIP from:

```text
tools/shop-manager-intellij/build/distributions
```

In IntelliJ IDEA, use **Settings > Plugins > Install Plugin from Disk...**, select the ZIP, restart
the IDE, then open an OpenRune server project. The tool window appears only when the opened project
has the expected OpenRune data folders.

## How To Use

Use the tool when you want to change a shop that already exists in the world. It changes the shop
title, prices, and stock. If the selected NPC has shop stock but no native Trade script, the tool
also writes a normal Aubury-style `PluginScript` that calls `shops.open(...)`. It does not move the
NPC and does not create new NPC definitions.

Basic flow:

- Pick an NPC and press **Load selected shop** to pull its current stock into the form.
- If the NPC has more than one native shop inventory, choose the stock set from **Shop variant**.
- **Currency** is currently greyed out and forced to coins until the server-side custom currency
  handlers are confirmed.
- Search items and add/remove stock rows.
- Press **Preview** if you want to inspect the generated changes.
- Press **Save / Fix shop** to write the native shop update.
- Rebuild cache with `:or-cache:buildCache`, then restart the server.

If an NPC already has a hand-written Trade script, the tool will not create a second Trade handler.

## Output Files

Existing shop edits update the matching native shop TOML under:

- `.data/raw-cache/server/shops/*.toml`

When a missing Trade click must be repaired, the tool writes a normal OpenRune shop script under:

- `content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated`

If that generated script is needed, the tool may also add the normal shops API dependency to:

- `content/generic/generic-npcs/build.gradle.kts`

The tool does not write generated inventory/NPC definition files and does not install a custom
shop runtime handler.

Custom item currencies require matching server support for the chosen `currency.*` key before the
selector can be enabled. Point-based currencies, such as activity reward points that are not
inventory items, need separate server logic.

After pressing **Save / Fix shop**, rebuild the cache and restart the server:

Windows:

```text
.\gradlew.bat --no-daemon :or-cache:buildCache
```

Linux/macOS:

```text
./gradlew --no-daemon :or-cache:buildCache
```
