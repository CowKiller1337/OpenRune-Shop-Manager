package org.rsmod.tools.shopmaker

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.NpcServerType
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import kotlin.math.max

fun main() {
    SwingUtilities.invokeLater {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        val repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        if (ensureShopMakerCompatibility(repoRoot)) {
            ShopMakerFrame(repoRoot).isVisible = true
        }
    }
}

private class ShopMakerFrame(private val repoRoot: Path) : JFrame("OpenRune Shop Manager") {
    private val shopName = JTextField("New Shop")
    private val shopSlug = JTextField()
    private val worldX = JTextField()
    private val worldY = JTextField()
    private val level = JTextField("0")
    private val buyMultiplier = JSpinner(SpinnerNumberModel(600, 0, 10_000, 10))
    private val sellMultiplier = JSpinner(SpinnerNumberModel(1000, 0, 10_000, 10))
    private val changeDelta = JSpinner(SpinnerNumberModel(20, 0, 10_000, 1))
    private val recreateExistingButton = JButton("Recreate existing")
    private val createCustomButton = JButton("Create custom")
    private val loadSelectedShopButton = JButton("Load selected shop")
    private val loadSavedShopButton = JButton("Load saved shop")
    private val saveShopButton = JButton("Save shop")
    private val modeInfo = JLabel()
    private val npcHint = JLabel()

    private val npcSearch = JTextField()
    private val itemSearch = JTextField()
    private val npcList = JList<LookupEntry>()
    private val itemList = JList<LookupEntry>()
    private val stockModel = StockTableModel()
    private val preview = JTextArea()
    private val status = JLabel("Loading cache...")

    private var lookup = LookupData(emptyList(), emptyList())
    private var shopMode = ShopMode.RECREATE_EXISTING

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(1180, 760)
        preferredSize = Dimension(1280, 820)
        layout = BorderLayout(8, 8)

        npcList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        itemList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        npcList.cellRenderer = LookupRenderer()
        itemList.cellRenderer = LookupRenderer()
        preview.isEditable = false
        preview.lineWrap = false

        add(buildMainPanel(), BorderLayout.CENTER)
        add(status.withPadding(), BorderLayout.SOUTH)
        pack()
        setLocationRelativeTo(null)

        shopName.document.addDocumentListener(SimpleDocumentListener { updateDerivedSlug() })
        npcSearch.document.addDocumentListener(SimpleDocumentListener { refreshNpcList() })
        itemSearch.document.addDocumentListener(SimpleDocumentListener { refreshItemList() })
        recreateExistingButton.addActionListener { setShopMode(ShopMode.RECREATE_EXISTING) }
        createCustomButton.addActionListener { setShopMode(ShopMode.CREATE_CUSTOM) }
        loadSelectedShopButton.addActionListener { loadSelectedNpcShop() }
        loadSavedShopButton.addActionListener { loadSavedShop() }

