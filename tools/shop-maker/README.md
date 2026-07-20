# OpenRune Shop Manager

Open the desktop tool with:

```powershell
.\gradlew.bat :tools:shop-maker:run
```

The tool reads the built server cache, lets you pick a trade-capable NPC model, search cache items,
and generate a custom shop.

The first panel asks which mode you want:

- **Recreate existing** only lists Trade NPCs that are already spawned in the world and already have
  shop data. This changes the shop title, stock, and prices only; it does not move the NPC. Make a
  new custom shop if you want different coordinates.
- **Create custom** spawns a new visible shop copy at manually entered X/Y/level coordinates. This is more flexible, but
  it creates a unique `npc.custom_shop_*` type plus a normal Aubury-style `PluginScript`, so it needs
  cache/map rebuilds and should be treated as the advanced path.

To edit instead of starting over:

- Pick an NPC and press **Load selected shop** to pull its current shop stock into the form.
- Press **Load saved shop** to reload a Shop Manager generated shop, including its saved custom
  coordinates when it has them.

On first launch, the tool checks one server-side support piece:

- OpenRune's server cache packer support for custom-only shop inventories.

If the support piece is missing or outdated, the tool offers to install it once and writes a backup
before updating the cache packer source file. If the file layout does not match the expected
OpenRune source, the tool refuses to patch it. The tool does not install a custom shop runtime
handler; newly placed shops use generated native OpenRune scripts that call `shops.open(...)`.

Generated shops are written as marked blocks to:

- `.data/raw-cache/server/shops/custom_shops.toml`
- `.data/raw-cache/server/custom_shop_npcs.toml`
- `.data/raw-cache/map/npcs/custom_shops.toml`
- `.data/gamevals/inv.rscm`
- `.data/gamevals/npc.rscm`
- `content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated`

Existing shop edits update the native `inv.*` shop TOML directly.

For shops attached to an existing visible NPC, restart the server after placing or editing a shop.
For newly spawned shop NPC copies, rebuild the cache/map data and restart the server:

```powershell
.\gradlew.bat --no-daemon :or-cache:buildCache
```

Generated shops use a 28-slot OSRS shop render inventory, so stock is capped at 28 rows. Larger
shops should be split or given a larger tested render inventory.
