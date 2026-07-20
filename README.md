# OpenRune Shop Manager

A desktop shop editor for OpenRune server projects.

The tool helps create and edit shop definitions without manually writing every inventory and NPC
parameter by hand. It is designed as a drop-in Gradle module for an OpenRune server checkout.

## Features

- Search cache NPCs that have a `Trade` option.
- Search cache items and build shop stock rows.
- Recreate existing in-world shops without moving the NPC.
- Repair missing existing-shop Trade scripts with normal Aubury-style OpenRune code.
- Create custom shop NPC copies at pasted world coordinates.
- Reload generated shops and edit them later.
- Load the stock from a selected existing shop NPC.
- Generates normal OpenRune shop scripts for newly placed custom shops.

## Install

Copy these files into the root of an OpenRune server project:

- `tools/shop-maker`
- `tools/shop-manager-intellij`
- `shop-manager.bat`

Then add the module to `settings.gradle.kts`:

```kotlin
include(
    "tools:shop-maker",
    "tools:shop-manager-intellij",
)
```

If your `settings.gradle.kts` already has an `include(...)` block, add only the two `tools:*`
entries inside the existing list.

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

```powershell
.\gradlew.bat --no-daemon :tools:shop-maker:run
```

Or double-click:

```powershell
shop-manager.bat
```

## IntelliJ Plugin

The IntelliJ plugin embeds the same Shop Manager UI as a right-side tool window named
**OpenRune Shops**.

Build it from the OpenRune server root after copying the files and adding the Gradle include:

```powershell
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

Use **Recreate existing** when you want to change a shop that already exists in the world. This
mode changes the shop title, prices, and stock. If the selected NPC has shop stock but no native
Trade script, the tool also writes a normal Aubury-style `PluginScript` that calls `shops.open(...)`.
It does not move the NPC. If you want a shop at different coordinates, use **Create custom**.

Use **Create custom** when you want to place a new shop NPC copy. Enter the world X/Y/level, build
the stock, and place the shop. The tool creates a unique `npc.custom_shop_*` type and a small
Aubury-style `PluginScript` that calls `shops.open(...)`.

To edit instead of starting over:

- Pick an NPC and press **Load selected shop** to pull its current stock into the form.
- Press **Load saved shop** to reload a generated shop, including saved custom coordinates.

## Output Files

Generated shops are written as marked blocks to:

- `.data/raw-cache/server/shops/custom_shops.toml`
- `.data/raw-cache/server/custom_shop_npcs.toml`
- `.data/raw-cache/map/npcs/custom_shops.toml`
- `.data/gamevals/inv.rscm`
- `.data/gamevals/npc.rscm`
- `content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated`

Existing shop edits update the native `inv.*` shop TOML directly. When a missing Trade click must
be repaired, the tool writes a normal generated OpenRune shop script instead of installing a custom
shop runtime handler.

Existing shop edits usually only need a server restart. Newly spawned custom shop NPCs need a cache
or map rebuild before restarting:

```powershell
.\gradlew.bat --no-daemon :or-cache:buildCache
```

Generated shops currently use a 28-slot OSRS shop render inventory, so stock is capped at 28 rows.
Larger shops should be split or given a larger tested render inventory.