        loadCache()
        refreshModeUi()
    }

    private fun buildMainPanel(): JPanel {
        val root = JPanel(BorderLayout(8, 8))
        root.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val lists = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            buildNpcPanel(),
            buildItemPanel(),
        )
        lists.resizeWeight = 0.5

        val work = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            lists,
            buildShopPanel(),
        )
        work.resizeWeight = 0.58

        root.add(work, BorderLayout.CENTER)
        return root
    }

    private fun buildNpcPanel(): JPanel {
        val panel = JPanel(BorderLayout(6, 6))
        panel.border = BorderFactory.createTitledBorder("Shop NPC")
        panel.add(npcSearch.withLabel("Search NPC"), BorderLayout.NORTH)
        panel.add(JScrollPane(npcList), BorderLayout.CENTER)
        panel.add(npcHint, BorderLayout.SOUTH)
        return panel
    }

    private fun buildItemPanel(): JPanel {
        val panel = JPanel(BorderLayout(6, 6))
        panel.border = BorderFactory.createTitledBorder("Cache Items")

        val addButton = JButton("Add selected item")
        addButton.addActionListener { addSelectedItem() }

        panel.add(itemSearch.withLabel("Search item"), BorderLayout.NORTH)
        panel.add(JScrollPane(itemList), BorderLayout.CENTER)
        panel.add(addButton, BorderLayout.SOUTH)
        return panel
    }

    private fun buildShopPanel(): JPanel {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createTitledBorder("Generated Shop")

        val north = JPanel(BorderLayout(8, 8))
        val fields = JPanel(GridBagLayout())
        var row = 0
        fields.addRow(row++, "Shop title", shopName)
        fields.addRow(row++, "Shop slug", shopSlug)
        fields.addRow(row++, "World X", worldX)
        fields.addRow(row++, "World Y", worldY)
        fields.addRow(row++, "Level", level)
        fields.addRow(row++, "Buy multiplier", buyMultiplier)
        fields.addRow(row++, "Sell multiplier", sellMultiplier)
        fields.addRow(row++, "Price delta", changeDelta)
        north.add(buildModePanel(), BorderLayout.NORTH)
        north.add(fields, BorderLayout.CENTER)

        val removeStock = JButton("Remove stock row")
        removeStock.addActionListener { removeSelectedStockRow() }
        val makePreview = JButton("Preview")
        makePreview.addActionListener { previewShop() }
        saveShopButton.addActionListener { placeShop() }

        val actions = JPanel(GridLayout(2, 1, 0, 4))
        val loadActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        loadActions.add(loadSelectedShopButton)
        loadActions.add(loadSavedShopButton)

        val editActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        editActions.add(removeStock)
        editActions.add(makePreview)
        editActions.add(saveShopButton)
        actions.add(loadActions)
        actions.add(editActions)

        val stockTable = JTable(stockModel)
        stockTable.fillsViewportHeight = true

        val center = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(stockTable),
            JScrollPane(preview),
        )
        center.resizeWeight = 0.45

        panel.add(north, BorderLayout.NORTH)
        panel.add(center, BorderLayout.CENTER)
        panel.add(actions, BorderLayout.SOUTH)
        return panel
    }

    private fun buildModePanel(): JPanel {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Start here"),
            BorderFactory.createEmptyBorder(4, 6, 6, 6),
        )

        val buttons = JPanel()
        buttons.add(recreateExistingButton)
        buttons.add(Box.createHorizontalStrut(8))
        buttons.add(createCustomButton)

        val help = JLabel(
            "<html><b>But why?</b> Recreating an existing Trade NPC is the safer option. " +
                "Creating a custom shop can spawn a new copy, but it needs X/Y/level and a rebuild.</html>",
        )

        panel.add(buttons, BorderLayout.NORTH)
        panel.add(modeInfo, BorderLayout.CENTER)
        panel.add(help, BorderLayout.SOUTH)
        return panel
    }

    private fun setShopMode(mode: ShopMode) {
        shopMode = mode
        refreshModeUi()
        refreshNpcList()
    }

    private fun refreshModeUi() {
        val custom = shopMode == ShopMode.CREATE_CUSTOM
        recreateExistingButton.isEnabled = custom
        createCustomButton.isEnabled = !custom
        saveShopButton.text = if (custom) "Place shop" else "Save shop"
        worldX.isEnabled = custom
        worldY.isEnabled = custom
        level.isEnabled = custom
        npcHint.text =
            if (custom) {
                "Create custom: shows Trade-capable NPC models. You choose where the new copy stands."
            } else {
                "Recreate existing: shows only in-world Trade NPCs that already have shop data."
            }
        modeInfo.text =
            if (custom) {
                "<html><b>Create custom:</b> choose a Trade-capable NPC model, add stock, enter the " +
                    "world X/Y/level where the new shop copy should stand, then rebuild cache/map data and restart.</html>"
            } else {
                "<html><b>Recreate existing:</b> choose an in-world shop NPC. This only changes the " +
                    "shop stock, title, and prices. It does not move the NPC. Use Create custom if " +
                    "you want a different location.</html>"
            }
    }

    private fun loadCache() {
        object : SwingWorker<LookupData, String>() {
            override fun doInBackground(): LookupData {
                publish("Loading cache definitions...")
                ServerCacheManager.init(239)
                publish("Indexing NPCs and items...")
                val spawnedNpcs = loadSpawnedNpcInternals(repoRoot)
                val shopParams = ShopParamIds.load(repoRoot)
                val rawShopIndex = loadRawShopIndex(repoRoot)
                val scriptedShopLinks = loadScriptedShopLinks(repoRoot, rawShopIndex)
                return LookupData(
                    npcs = ServerCacheManager.getNpcs().values
                        .mapNotNull { it.toLookupEntry(spawnedNpcs, shopParams, rawShopIndex, scriptedShopLinks) }
                        .sortedWith(
                            compareBy<LookupEntry> { !it.isExistingShopNpc }
                                .thenBy { !it.hasTrade }
                                .thenBy { it.name }
                                .thenBy { it.id },
                        ),
                    items = ServerCacheManager.getItems().values.mapNotNull { it.toLookupEntry() }
                        .sortedWith(compareBy<LookupEntry> { it.name }.thenBy { it.id }),
                )
            }

            override fun process(chunks: MutableList<String>) {
                status.text = chunks.lastOrNull() ?: status.text
            }

            override fun done() {
                try {
                    lookup = get()
                    status.text = "Loaded ${lookup.npcs.size} NPCs and ${lookup.items.size} items."
                    refreshNpcList()
                    refreshItemList()
                    updateDerivedSlug()
                } catch (e: Exception) {
                    status.text = "Failed to load cache."
                    showError("Could not load cache definitions.", e)
                }
            }
        }.execute()
    }

    private fun refreshNpcList() {
        val query = npcSearch.text.normalizedQuery()
        val filtered =
            lookup.npcs.asSequence()
                .filter { it.hasTrade }
                .filter { shopMode == ShopMode.CREATE_CUSTOM || it.isExistingShopNpc }
                .filter { it.matches(query) }
                .take(MAX_SEARCH_RESULTS)
                .toList()
        npcList.setListData(filtered.toTypedArray())
        if (npcList.selectedValue !in filtered) {
            npcList.clearSelection()
        }
    }

    private fun refreshItemList() {
        val query = itemSearch.text.normalizedQuery()
        itemList.setListData(
            lookup.items.asSequence()
                .filter { it.matches(query) }
                .take(MAX_SEARCH_RESULTS)
                .toList()
                .toTypedArray(),
        )
    }

    private fun updateDerivedSlug() {
        if (shopSlug.text.isBlank()) {
            shopSlug.text = shopName.text.toSlug()
        }
    }

    private fun addSelectedItem() {
        val item = itemList.selectedValue
        if (item == null) {
            JOptionPane.showMessageDialog(this, "Pick an item first.")
            return
        }
        stockModel.add(StockEntry(item, DEFAULT_STOCK_COUNT, DEFAULT_RESTOCK_CYCLES))
        status.text = "Added ${item.name}. Fill the shop details, then preview or place it."
    }

    private fun removeSelectedStockRow() {
        val table = findStockTable(this) ?: return
        val row = table.selectedRow
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Pick a stock row first.")
            return
        }
        stockModel.remove(row)
        status.text = "Removed stock item."
    }

    private fun loadSelectedNpcShop() {
        try {
            val selectedNpc = npcList.selectedValue ?: error("Pick a shop NPC first.")
            val stock =
                if (selectedNpc.shopStock.isNotEmpty()) {
                    selectedNpc.shopStock.map { stock ->
                        StockEntry(
                            item = lookup.itemByInternal(stock.itemInternal),
                            count = stock.count,
                            restockCycles = stock.restockCycles,
                        )
                    }
                } else {
                    val inventoryId = selectedNpc.shopInventoryId ?: error("That NPC does not have shop stock attached.")
                    val inventory = ServerCacheManager.getInventory(inventoryId)
                        ?: error("Could not find the shop inventory for ${selectedNpc.name}.")
                    inventory.stock.map { stock ->
                        StockEntry(
                            item = lookup.itemById(stock.obj),
                            count = stock.count,
                            restockCycles = stock.restockCycles,
                        )
                    }
                }
            require(stock.isNotEmpty()) { "That shop has no stock rows to load." }

            val slug = selectedNpc.shopInventoryInternal
                ?.removePrefix("inv.")
                ?.removePrefix("custom_shop_")
                ?.takeIf { it.isNotBlank() }
                ?: (selectedNpc.shopTitle ?: selectedNpc.name).toSlug()
            applyLoadedShop(
                LoadedShop(
                    label = selectedNpc.name,
                    mode = ShopMode.RECREATE_EXISTING,
                    npcInternal = selectedNpc.internal,
                    title = selectedNpc.shopTitle ?: "${selectedNpc.name} Shop",
                    slug = slug,
                    coord = null,
                    buyMultiplier = selectedNpc.shopBuyMultiplier ?: DEFAULT_BUY_MULTIPLIER,
                    sellMultiplier = selectedNpc.shopSellMultiplier ?: DEFAULT_SELL_MULTIPLIER,
                    changeDelta = selectedNpc.shopChangeDelta ?: DEFAULT_CHANGE_DELTA,
                    stock = stock,
                ),
            )
        } catch (e: Exception) {
            showError("Could not load the selected NPC shop.", e)
        }
    }

    private fun loadSavedShop() {
        try {
            val shops = loadGeneratedShops(repoRoot, lookup)
            if (shops.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No saved Shop Manager shops found yet.")
                return
            }
            val selected = JOptionPane.showInputDialog(
                this,
                "Pick a saved Shop Manager shop to edit.",
                "Load saved shop",
                JOptionPane.PLAIN_MESSAGE,
                null,
                shops.toTypedArray(),
                shops.first(),
            ) as? LoadedShop ?: return
            applyLoadedShop(selected)
        } catch (e: Exception) {
            showError("Could not load saved shops.", e)
        }
    }

    private fun applyLoadedShop(shop: LoadedShop) {
        setShopMode(shop.mode)
        shopSlug.text = shop.slug
        shopName.text = shop.title
        buyMultiplier.value = shop.buyMultiplier
        sellMultiplier.value = shop.sellMultiplier
        changeDelta.value = shop.changeDelta
        if (shop.coord != null) {
            worldX.text = shop.coord.x.toString()
            worldY.text = shop.coord.y.toString()
            level.text = shop.coord.level.toString()
        } else {
            worldX.text = ""
            worldY.text = ""
            level.text = "0"
        }
        stockModel.replaceAll(shop.stock)
        val selectedNpc = lookup.npcs.firstOrNull { it.internal == shop.npcInternal }
        if (selectedNpc != null) {
            npcSearch.text = ""
            refreshNpcList()
            npcList.setSelectedValue(selectedNpc, true)
        }
        preview.text = ""
        status.text = "Loaded ${shop.title}. Edit it, then save the changes."
    }

    private fun previewShop() {
        try {
            val generated = buildGeneratedFiles(readSpec())
            preview.text = generated.preview()
            status.text = "Preview ready."
        } catch (e: Exception) {
            showError("Could not build shop preview.", e)
        }
    }

    private fun placeShop() {
        try {
            val spec = readSpec()
            val generated = buildGeneratedFiles(spec)
            val existingShopAtTile = spec.coord?.let { findGeneratedShopAt(repoRoot, it, spec.marker) }
            if (existingShopAtTile != null) {
                val replace = JOptionPane.showConfirmDialog(
                    this,
                    "A generated shop already exists at ${spec.coord?.label}.\n\n" +
                        "${existingShopAtTile.npcInternal}\n\nReplace that generated shop?",
                    "Shop already exists",
                    JOptionPane.YES_NO_OPTION,
                )
                if (replace != JOptionPane.YES_OPTION) {
                    status.text = "Shop placement cancelled."
                    return
                }
                removeGeneratedShop(repoRoot, existingShopAtTile.marker)
            }
            generated.write(repoRoot)
            preview.text = generated.preview()
            status.text =
                if (spec.spawnNpc) {
                    "Placed ${spec.shopTitle}. Rebuild cache/map data and restart server before testing."
                } else {
                    "Saved ${spec.shopTitle}. Restart server before testing."
                }
            JOptionPane.showMessageDialog(
                this,
                if (spec.spawnNpc) {
                    "Shop placed. Rebuild cache/map data with :or-cache:buildCache, then restart the server."
                } else {
                    "Shop saved. Restart the server before testing."
                },
            )
        } catch (e: Exception) {
            showError("Could not place shop.", e)
        }
    }

    private fun readSpec(): ShopSpec {
        val selectedNpc = npcList.selectedValue
            ?: error("Pick a shop NPC first.")
        require(selectedNpc.hasTrade) { "Pick an NPC with a Trade option." }
        require(shopMode == ShopMode.CREATE_CUSTOM || selectedNpc.isExistingShopNpc) {
            "Recreate existing only supports in-world NPCs that already have shop data. " +
                "Use Create custom if you want to place a new shop at different coordinates."
        }
        val slug = shopSlug.text.toSlug()
        require(slug.isNotBlank()) { "Shop slug cannot be empty." }
        require(stockModel.rows.isNotEmpty()) { "Add at least one stock item." }
        require(stockModel.rows.size <= MAX_RENDER_STOCK_ROWS) {
            "Generated shops currently support up to $MAX_RENDER_STOCK_ROWS stock rows. " +
                "Split larger shops or test a larger render inventory first."
        }

        val shouldSpawnNpc = shopMode == ShopMode.CREATE_CUSTOM
        val coord =
            if (shouldSpawnNpc) {
                val x = worldX.text.trim().toIntOrNull() ?: error("World X must be a number.")
                val y = worldY.text.trim().toIntOrNull() ?: error("World Y must be a number.")
                val z = level.text.trim().toIntOrNull() ?: error("Level must be a number.")
                WorldCoord(x, y, z)
            } else {
                null
            }

        return ShopSpec(
            shopTitle = shopName.text.trim().ifBlank { "Custom Shop" },
            slug = slug,
            inheritedNpc = selectedNpc,
            coord = coord,
            spawnNpc = shouldSpawnNpc,
            buyMultiplier = buyMultiplier.value as Int,
            sellMultiplier = sellMultiplier.value as Int,
            changeDelta = changeDelta.value as Int,
            stock = stockModel.rows.toList(),
        )
    }

    private fun showError(message: String, e: Exception) {
        JOptionPane.showMessageDialog(this, "$message\n\n${e.message}", "Shop Manager", JOptionPane.ERROR_MESSAGE)
    }

    private companion object {
        private const val MAX_SEARCH_RESULTS = 500
        private const val DEFAULT_STOCK_COUNT = 10
        private const val DEFAULT_RESTOCK_CYCLES = 100
        private const val DEFAULT_BUY_MULTIPLIER = 600
        private const val DEFAULT_SELL_MULTIPLIER = 1000
        private const val DEFAULT_CHANGE_DELTA = 20
        private const val MAX_RENDER_STOCK_ROWS = 22
    }
}

