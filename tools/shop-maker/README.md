# OpenRune Shop Manager

This module contains the shared editor UI used by the IntelliJ plugin. End users should install and
open the **OpenRune Shops** IntelliJ tool window instead of launching this module directly.

The tool reads the built server cache, lets you pick an existing shop NPC, search cache items, and
edit that shop's native stock.

The tool lists spawned Trade NPCs that can be matched to real cache shop inventories. It changes the
shop title, stock, and prices. If a real cache shop has no stock TOML yet, it creates the TOML
overlay for that existing `inv.*` shop. If the NPC is missing a native Trade script, it writes a
normal Aubury-style `PluginScript` for that NPC. It does not move the NPC, create a new NPC
definition, or invent new shop inventory ids.

Basic flow:

- Pick an NPC and press **Load selected shop** to pull its current shop stock into the form.
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

If an NPC already has a hand-written Trade script, the tool will not generate a duplicate Trade
handler.

Button/actions:

- **Load selected shop** loads the chosen NPC's current shop stock.
- **Add selected item** adds the highlighted cache item to the stock table.
- **Remove stock row** removes the highlighted stock row.
- **Set price** adjusts the normal native shop sell multiplier. This affects the whole shop, because
  OpenRune's standard shop stock rows do not have per-item prices.
- **Preview** shows the files that will be changed.
- **Save / Fix shop** writes the shop stock and, when needed, a normal native Trade script.

Existing shop edits update the matching native shop TOML under:

- `.data/raw-cache/server/shops/*.toml`

When a real cache shop inventory has no stock TOML yet, the tool creates a native shop TOML in the
same folder for that existing inventory. It does not write `.data/gamevals/inv.rscm`, because
invented inventory ids can open as blank shops in the client.

When a missing Trade click must be repaired, the tool writes a normal OpenRune shop script under:

- `content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated`

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
