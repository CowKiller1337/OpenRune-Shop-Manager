# OpenRune Shop Manager

This module contains the shared editor UI used by the IntelliJ plugin. End users should install and
open the **OpenRune Shops** IntelliJ tool window instead of launching this module directly.

The tool reads the built server cache, lets you pick an existing shop NPC, search cache items, and
edit that shop's native stock.

The tool only lists Trade NPCs that are already spawned in the world and already have shop data.
It changes the shop title, stock, and prices; if the NPC is missing a native Trade script, it writes
a normal Aubury-style `PluginScript` for that NPC. It does not move the NPC or create a new NPC
definition.

Basic flow:

- Pick an NPC and press **Load selected shop** to pull its current shop stock into the form.
- If the NPC has more than one native shop inventory, choose the stock set from **Shop variant**.
- **Currency** is currently greyed out and forced to coins until the server-side custom currency
  handlers are confirmed.
- Search items and add/remove stock rows.
- Press **Preview** if you want to inspect the generated changes.
- Press **Save / Fix shop** to write the native shop update.
- Rebuild cache with `:or-cache:buildCache`, then restart the server.

If an NPC already has a hand-written Trade script, the tool will not generate a duplicate Trade
handler.

Existing shop edits update the matching native shop TOML under:

- `.data/raw-cache/server/shops/*.toml`

When a missing Trade click must be repaired, the tool writes a normal OpenRune shop script under:

- `content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated`

The tool does not write generated inventory/NPC definition files and does not install a custom
shop runtime handler.

Custom item currencies require matching server support for the chosen `currency.*` key before the
selector can be enabled. Point-based currencies that are not inventory items need separate server
logic.

After pressing **Save / Fix shop**, rebuild the cache and restart the server:

Windows:

```text
.\gradlew.bat --no-daemon :or-cache:buildCache
```

Linux/macOS:

```text
./gradlew --no-daemon :or-cache:buildCache
```