private enum class ShopMode {
    RECREATE_EXISTING,
    CREATE_CUSTOM,
}

private data class LookupData(
    val npcs: List<LookupEntry>,
    val items: List<LookupEntry>,
) {
    private val itemsById: Map<Int, LookupEntry> = items.associateBy { it.id }
    private val itemsByInternal: Map<String, LookupEntry> = items.associateBy { it.internal }

    fun itemById(id: Int): LookupEntry =
        itemsById[id] ?: LookupEntry(
            id = id,
            internal = safeReverseMapping(RSCMType.OBJ, id),
            name = safeReverseMapping(RSCMType.OBJ, id).removePrefix("obj."),
            actions = "",
            hasTrade = false,
        )

    fun itemByInternal(internal: String): LookupEntry =
        itemsByInternal[internal] ?: LookupEntry(
            id = -1,
            internal = internal,
            name = internal.removePrefix("obj."),
            actions = "",
            hasTrade = false,
        )
}

private data class LookupEntry(
    val id: Int,
    val internal: String,
    val name: String,
    val actions: String,
    val hasTrade: Boolean,
    val hasShopInventory: Boolean = false,
    val isSpawned: Boolean = false,
    val shopInventoryId: Int? = null,
    val shopInventoryInternal: String? = null,
    val shopTitle: String? = null,
    val shopBuyMultiplier: Int? = null,
    val shopSellMultiplier: Int? = null,
    val shopChangeDelta: Int? = null,
    val shopStock: List<RawStockEntry> = emptyList(),
    val cost: Int = 0,
) {
    val isExistingShopNpc: Boolean
        get() = hasTrade && hasShopInventory && isSpawned

    val label: String
        get() {
            val actionText = if (actions.isBlank()) "" else " | $actions"
            val costText = if (cost > 0) " | ${cost}gp" else ""
            return "$name [$id] $internal$actionText$costText"
        }

    fun matches(query: String): Boolean {
        if (query.isBlank()) {
            return true
        }
        return label.lowercase().contains(query)
    }

    override fun toString(): String = label
}

private data class StockEntry(
    val item: LookupEntry,
    var count: Int,
    var restockCycles: Int,
)

private data class RawStockEntry(
    val itemInternal: String,
    val count: Int,
    val restockCycles: Int,
)

private data class ShopInventoryInfo(
    val internal: String,
    val title: String,
    val buyMultiplier: Int,
    val sellMultiplier: Int,
    val changeDelta: Int,
    val stock: List<RawStockEntry>,
)

private data class RawShopIndex(
    val byInternal: Map<String, ShopInventoryInfo>,
) {
    private val shops: List<ShopInventoryInfo> = byInternal.values.toList()

    fun byInternal(internal: String?): ShopInventoryInfo? =
        internal?.let { byInternal[it] }

    fun matchNpc(name: String, internal: String): ShopInventoryInfo? {
        val candidates =
            sequenceOf(name, internal.removePrefix("npc.").replace('_', ' '))
                .map(::shopSearchKey)
                .filter { it.length >= 3 && it !in GENERIC_SHOP_MATCH_KEYS }
                .flatMap { key -> sequenceOf(key, "${key}s") }
                .distinct()
                .toList()
        if (candidates.isEmpty()) {
            return null
        }
        return shops.firstOrNull { shop ->
            val titleKey = shopSearchKey(shop.title)
            candidates.any { candidate -> candidate in titleKey }
        }
    }
}

private data class LoadedShop(
    val label: String,
    val mode: ShopMode,
    val npcInternal: String,
    val title: String,
    val slug: String,
    val coord: WorldCoord?,
    val buyMultiplier: Int,
    val sellMultiplier: Int,
    val changeDelta: Int,
    val stock: List<StockEntry>,
) {
    override fun toString(): String {
        val modeText = if (mode == ShopMode.CREATE_CUSTOM) "custom coords" else "existing NPC"
        return "$title - $label ($modeText)"
    }
}

