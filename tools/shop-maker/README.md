# OpenRune Shop Manager

Open the desktop tool with:

```powershell
.\gradlew.bat :tools:shop-maker:run
```

The tool reads the built server cache, lets you pick an existing shop NPC, search cache items, and
edit that shop's native stock.

The tool only lists Trade NPCs that are already spawned in the world and already have shop data.
It changes the shop title, stock, and prices; if the NPC is missing a native Trade script, it writes
a normal Aubury-style `PluginScript` for that NPC. It does not move the NPC or create a new NPC
definition.

To edit instead of starting over:

- Pick an NPC and press **Load selected shop** to pull its current shop stock into the form.

Existing shop edits update the matching native shop TOML under:

- `.data/raw-cache/server/shops/*.toml`

When a missing Trade click must be repaired, the tool writes a normal OpenRune shop script under:

- `content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated`

The tool does not write generated inventory/NPC definition files and does not install a custom
shop runtime handler.

After saving a shop stock change, rebuild the cache and restart the server:

```powershell
.\gradlew.bat --no-daemon :or-cache:buildCache
```
