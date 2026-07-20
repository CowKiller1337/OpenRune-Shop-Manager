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
  it needs cache/map rebuilds and should be treated as the advanced path.

To edit instead of starting over:

- Pick an NPC and press **Load selected shop** to pull its current shop stock into the form.
- Press **Load saved shop** to reload a Shop Manager generated shop, including its saved custom
  coordinates when it has them.

On first launch, the tool checks two server-side support pieces:

- OpenRune's server cache packer support for custom-only shop inventories.
- The Shop Manager runtime NPC script that opens generated shops with their custom stock.

If either support piece is missing or outdated, the tool offers to install it once and writes a
backup before updating an existing source file. If the file layout does not match the expected
OpenRune source, the tool refuses to patch it.

Generated shops are written as marked blocks to:

- `.data/raw-cache/server/shops/custom_shops.toml`
- `.data/raw-cache/server/custom_shop_npcs.toml`
- `.data/raw-cache/map/npcs/custom_shops.toml`
- `.data/gamevals/inv.rscm`

For shops attached to an existing visible NPC, restart the server after placing or editing a shop.
For newly spawned shop NPC copies, rebuild the cache/map data and restart the server:

```powershell
.\gradlew.bat --no-daemon :or-cache:buildCache
```

The current runtime uses a proven 22-slot shop render inventory, so generated shops are capped at
22 stock rows until a larger render inventory is tested.