private data class ShopParamIds(
    val inventory: Int,
    val name: Int,
    val sellPercentage: Int,
    val buyPercentage: Int,
    val changePercentage: Int,
) {
    companion object {
        fun load(root: Path): ShopParamIds {
            val file = root.resolve(PARAM_GAMEVALS)
            return ShopParamIds(
                inventory = gamevalId(file, "shop_inventory") ?: SHOP_INVENTORY_PARAM_ID,
                name = gamevalId(file, "shop_name") ?: SHOP_NAME_PARAM_ID,
                sellPercentage = gamevalId(file, "shop_sell_percentage") ?: SHOP_SELL_PERCENTAGE_PARAM_ID,
                buyPercentage = gamevalId(file, "shop_buy_percentage") ?: SHOP_BUY_PERCENTAGE_PARAM_ID,
                changePercentage = gamevalId(file, "shop_change_percentage") ?: SHOP_CHANGE_PERCENTAGE_PARAM_ID,
            )
        }
    }
}

private data class ShopSpec(
    val shopTitle: String,
    val slug: String,
    val inheritedNpc: LookupEntry,
    val coord: WorldCoord?,
    val spawnNpc: Boolean,
    val buyMultiplier: Int,
    val sellMultiplier: Int,
    val changeDelta: Int,
    val stock: List<StockEntry>,
) {
    val invInternal: String get() = "inv.custom_shop_$slug"
    val npcInternal: String get() = inheritedNpc.internal
    val marker: String get() = "custom_shop_$slug"
}

private data class WorldCoord(val x: Int, val y: Int, val level: Int) {
    val label: String get() = "$x, $y, $level"

    fun toCoordGridString(): String {
        val mapX = x.floorDiv(64)
        val mapY = y.floorDiv(64)
        val localX = Math.floorMod(x, 64)
        val localY = Math.floorMod(y, 64)
        return "${level}_${mapX}_${mapY}_${localX}_${localY}"
    }
}

private data class GeneratedFiles(
    val marker: String,
    val coord: WorldCoord?,
    val shopToml: String,
    val npcToml: String,
    val spawnToml: String?,
    val invGameval: String,
) {
    fun preview(): String =
        buildString {
            appendLine("----- NPC mode -----")
            if (coord == null) {
                appendLine("Patches existing visible NPC type: no new NPC spawn.")
            } else {
                appendLine("Spawns visible NPC copy at:")
                appendLine("World: ${coord.label}")
                appendLine("Map: ${coord.toCoordGridString()}")
            }
            appendLine()
            appendLine("----- .data/raw-cache/server/shops/custom_shops.toml -----")
            appendLine(shopToml)
            appendLine("----- .data/raw-cache/server/custom_shop_npcs.toml -----")
            appendLine(npcToml)
            appendLine("----- .data/raw-cache/map/npcs/custom_shops.toml -----")
            appendLine(spawnToml ?: "No new NPC spawn.")
            appendLine("----- .data/gamevals/inv.rscm -----")
            appendLine(invGameval)
        }

    fun write(root: Path) {
        upsertManagedBlock(root.resolve(CUSTOM_SHOPS_TOML), marker, shopToml)
        upsertManagedBlock(root.resolve(CUSTOM_SHOP_NPCS_TOML), marker, npcToml)
        if (spawnToml != null) {
            upsertManagedBlock(root.resolve(CUSTOM_SHOP_SPAWNS_TOML), marker, spawnToml)
        } else {
            removeManagedBlock(root.resolve(CUSTOM_SHOP_SPAWNS_TOML), marker)
        }
        removeGameval(root.resolve(NPC_GAMEVALS), marker)
        upsertGameval(root.resolve(INV_GAMEVALS), invGameval)
    }
}

private data class ExistingGeneratedShop(
    val marker: String,
    val npcInternal: String,
)

private class StockTableModel : AbstractTableModel() {
    val rows: MutableList<StockEntry> = mutableListOf()
    private val columns = arrayOf("Item", "Internal", "ID", "Count", "Restock cycles")

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 3 || columnIndex == 4

    override fun getColumnClass(columnIndex: Int): Class<*> =
        when (columnIndex) {
            2, 3, 4 -> Int::class.javaObjectType
            else -> String::class.java
        }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.item.name
            1 -> row.item.internal
            2 -> row.item.id
            3 -> row.count
            4 -> row.restockCycles
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val parsed = value.toString().toIntOrNull()?.coerceAtLeast(0) ?: return
        val row = rows[rowIndex]
        when (columnIndex) {
            3 -> row.count = parsed
            4 -> row.restockCycles = parsed
        }
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    fun add(entry: StockEntry) {
        rows += entry
        fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
    }

    fun remove(index: Int) {
        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    fun replaceAll(nextRows: List<StockEntry>) {
        rows.clear()
        rows += nextRows
        fireTableDataChanged()
    }
}

private class LookupRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): java.awt.Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        if (component is JLabel && value is LookupEntry) {
            component.text = value.label
        }
        return component
    }
}

private class SimpleDocumentListener(private val callback: () -> Unit) : DocumentListener {
    override fun insertUpdate(e: DocumentEvent?) = callback()
    override fun removeUpdate(e: DocumentEvent?) = callback()
    override fun changedUpdate(e: DocumentEvent?) = callback()
}

private fun buildGeneratedFiles(spec: ShopSpec): GeneratedFiles {
    val invKey = spec.invInternal.removePrefix("inv.")
    val existingInvGameval = existingGameval(Paths.get(INV_GAMEVALS), invKey)?.takeIf { it in INV_CUSTOM_ID_RANGE }

    val shopToml =
        buildString {
            appendLine("[[inventory]]")
            appendLine("isServerOnly = true")
            appendLine("id = ${spec.invInternal.tomlString()}")
            appendLine("name = ${spec.shopTitle.tomlString()}")
            appendLine()
            appendLine("scope = \"Shared\"")
            appendLine("stack = \"Always\"")
            appendLine()
            appendLine("sellMultiplier = ${spec.sellMultiplier}")
            appendLine("buyMultiplier = ${spec.buyMultiplier}")
            appendLine("delta = ${spec.changeDelta}")
            appendLine()
            appendLine("size = ${max(spec.stock.size, 1)}")
            appendLine()
            appendLine("protect = false")
            appendLine("runWeight = false")
            appendLine("restock = true")
            appendLine("allStock = false")
            appendLine("placeholders = false")
            appendLine()
            spec.stock.forEach { entry ->
                appendLine("[[inventory.stock]]")
                appendLine("obj = ${entry.item.internal.tomlString()}")
                appendLine("count = ${entry.count}")
                appendLine("restockCycles = ${entry.restockCycles}")
                appendLine()
            }
        }.trimEnd()

    val npcToml =
        buildString {
            appendLine("[[npc]]")
            appendLine("id = ${spec.npcInternal.tomlString()}")
            appendLine("inherit = ${spec.inheritedNpc.internal.tomlString()}")
            appendLine("moveRestrict = \"NoMove\"")
            appendLine("defaultMode = \"None\"")
            appendLine("wanderRange = 0")
            appendLine()
            appendLine("[npc.params]")
            appendLine("\"param.shop_inventory\" = ${spec.invInternal.tomlString()}")
            appendLine("\"param.shop_name\" = ${spec.shopTitle.tomlString()}")
            appendLine("\"param.shop_sell_percentage\" = ${spec.sellMultiplier}")
            appendLine("\"param.shop_buy_percentage\" = ${spec.buyMultiplier}")
            appendLine("\"param.shop_change_percentage\" = ${spec.changeDelta}")
        }.trimEnd()

    val spawnToml =
        spec.coord?.let { coord ->
            buildString {
                appendLine("[[spawn]]")
                appendLine("npc = ${spec.npcInternal.tomlString()}")
                appendLine("coords = ${coord.toCoordGridString().tomlString()}")
            }.trimEnd()
        }

    val invGameval = "$invKey=${existingInvGameval ?: nextGameval(Paths.get(INV_GAMEVALS), INV_CUSTOM_ID_RANGE)}"

    return GeneratedFiles(
        marker = spec.marker,
        coord = spec.coord,
        shopToml = shopToml,
        npcToml = npcToml,
        spawnToml = spawnToml,
        invGameval = invGameval,
    )
}

