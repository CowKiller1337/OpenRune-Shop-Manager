// Shop Maker runtime support v2.
package org.rsmod.content.generic.npcs.shops

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.InvScope
import dev.openrune.types.InvStackType
import dev.openrune.types.InvStock
import dev.openrune.types.InventoryServerType
import dev.openrune.types.NpcServerType
import dev.openrune.types.util.UncheckedType
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.shops.Shops
import org.rsmod.api.shops.config.ShopParams
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class CustomShopNpcs @Inject constructor(private val shops: Shops) : PluginScript() {
    private val sharedGeneratedInvs: MutableMap<String, Inventory> = mutableMapOf()

    override fun ScriptContext.startup() {
        val generatedShops = loadGeneratedShops()
        val managedNpcs = loadManagedShopNpcs()
        for (type in ServerCacheManager.getNpcs().values) {
            if (!type.isManagedShopNpc(managedNpcs, generatedShops)) {
                continue
            }
            bindShopNpc(type, generatedShops)
        }
    }

    private fun ScriptContext.bindShopNpc(
        type: NpcServerType,
        generatedShops: Map<String, GeneratedShop>,
    ) {
        val npc = type.safeInternalName() ?: return
        when (type.tradeOpSlot() ?: DEFAULT_TRADE_SLOT) {
            1 -> onOpNpc1(npc) { openShop(player, it.npc, generatedShops[npc]) }
            2 -> onOpNpc2(npc) { openShop(player, it.npc, generatedShops[npc]) }
            3 -> onOpNpc3(npc) { openShop(player, it.npc, generatedShops[npc]) }
            4 -> onOpNpc4(npc) { openShop(player, it.npc, generatedShops[npc]) }
            5 -> onOpNpc5(npc) { openShop(player, it.npc, generatedShops[npc]) }
        }
    }

    private fun openShop(player: Player, npc: Npc, generatedShop: GeneratedShop?) {
        if (generatedShop == null) {
            shops.open(player, npc)
            return
        }
        val shopInv = sharedGeneratedInvs.getOrPut(generatedShop.invInternal) {
            generatedShop.createInventory()
        }
        shops.open(
            player = player,
            title = generatedShop.title,
            shopInv = shopInv,
            sideInv = player.inv,
            buyPercentage = generatedShop.buyMultiplier / PERCENTAGE_SCALE,
            sellPercentage = generatedShop.sellMultiplier / PERCENTAGE_SCALE,
            changePercentage = generatedShop.changeDelta / PERCENTAGE_SCALE,
            subtext = Shops.DEFAULT_SUBTEXT,
        )
    }

    private companion object {
        private const val CUSTOM_SHOP_PREFIX = "npc.custom_shop_"
        private val CUSTOM_SHOPS_FILE = Path.of(".data/raw-cache/server/shops/custom_shops.toml")
        private val CUSTOM_SHOP_NPCS_FILE = Path.of(".data/raw-cache/server/custom_shop_npcs.toml")
        private const val DEFAULT_TRADE_SLOT = 3
        private const val PERCENTAGE_SCALE = 10.0

        private fun NpcServerType.isManagedShopNpc(
            managedNpcs: Set<String>,
            generatedShops: Map<String, GeneratedShop>,
        ): Boolean {
            val internal = safeInternalName() ?: return false
            if (internal in generatedShops) {
                return true
            }
            return (internal in managedNpcs || internal.startsWith(CUSTOM_SHOP_PREFIX)) &&
                hasParam(ShopParams.shop_invetnory)
        }

        private fun loadGeneratedShops(): Map<String, GeneratedShop> {
            if (!Files.exists(CUSTOM_SHOPS_FILE) || !Files.exists(CUSTOM_SHOP_NPCS_FILE)) {
                return emptyMap()
            }
            val shopBlocks = parseManagedBlocks(Files.readString(CUSTOM_SHOPS_FILE))
            val npcBlocks = parseManagedBlocks(Files.readString(CUSTOM_SHOP_NPCS_FILE))
            return npcBlocks.mapNotNull { (marker, npcBlock) ->
                val shopBlock = shopBlocks[marker] ?: return@mapNotNull null
                val npcInternal = npcBlock.stringValue("id") ?: return@mapNotNull null
                val invInternal = npcBlock.paramStringValue("param.shop_inventory")
                    ?: shopBlock.stringValue("id")
                    ?: return@mapNotNull null
                val title = npcBlock.paramStringValue("param.shop_name")
                    ?: shopBlock.stringValue("name")
                    ?: "Custom Shop"
                npcInternal to GeneratedShop(
                    invInternal = invInternal,
                    title = title,
                    size = shopBlock.intValue("size") ?: 1,
                    buyMultiplier = shopBlock.intValue("buyMultiplier") ?: 600,
                    sellMultiplier = shopBlock.intValue("sellMultiplier") ?: 1000,
                    changeDelta = shopBlock.intValue("delta") ?: 20,
                    stock = shopBlock.stockRows(),
                )
            }.toMap()
        }

        private fun loadManagedShopNpcs(): Set<String> {
            if (!Files.exists(CUSTOM_SHOP_NPCS_FILE)) {
                return emptySet()
            }
            val regex = Regex("""(?m)^\s*id\s*=\s*"([^"]+)"""")
            return regex.findAll(Files.readString(CUSTOM_SHOP_NPCS_FILE))
                .map { it.groupValues[1] }
                .toSet()
        }

        private fun NpcServerType.tradeOpSlot(): Int? =
            (1..5).firstOrNull { slot ->
                actions.getOpOrNull(slot - 1).equals("trade", ignoreCase = true)
            }

        private fun NpcServerType.safeInternalName(): String? =
            runCatching { internalName }.getOrNull()

        private fun parseManagedBlocks(text: String): Map<String, String> {
            val regex = Regex(
                """# BEGIN SHOP MAKER: ([^\r\n]+)\R(.*?)# END SHOP MAKER: \1""",
                RegexOption.DOT_MATCHES_ALL,
            )
            return regex.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        }

        private fun String.stringValue(key: String): String? {
            val regex = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*"([^"]*)"""")
            return regex.find(this)?.groupValues?.get(1)
        }

        private fun String.paramStringValue(key: String): String? {
            val regex = Regex("""(?m)^\s*"${Regex.escape(key)}"\s*=\s*"([^"]*)"""")
            return regex.find(this)?.groupValues?.get(1)
        }

        private fun String.intValue(key: String): Int? {
            val regex = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*(-?\d+)""")
            return regex.find(this)?.groupValues?.get(1)?.toIntOrNull()
        }

        private fun String.stockRows(): List<GeneratedStock> {
            val stockBlockRegex = Regex(
                """\[\[inventory\.stock\]\]\R(.*?)(?=\R\[\[inventory\.stock\]\]|\z)""",
                RegexOption.DOT_MATCHES_ALL,
            )
            return stockBlockRegex.findAll(this).mapNotNull { match ->
                val block = match.groupValues[1]
                val obj = block.stringValue("obj") ?: return@mapNotNull null
                GeneratedStock(
                    objInternal = obj,
                    count = block.intValue("count") ?: 1,
                    restockCycles = block.intValue("restockCycles") ?: 100,
                )
            }.toList()
        }
    }
}

