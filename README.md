# OpenRune Shop Manager

An IntelliJ shop editor for OpenRune server projects.

The tool edits existing native OpenRune shops without manually writing every inventory parameter by
hand. It is designed as a drop-in IntelliJ plugin source module for an OpenRune server checkout.

## Features

- Search cache NPCs that have a `Trade` option.
- Search cache items and build shop stock rows.
- Recreate existing in-world shops without moving the NPC.
- Repair missing existing-shop Trade scripts with normal Aubury-style OpenRune code.
- Load the stock from a selected existing shop NPC.
- Edit native shop variants on one NPC, such as base, skillcape, or trimmed skillcape stock.
- Set a selected item's target shop price by adjusting the native shop sell multiplier.
- Select native OpenRune shop currencies, including already registered currencies and common
  item-backed currencies such as Tokkul, trading sticks, marks of grace, pearls, and stardust.

## Install

Copy these files into the root of an OpenRune server project:

- `tools/shop-maker`
- `tools/shop-manager-intellij`

`tools/shop-maker` is the shared editor module used by the plugin. Users do not need to run it as a
desktop app.

Then add the modules to `settings.gradle.kts`:

```kotlin
include("tools:shop-maker")
include("tools:shop-manager-intellij")
```

## Build Plugin

From the OpenRune server root:

```text
.\gradlew.bat --no-daemon :tools:shop-manager-intellij:buildPlugin
```

Linux/macOS:

```text
./gradlew --no-daemon :tools:shop-manager-intellij:buildPlugin
```

Install the generated plugin ZIP from:

```text
tools/shop-manager-intellij/build/distributions
```

## IntelliJ Plugin

The IntelliJ plugin embeds the same Shop Manager UI as a right-side tool window named
**OpenRune Shops**.

In IntelliJ IDEA, use **Settings > Plugins > Install Plugin from Disk...**, select the ZIP, restart
the IDE, then open an OpenRune server project. The tool window appears only when the opened project
has the expected OpenRune data folders.

## Update Existing Plugin

If an older OpenRune Shop Manager plugin is already installed:

- Open **Settings > Plugins > Installed**.
- Search for **OpenRune Shop Manager**.
- Uninstall it, then restart IntelliJ.
- Install the new ZIP with **Install Plugin from Disk...**.
- Restart IntelliJ again.

If IntelliJ still shows the old UI, confirm the installed plugin version matches the newest ZIP and
restart the IDE once more.

## How To Use

Use the tool when you want to change a shop NPC that already exists in the world. It changes the
shop title, prices, and stock. If a real cache shop has no stock TOML yet, the tool creates the TOML
overlay for that existing `inv.*` shop. If the selected NPC has no native Trade script, the tool
also writes a normal Aubury-style `PluginScript` that calls `shops.open(...)`. It does not move the
NPC, create new NPC definitions, or invent new shop inventory ids.

Basic flow:

- Pick an NPC and press **Load selected shop** to pull its current stock into the form.
- If the NPC has more than one native shop inventory, choose the stock set from **Shop variant**.
- Choose **Currency** when the shop should use something other than coins. Registered currencies are
  used directly. Common item-backed currencies are registered through OpenRune's native
  `ShopCurrencyTable` setup when you save.
- Search items and add/remove stock rows.
- Select a stock row and press **Set price** if you want the tool to adjust the shop sell multiplier
  so that item costs the entered amount at normal stock.
- Press **Preview** if you want to inspect the generated changes.
- Press **Save / Fix shop** to write the native shop update.
- Rebuild cache with `:or-cache:buildCache`, then restart the server.

If an NPC already has a hand-written Trade script, the tool will not create a second Trade handler.

Button/actions:

- **Load selected shop** loads the chosen NPC's current shop stock.
- **Add selected item** adds the highlighted cache item to the stock table.
- **Remove stock row** removes the highlighted stock row.
- **Set price** adjusts the normal native shop sell multiplier. This affects the whole shop, because
  OpenRune's standard shop stock rows do not have per-item prices.
- **Preview** shows the files that will be changed.
- **Save / Fix shop** writes the shop stock and, when needed, a normal native Trade script.

## Output Files

Existing shop edits update the matching native shop TOML under:

- `.data/raw-cache/server/shops/*.toml`

When a real cache shop inventory has no stock TOML yet, the tool creates a native shop TOML in the
same folder for that existing inventory. It does not write `.data/gamevals/inv.rscm`, because
invented inventory ids can open as blank shops in the client.

When a missing Trade click must be repaired, the tool writes a normal OpenRune shop script under:

- `content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated`

If that generated script is needed, the tool may also add the normal shops API dependency to:

- `content/generic/generic-npcs/build.gradle.kts`

The tool does not write generated inventory/NPC definition files and does not install a custom
shop runtime handler.

Native item currencies are written to:

- `.data/gamevals/currency.rscm`
- `.data/gamevals/dbrow.rscm`
- `or-cache/src/main/kotlin/dev/openrune/tables/ShopCurrencyTable.kt`

Point-based currencies that use varbits or custom sync/cost logic still need server code from the
project owner. The tool will use those currencies once they already exist in `ShopCurrencyTable`.

After pressing **Save / Fix shop**, rebuild the cache and restart the server:

Windows:

```text
.\gradlew.bat --no-daemon :or-cache:buildCache
```

Linux/macOS:

```text
./gradlew --no-daemon :or-cache:buildCache
```