private fun findGeneratedShopAt(root: Path, coord: WorldCoord, currentMarker: String): ExistingGeneratedShop? {
    val file = root.resolve(CUSTOM_SHOP_SPAWNS_TOML)
    if (!Files.exists(file)) {
        return null
    }
    val coordGrid = coord.toCoordGridString()
    val coordPattern = Regex("""coords\s*=\s*"${Regex.escape(coordGrid)}"""")
    val npcPattern = Regex("""npc\s*=\s*"([^"]+)"""")
    return managedBlockPattern.findAll(Files.readString(file))
        .mapNotNull { match ->
            val marker = match.groupValues[1]
            if (marker == currentMarker) {
                return@mapNotNull null
            }
            val block = match.groupValues[2]
            if (!coordPattern.containsMatchIn(block)) {
                return@mapNotNull null
            }
            val npcInternal = npcPattern.find(block)?.groupValues?.get(1) ?: marker
            ExistingGeneratedShop(marker, npcInternal)
        }
        .firstOrNull()
}

private fun loadGeneratedShops(root: Path, lookup: LookupData): List<LoadedShop> {
    val inventoryBlocks = managedBlocks(root.resolve(CUSTOM_SHOPS_TOML))
    val npcBlocks = managedBlocks(root.resolve(CUSTOM_SHOP_NPCS_TOML))
    val spawnBlocks = managedBlocks(root.resolve(CUSTOM_SHOP_SPAWNS_TOML))
    return (inventoryBlocks.keys + npcBlocks.keys)
        .distinct()
        .mapNotNull { marker ->
            val inventoryBlock = inventoryBlocks[marker] ?: return@mapNotNull null
            val npcBlock = npcBlocks[marker] ?: return@mapNotNull null
            val invInternal = tomlStringValue(inventoryBlock, "id") ?: return@mapNotNull null
            val npcInternal = tomlStringValue(npcBlock, "id") ?: return@mapNotNull null
            val coord = spawnBlocks[marker]?.let { tomlStringValue(it, "coords") }?.let(::parseCoords)
            val title =
                tomlStringValue(npcBlock, "param.shop_name")
                    ?: tomlStringValue(inventoryBlock, "name")
                    ?: marker.removePrefix("custom_shop_").replace('_', ' ')
            val stock = inventoryStockRows(inventoryBlock, lookup)
            LoadedShop(
                label = marker.removePrefix("custom_shop_").replace('_', ' '),
                mode = if (coord == null) ShopMode.RECREATE_EXISTING else ShopMode.CREATE_CUSTOM,
                npcInternal = npcInternal,
                title = title,
                slug = invInternal.removePrefix("inv.").removePrefix("custom_shop_"),
                coord = coord,
                buyMultiplier =
                    tomlIntValue(npcBlock, "param.shop_buy_percentage")
                        ?: tomlIntValue(inventoryBlock, "buyMultiplier")
                        ?: 600,
                sellMultiplier =
                    tomlIntValue(npcBlock, "param.shop_sell_percentage")
                        ?: tomlIntValue(inventoryBlock, "sellMultiplier")
                        ?: 1000,
                changeDelta =
                    tomlIntValue(npcBlock, "param.shop_change_percentage")
                        ?: tomlIntValue(inventoryBlock, "delta")
                        ?: 20,
                stock = stock,
            )
        }
        .sortedBy { it.title.lowercase() }
}

private fun managedBlocks(file: Path): Map<String, String> {
    if (!Files.exists(file)) {
        return emptyMap()
    }
    return managedBlockPattern.findAll(Files.readString(file))
        .associate { match -> match.groupValues[1] to match.groupValues[2] }
}

private fun inventoryStockRows(block: String, lookup: LookupData): List<StockEntry> {
    val stockBlock = Regex("""(?s)\[\[inventory\.stock]](.*?)(?=\R\[\[inventory\.stock]]|\z)""")
    return stockBlock.findAll(block)
        .mapNotNull { match ->
            val text = match.groupValues[1]
            val itemInternal = tomlStringValue(text, "obj") ?: return@mapNotNull null
            StockEntry(
                item = lookup.itemByInternal(itemInternal),
                count = tomlIntValue(text, "count") ?: 0,
                restockCycles = tomlIntValue(text, "restockCycles") ?: 100,
            )
        }
        .toList()
}

private fun tomlStringValue(block: String, key: String): String? {
    val regex = Regex("""(?m)^\s*"?\Q$key\E"?\s*=\s*"([^"]*)"""")
    return regex.find(block)?.groupValues?.get(1)
}

private fun tomlIntValue(block: String, key: String): Int? {
    val regex = Regex("""(?m)^\s*"?\Q$key\E"?\s*=\s*(-?\d+)""")
    return regex.find(block)?.groupValues?.get(1)?.toIntOrNull()
}

private fun removeGeneratedShop(root: Path, marker: String) {
    GENERATED_SHOP_TOML_FILES.forEach { file ->
        removeManagedBlock(root.resolve(file), marker)
    }
    removeGameval(root.resolve(NPC_GAMEVALS), marker)
    removeGameval(root.resolve(INV_GAMEVALS), marker)
}

private fun upsertManagedBlock(file: Path, marker: String, block: String) {
    Files.createDirectories(file.parent)
    val begin = "# BEGIN SHOP MAKER: $marker"
    val end = "# END SHOP MAKER: $marker"
    val wrapped = "$begin\n${block.trimEnd()}\n$end\n"
    val existing = if (Files.exists(file)) Files.readString(file) else ""
    val pattern = Regex("${Regex.escape(begin)}\\R.*?${Regex.escape(end)}\\R?", RegexOption.DOT_MATCHES_ALL)
    val next =
        if (pattern.containsMatchIn(existing)) {
            existing.replace(pattern, wrapped)
        } else {
            buildString {
                append(existing.trimEnd())
                if (isNotEmpty()) {
                    appendLine()
                    appendLine()
                }
                append(wrapped)
            }
        }
    Files.writeString(file, next)
}

private fun removeManagedBlock(file: Path, marker: String) {
    if (!Files.exists(file)) {
        return
    }
    val begin = "# BEGIN SHOP MAKER: $marker"
    val end = "# END SHOP MAKER: $marker"
    val existing = Files.readString(file)
    val pattern = Regex("${Regex.escape(begin)}\\R.*?${Regex.escape(end)}\\R?", RegexOption.DOT_MATCHES_ALL)
    val next = existing.replace(pattern, "").trimEnd()
    Files.writeString(file, if (next.isBlank()) "" else "$next\n")
}

private fun upsertGameval(file: Path, line: String) {
    Files.createDirectories(file.parent)
    val key = line.substringBefore('=').trim()
    val existing = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
    var replaced = false
    val next =
        existing
            .filterNot { it.startsWith("# BEGIN SHOP MAKER:") || it.startsWith("# END SHOP MAKER:") }
            .mapNotNull { existingLine ->
                if (existingLine.isBlank()) {
                    return@mapNotNull null
                }
                if (existingLine.substringBefore('=').trim() == key) {
                    replaced = true
                    line
                } else {
                    existingLine
                }
            }
            .toMutableList()
    if (!replaced) {
        next += line
    }
    Files.write(file, next)
}

private fun removeGameval(file: Path, key: String) {
    if (!Files.exists(file)) {
        return
    }
    val next =
        Files.readAllLines(file)
            .filterNot { line ->
                line.isBlank() ||
                    line.startsWith("# BEGIN SHOP MAKER:") ||
                    line.startsWith("# END SHOP MAKER:") ||
                    line.substringBefore('=').trim() == key
            }
    Files.write(file, next)
}

private fun existingGameval(file: Path, key: String): Int? {
    if (!Files.exists(file)) {
        return null
    }
    val regex = Regex("^\\s*${Regex.escape(key)}\\s*=\\s*(-?\\d+)\\s*$")
    return Files.readAllLines(file)
        .asSequence()
        .mapNotNull { regex.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        .firstOrNull()
}

private fun nextGameval(file: Path, range: IntRange): Int {
    val used =
        if (Files.exists(file)) {
            Files.readAllLines(file)
                .asSequence()
                .mapNotNull { it.substringAfter('=', "").trim().toIntOrNull() }
                .filter { it in range }
                .toSet()
        } else {
            emptySet()
        }
    return range.firstOrNull { it !in used } ?: error("No free gameval ids left in $range.")
}

private fun loadSpawnedNpcInternals(root: Path): Set<String> {
    val dir = root.resolve(MAP_NPC_SPAWNS_DIR)
    if (!Files.isDirectory(dir)) {
        return emptySet()
    }
    val npcLine = Regex("""^\s*npc\s*=\s*"([^"]+)"""")
    Files.walk(dir).use { paths ->
        return paths.iterator().asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".toml") }
            .flatMap { file -> Files.readAllLines(file).asSequence() }
            .mapNotNull { line -> npcLine.find(line)?.groupValues?.get(1) }
            .filter { it.startsWith("npc.") }
            .toSet()
    }
}

private fun loadRawShopIndex(root: Path): RawShopIndex {
    val dir = root.resolve(RAW_SHOPS_DIR)
    if (!Files.isDirectory(dir)) {
        return RawShopIndex(emptyMap())
    }
    val shops =
        Files.walk(dir).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".toml") }
                .flatMap { file -> inventoryBlocks(Files.readString(file)).asSequence() }
                .mapNotNull(::parseShopInventoryInfo)
                .associateBy { it.internal }
        }
    return RawShopIndex(shops)
}