private data class GeneratedShop(
    val invInternal: String,
    val title: String,
    val size: Int,
    val buyMultiplier: Int,
    val sellMultiplier: Int,
    val changeDelta: Int,
    val stock: List<GeneratedStock>,
) {
    @OptIn(UncheckedType::class)
    fun createInventory(): Inventory {
        val safeSize = maxOf(size, stock.size, 1)
        val invStock =
            stock.map {
                InvStock(
                    obj = it.objInternal.asRSCM(RSCMType.OBJ),
                    count = it.count,
                    restockCycles = it.restockCycles,
                )
            }
        val type = InventoryServerType(
            id = SHOP_MAKER_RENDER_INV.asRSCM(RSCMType.INV),
            stack = InvStackType.Always,
            size = safeSize,
            scope = InvScope.Shared,
            flags = InventoryServerType.pack(
                protect = false,
                allStock = false,
                restock = true,
                runWeight = false,
                dummyInv = false,
                placeholders = false,
            ),
            stock = invStock,
        )
        val objs = arrayOfNulls<InvObj>(safeSize)
        invStock.forEachIndexed { slot, stock ->
            objs[slot] = InvObj(stock.obj, stock.count)
        }
        return Inventory(type, objs)
    }

    private companion object {
        /*
         * The shop clientscript draws from a client-known inventory id. Generated inventories are
         * server-only, so we use an existing shop-shaped inventory id for the visual channel while
         * keeping this generated inventory's stock, title, and pricing.
         */
        private const val SHOP_MAKER_RENDER_INV = "inv.archeryshop2"
    }
}

private data class GeneratedStock(
    val objInternal: String,
    val count: Int,
    val restockCycles: Int,
)
