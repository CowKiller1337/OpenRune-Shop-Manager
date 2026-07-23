# OpenRune Shop Manager

An IntelliJ plugin for editing OpenRune shops without writing the shop script by hand.

It edits **native OpenRune shops**. It does not add a custom shop system.

## What It Does

- Finds in-world NPCs with a `Trade` option.
- Loads and edits existing shop stock.
- Lets you add/remove items, change counts, restock cycles, currency, and shop title.
- Can fix missing Trade scripts with normal `shops.open(...)` code.
- Can attach an NPC to an existing real `inv.*` shop inventory.
- Shows if a shop inventory is unused or already used by another NPC.

## Install / Update

Copy these folders into your OpenRune server root:

```text
tools/shop-maker
tools/shop-manager-intellij
```

Add this to `settings.gradle.kts`:

```kotlin
include("tools:shop-maker")
include("tools:shop-manager-intellij")
```

Build the plugin:

```text
.\gradlew.bat --no-daemon :tools:shop-manager-intellij:buildPlugin
```

Install the ZIP from:

```text
tools/shop-manager-intellij/build/distributions
```

In IntelliJ:

```text
Settings > Plugins > Install Plugin from Disk...
```

Then restart IntelliJ.

If you already installed an older version, uninstall the old plugin first, restart IntelliJ, install the new ZIP, then restart again.

## How To Use

1. Open your OpenRune server project in IntelliJ.
2. Open the **OpenRune Shops** tool window on the right side.
3. Search for a shop NPC.
4. Press **Load selected shop**.
5. Pick the correct **Shop inventory** if needed.
6. Add/remove stock items.
7. Pick a currency if the shop does not use coins.
8. Press **Save / Fix shop**.
9. Rebuild cache and restart the server.

Cache rebuild command:

```text
.\gradlew.bat --no-daemon :or-cache:buildCache
```

## Important

If a shop inventory says `used by ...`, changing it changes every NPC using that same inventory.

The **Set price** button changes the shop multiplier, not one single item price. That is how normal OpenRune shops work.

The tool only uses real cache shop inventories. It does not invent fake `inv.*` ids, because those can open as blank shops in-game.

## Buttons

- **Load selected shop**: loads the NPC shop into the editor.
- **Add selected item**: adds the selected item to the stock table.
- **Remove stock row**: removes the selected stock row.
- **Set price**: adjusts the shop multiplier to get close to a target price.
- **Preview**: shows what will be written.
- **Save / Fix shop**: writes the shop update.