private fun inventoryBlocks(text: String): List<String> {
    val pattern = Regex("""(?s)\[\[inventory]](.*?)(?=\R\[\[inventory]]|\z)""")
    return pattern.findAll(text).map { it.groupValues[1] }.toList()
}

private fun parseShopInventoryInfo(block: String): ShopInventoryInfo? {
    val internal = tomlStringValue(block, "id")?.takeIf { it.startsWith("inv.") } ?: return null
    return ShopInventoryInfo(
        internal = internal,
        title = tomlStringValue(block, "name") ?: internal.removePrefix("inv.").replace('_', ' '),
        buyMultiplier = tomlIntValue(block, "buyMultiplier") ?: 600,
        sellMultiplier = tomlIntValue(block, "sellMultiplier") ?: 1000,
        changeDelta = tomlIntValue(block, "delta") ?: 20,
        stock = rawInventoryStockRows(block),
    )
}

private fun rawInventoryStockRows(block: String): List<RawStockEntry> {
    val stockBlock = Regex("""(?s)\[\[inventory\.stock]](.*?)(?=\R\[\[inventory\.stock]]|\z)""")
    return stockBlock.findAll(block)
        .mapNotNull { match ->
            val text = match.groupValues[1]
            val itemInternal = tomlStringValue(text, "obj") ?: return@mapNotNull null
            RawStockEntry(
                itemInternal = itemInternal,
                count = tomlIntValue(text, "count") ?: 0,
                restockCycles = tomlIntValue(text, "restockCycles") ?: 100,
            )
        }
        .toList()
}

private fun loadScriptedShopLinks(root: Path, rawShopIndex: RawShopIndex): Map<String, ShopInventoryInfo> {
    val contentDir = root.resolve("content")
    if (!Files.isDirectory(contentDir)) {
        return emptyMap()
    }
    val links = mutableMapOf<String, ShopInventoryInfo>()
    val npcPattern = Regex("""onOpNpc\d\s*\(\s*"([^"]+)"""")
    val shopOpenPattern =
        Regex("""shops\.open\s*\((?s:.*?)"[^"]+"\s*,\s*"(inv\.[^"]+)"""")
    Files.walk(contentDir).use { paths ->
        paths.iterator().asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .forEach { file ->
                val text = Files.readString(file)
                val npcs = npcPattern.findAll(text).map { it.groupValues[1] }.filter { it.startsWith("npc.") }.toSet()
                val shopInvs = shopOpenPattern.findAll(text).map { it.groupValues[1] }.toSet()
                if (npcs.isNotEmpty() && shopInvs.size == 1) {
                    val shop = rawShopIndex.byInternal(shopInvs.single()) ?: return@forEach
                    npcs.forEach { npc -> links.putIfAbsent(npc, shop) }
                }
            }
    }
    return links
}

private fun gamevalId(file: Path, key: String): Int? = existingGameval(file, key)

private fun Any?.asParamInt(): Int? =
    when (this) {
        is Int -> this
        is Number -> toInt()
        is String -> toIntOrNull() ?: runCatching { RSCM.getRSCM(this) }.getOrNull()
        else -> null
    }

private fun safeReverseMapping(type: RSCMType, id: Int): String =
    runCatching { RSCM.getReverseMapping(type, id) }
        .getOrDefault("${type.prefix}.$id")
        .takeUnless { it == "-1" }
        ?: "${type.prefix}.$id"

private fun shopSearchKey(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

private fun NpcServerType.toLookupEntry(
    spawnedNpcs: Set<String>,
    shopParams: ShopParamIds,
    rawShopIndex: RawShopIndex,
    scriptedShopLinks: Map<String, ShopInventoryInfo>,
): LookupEntry? {
    val internal = runCatching { internalName }.getOrNull()?.takeIf { it.startsWith("npc.") } ?: return null
    val ops = (1..5).mapNotNull { slot ->
        actions.getOpOrNull(slot - 1)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("hidden", ignoreCase = true) }
            ?.let { "$slot:$it" }
    }
    val hasTrade = ops.any { it.substringAfter(':').equals("trade", ignoreCase = true) }
    val paramShopInventoryId = paramsRaw?.get(shopParams.inventory).asParamInt()
    val paramShopInventoryInternal = paramShopInventoryId?.let { safeReverseMapping(RSCMType.INV, it) }
    val linkedShop =
        rawShopIndex.byInternal(paramShopInventoryInternal)
            ?: scriptedShopLinks[internal]
            ?: rawShopIndex.matchNpc(name, internal)
    val shopInventoryId =
        paramShopInventoryId ?: linkedShop?.internal?.let { runCatching { RSCM.getRSCM(it) }.getOrNull() }
    val hasShopInventory = paramShopInventoryId != null || linkedShop != null
    return LookupEntry(
        id = id,
        internal = internal,
        name = name.ifBlank { internal.removePrefix("npc.") },
        actions = ops.joinToString(", "),
        hasTrade = hasTrade,
        hasShopInventory = hasShopInventory,
        isSpawned = internal in spawnedNpcs,
        shopInventoryId = shopInventoryId,
        shopInventoryInternal = paramShopInventoryInternal ?: linkedShop?.internal,
        shopTitle = paramsRaw?.get(shopParams.name) as? String ?: linkedShop?.title,
        shopBuyMultiplier = paramsRaw?.get(shopParams.buyPercentage).asParamInt() ?: linkedShop?.buyMultiplier,
        shopSellMultiplier = paramsRaw?.get(shopParams.sellPercentage).asParamInt() ?: linkedShop?.sellMultiplier,
        shopChangeDelta = paramsRaw?.get(shopParams.changePercentage).asParamInt() ?: linkedShop?.changeDelta,
        shopStock = linkedShop?.stock.orEmpty(),
    )
}

private fun ItemServerType.toLookupEntry(): LookupEntry? {
    val internal = runCatching { internalName }.getOrNull()?.takeIf { it.startsWith("obj.") } ?: return null
    val displayName = name.trim().ifBlank { internal.removePrefix("obj.") }
    if (displayName.equals("null", ignoreCase = true)) {
        return null
    }
    return LookupEntry(
        id = id,
        internal = internal,
        name = displayName,
        actions = "",
        hasTrade = false,
        cost = cost,
    )
}

private fun parseCoords(input: String): WorldCoord? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) {
        return null
    }

    val worldLine = Regex("""(?i)world\s+(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)""").find(trimmed)
    if (worldLine != null) {
        val parts = worldLine.groupValues.drop(1).map { it.toInt() }
        return WorldCoord(parts[0], parts[1], parts[2])
    }

    val coordGrid = Regex("""(\d+)_(\d+)_(\d+)_(\d+)_(\d+)""").find(trimmed)
    if (coordGrid != null) {
        val parts = coordGrid.groupValues.drop(1).map { it.toInt() }
        val level = parts[0]
        val x = parts[1] * 64 + parts[3]
        val y = parts[2] * 64 + parts[4]
        return WorldCoord(x, y, level)
    }

    val numbers = Regex("""-?\d+""").findAll(trimmed).map { it.value.toInt() }.toList()
    if (numbers.size >= 3) {
        return WorldCoord(numbers[0], numbers[1], numbers[2])
    }
    return null
}

private fun ensureShopMakerCompatibility(repoRoot: Path): Boolean =
    ensureInventoryCodecCompatibility(repoRoot) && ensureCustomShopRuntimeCompatibility(repoRoot)

private fun ensureInventoryCodecCompatibility(repoRoot: Path): Boolean {
    val file = repoRoot.resolve(INVENTORY_SERVER_CODEC).normalize()
    if (!Files.exists(file)) {
        JOptionPane.showMessageDialog(
            null,
            "Could not find $INVENTORY_SERVER_CODEC.\n\nOpen the shop manager from the OpenRune server root.",
            "Shop Manager",
            JOptionPane.ERROR_MESSAGE,
        )
        return false
    }

    val text = Files.readString(file)
    val newline = if ("\r\n" in text) "\r\n" else "\n"
    val normalized = text.replace("\r\n", "\n")
    if (hasInventoryCodecSupport(normalized)) {
        return true
    }
    val sourceBlock =
        listOf(INVENTORY_CODEC_OLD_BLOCK, INVENTORY_CODEC_BROAD_PATCH_BLOCK)
            .firstOrNull { normalized.contains(it) }
    if (sourceBlock == null) {
        JOptionPane.showMessageDialog(
            null,
            "Shop Manager needs custom shop inventory support, but the cache packer file does not match " +
                "the expected OpenRune layout.\n\nNo files were changed.",
            "Shop Manager",
            JOptionPane.ERROR_MESSAGE,
        )
        return false
    }

    val install =
        JOptionPane.showConfirmDialog(
            null,
            "Shop Manager needs a one-time OpenRune compatibility fix so newly generated shop inventories " +
                "can keep their stock.\n\nA backup will be created beside InventoryServerCodec.kt.",
            "Install Shop Manager compatibility fix?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
    if (install != JOptionPane.YES_OPTION) {
        return false
    }

    val backup = file.resolveSibling("${file.fileName}.shop-maker.bak")
    if (!Files.exists(backup)) {
        Files.copy(file, backup)
    }
    val patched = normalized.replace(sourceBlock, INVENTORY_CODEC_NEW_BLOCK)
    Files.writeString(file, patched.replace("\n", newline))
    JOptionPane.showMessageDialog(
        null,
        "Compatibility fix installed.\n\nRebuild the cache before testing generated shops.",
        "Shop Manager",
        JOptionPane.INFORMATION_MESSAGE,
    )
    return true
}

private fun ensureCustomShopRuntimeCompatibility(repoRoot: Path): Boolean {
    val scriptFile = repoRoot.resolve(CUSTOM_SHOP_RUNTIME_SCRIPT).normalize()
    val buildFile = repoRoot.resolve(GENERIC_NPCS_BUILD_FILE).normalize()
    if (!Files.exists(buildFile)) {
        JOptionPane.showMessageDialog(
            null,
            "Could not find $GENERIC_NPCS_BUILD_FILE.\n\nOpen the shop manager from the OpenRune server root.",
            "Shop Manager",
            JOptionPane.ERROR_MESSAGE,
        )
        return false
    }

    val desired =
        try {
            loadBundledCustomShopRuntimeScript()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                null,
                "Could not load the bundled custom shop runtime script.\n\n${e.message}",
                "Shop Manager",
                JOptionPane.ERROR_MESSAGE,
            )
            return false
        }
    val current = if (Files.exists(scriptFile)) Files.readString(scriptFile).replace("\r\n", "\n") else ""
    val dependencyInstalled = Files.readString(buildFile).contains(SHOPS_API_DEPENDENCY)
    if (
        current.trimEnd() == desired.trimEnd() &&
        current.contains(CUSTOM_SHOP_RUNTIME_MARKER) &&
        dependencyInstalled
    ) {
        return true
    }
    if (Files.exists(scriptFile) && !current.contains("class CustomShopNpcs")) {
        JOptionPane.showMessageDialog(
            null,
            "Shop Manager found $CUSTOM_SHOP_RUNTIME_SCRIPT, but it does not look like the expected " +
                "custom shop runtime script.\n\nNo files were changed.",
            "Shop Manager",
            JOptionPane.ERROR_MESSAGE,
        )
        return false
    }

    val install =
        JOptionPane.showConfirmDialog(
            null,
            "Shop Manager needs a one-time server runtime script so generated shops open with their " +
                "custom stock instead of an empty client inventory.\n\n" +
                "A backup will be created if an existing CustomShopNpcs.kt is updated.",
            "Install Shop Manager runtime support?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
    if (install != JOptionPane.YES_OPTION) {
        return false
    }

    if (Files.exists(scriptFile)) {
        val backup = scriptFile.resolveSibling("${scriptFile.fileName}.shop-maker.bak")
        if (!Files.exists(backup)) {
            Files.copy(scriptFile, backup)
        }
    }
    Files.createDirectories(scriptFile.parent)
    Files.writeString(scriptFile, desired)
    ensureGradleDependency(buildFile, SHOPS_API_DEPENDENCY)
    JOptionPane.showMessageDialog(
        null,
        "Custom shop runtime support installed.\n\nRestart the server before testing generated shops.",
        "Shop Manager",
        JOptionPane.INFORMATION_MESSAGE,
    )
    return true
}

private fun loadBundledCustomShopRuntimeScript(): String {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(CUSTOM_SHOP_RUNTIME_RESOURCE)
        ?: error("Missing resource: $CUSTOM_SHOP_RUNTIME_RESOURCE")
    return stream.bufferedReader().use { it.readText() }.trimEnd() + "\n"
}

private fun hasInventoryCodecSupport(source: String): Boolean {
    val hasCustomData = "val customData = custom?.get(id)" in source
    val allowsCustomOnlyInventory = Regex(
        """if\s*\(\s*inventoryType\s*==\s*null\s*&&\s*customData\s*==\s*null\s*\)\s*\{\s*return\s*}""",
        RegexOption.DOT_MATCHES_ALL,
    ).containsMatchIn(source)
    val copiesCustomSize = Regex(
        """if\s*\(\s*inventoryType\s*==\s*null\s*\)\s*\{\s*size\s*=\s*customData\.size\s*}""",
        RegexOption.DOT_MATCHES_ALL,
    ).containsMatchIn(source)
    return hasCustomData && allowsCustomOnlyInventory && copiesCustomSize
}

private fun ensureGradleDependency(file: Path, dependency: String) {
    val text = Files.readString(file)
    if (dependency in text) {
        return
    }
    val newline = if ("\r\n" in text) "\r\n" else "\n"
    val normalized = text.replace("\r\n", "\n")
    val dependencies = Regex("""dependencies\s*\{\n""").find(normalized)
    val next =
        if (dependencies != null) {
            val insertAt = dependencies.range.last + 1
            normalized.substring(0, insertAt) + "    $dependency\n" + normalized.substring(insertAt)
        } else {
            normalized.trimEnd() + "\n\ndependencies {\n    $dependency\n}\n"
        }
    Files.writeString(file, next.replace("\n", newline))
}

private val managedBlockPattern =
    Regex("""# BEGIN SHOP MAKER: ([^\r\n]+)\R(.*?)# END SHOP MAKER: \1\R?""", RegexOption.DOT_MATCHES_ALL)

private const val INVENTORY_SERVER_CODEC =
    "or-cache/src/main/kotlin/dev/openrune/codec/osrs/impl/InventoryServerCodec.kt"
private const val CUSTOM_SHOP_RUNTIME_SCRIPT =
    "content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/CustomShopNpcs.kt"
private const val CUSTOM_SHOP_RUNTIME_RESOURCE = "shop-maker/CustomShopNpcs.kt"
private const val CUSTOM_SHOP_RUNTIME_MARKER = "Shop Maker runtime support v2"
private const val GENERIC_NPCS_BUILD_FILE = "content/generic/generic-npcs/build.gradle.kts"
private const val SHOPS_API_DEPENDENCY = "implementation(projects.api.shops)"
private const val CUSTOM_SHOPS_TOML = ".data/raw-cache/server/shops/custom_shops.toml"
private const val CUSTOM_SHOP_NPCS_TOML = ".data/raw-cache/server/custom_shop_npcs.toml"
private const val CUSTOM_SHOP_SPAWNS_TOML = ".data/raw-cache/map/npcs/custom_shops.toml"
private const val RAW_SHOPS_DIR = ".data/raw-cache/server/shops"
private const val NPC_GAMEVALS = ".data/gamevals/npc.rscm"
private const val INV_GAMEVALS = ".data/gamevals/inv.rscm"
private const val PARAM_GAMEVALS = ".data/gamevals/param.rscm"
private const val MAP_NPC_SPAWNS_DIR = ".data/raw-cache/map/npcs"
private const val SHOP_INVENTORY_PARAM_ID = 65525
private const val SHOP_NAME_PARAM_ID = 65524
private const val SHOP_SELL_PERCENTAGE_PARAM_ID = 65497
private const val SHOP_BUY_PERCENTAGE_PARAM_ID = 65498
private const val SHOP_CHANGE_PERCENTAGE_PARAM_ID = 65499
private val NPC_CUSTOM_ID_RANGE = 16294..16383
private val INV_CUSTOM_ID_RANGE = 1027..4095
private val GENERIC_SHOP_MATCH_KEYS =
    setOf(
        "shop",
        "shopkeeper",
        "shop" + "assist" + "ant",
        "assist" + "ant",
        "generalshopkeeper",
        "general" + "assist" + "ant",
        "trader",
        "merchant",
    )

private val GENERATED_SHOP_TOML_FILES =
    listOf(
        CUSTOM_SHOPS_TOML,
        CUSTOM_SHOP_NPCS_TOML,
        CUSTOM_SHOP_SPAWNS_TOML,
    )

private val INVENTORY_CODEC_PATCH_MARKER =
    """
        if (inventoryType == null) {
            size = customData.size
        }
    """.trimIndent()

private val INVENTORY_CODEC_OLD_BLOCK =
    """
        override fun InventoryServerType.createData() {
            if (types == null) return
            val inventoryType = types[id] ?: return
            size = inventoryType.size
            val customData = custom?.get(id)

            if (customData != null) {
                scope = customData.scope
                stack = customData.stack
                flags = customData.flags
                stock = customData.stock
            }
        }
    """.trimIndent().prependIndent("    ")

private val INVENTORY_CODEC_NEW_BLOCK =
    """
        override fun InventoryServerType.createData() {
            if (types == null) return
            val customData = custom?.get(id)
            val inventoryType = types[id]

            if (inventoryType == null && customData == null) {
                return
            }

            if (inventoryType != null) {
                size = inventoryType.size
            }

            if (customData != null) {
                if (inventoryType == null) {
                    size = customData.size
                }
                scope = customData.scope
                stack = customData.stack
                flags = customData.flags
                stock = customData.stock
            }
        }
    """.trimIndent().prependIndent("    ")

private val INVENTORY_CODEC_BROAD_PATCH_BLOCK =
    """
        override fun InventoryServerType.createData() {
            if (types == null) return
            val customData = custom?.get(id)
            val inventoryType = types[id]

            if (inventoryType == null && customData == null) {
                return
            }

            if (inventoryType != null) {
                size = inventoryType.size
            }

            if (customData != null) {
                size = customData.size
                scope = customData.scope
                stack = customData.stack
                flags = customData.flags
                stock = customData.stock
            }
        }
    """.trimIndent().prependIndent("    ")

private fun String.normalizedQuery(): String = trim().lowercase()

private fun String.toSlug(): String {
    val slug =
        trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    return when {
        slug.isBlank() -> ""
        slug.first().isDigit() -> "shop_$slug"
        else -> slug
    }
}

private fun String.tomlString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun JPanel.addRow(row: Int, label: String, component: java.awt.Component) {
    val labelConstraints = GridBagConstraints().apply {
        gridx = 0
        gridy = row
        anchor = GridBagConstraints.WEST
        insets = Insets(3, 3, 3, 8)
    }
    add(JLabel(label), labelConstraints)

    val fieldConstraints = GridBagConstraints().apply {
        gridx = 1
        gridy = row
        weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(3, 3, 3, 3)
    }
    add(component, fieldConstraints)
}

private fun JTextField.withLabel(label: String): JPanel {
    val panel = JPanel(BorderLayout(4, 4))
    panel.add(JLabel(label), BorderLayout.NORTH)
    panel.add(this, BorderLayout.CENTER)
    return panel
}

private fun JLabel.withPadding(): JPanel {
    val panel = JPanel(BorderLayout())
    panel.border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
    panel.add(this, BorderLayout.CENTER)
    return panel
}

private fun findStockTable(component: java.awt.Component): JTable? {
    if (component is JTable) {
        return component
    }
    if (component is java.awt.Container) {
        for (child in component.components) {
            val found = findStockTable(child)
            if (found != null) {
                return found
            }
        }
    }
    return null
}
