package org.rsmod.tools.shopmaker

import dev.openrune.ServerCacheManager
import dev.openrune.gamevals.GameValProvider
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.InvScope
import dev.openrune.types.InventoryServerType
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
import javax.swing.JButton
import javax.swing.JComboBox
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

fun main() {
    SwingUtilities.invokeLater {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        val repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        if (ensureShopMakerCompatibility(repoRoot)) {
            ShopMakerFrame(repoRoot).isVisible = true
        }
    }
}

class ShopMakerFrame(repoRoot: Path) : JFrame("OpenRune Shop Manager") {
    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val panel = ShopMakerPanel(repoRoot)
        minimumSize = panel.minimumSize
        preferredSize = panel.preferredSize
        contentPane.add(panel, BorderLayout.CENTER)
        pack()
        setLocationRelativeTo(null)
    }
}

fun createShopMakerPanel(repoRoot: Path): JPanel =
    if (ensureShopMakerCompatibility(repoRoot)) {
        ShopMakerPanel(repoRoot)
    } else {
        JPanel(BorderLayout()).apply {
            add(JLabel("Shop Manager support was not installed."), BorderLayout.CENTER)
        }
    }

class ShopMakerPanel(private val repoRoot: Path) : JPanel(BorderLayout(8, 8)) {
    private val shopName = JTextField("New Shop")
    private val shopSlug = JTextField()
    private val shopVariant = JComboBox<ShopInventoryInfo>()
    private val shopCurrency = JComboBox<CurrencyOption>()
    private val buyMultiplier = JSpinner(SpinnerNumberModel(600, 0, PRICE_MULTIPLIER_MAX, 10))
    private val sellMultiplier = JSpinner(SpinnerNumberModel(1000, 0, PRICE_MULTIPLIER_MAX, 10))
    private val changeDelta = JSpinner(SpinnerNumberModel(20, 0, 10_000, 1))
    private val loadSelectedShopButton = JButton("Load selected shop")
    private val saveShopButton = JButton("Save shop")
    private val npcHint = JLabel()

    private val npcSearch = JTextField()
    private val npcRegion = JComboBox<String>()
    private val itemSearch = JTextField()
    private val npcList = JList<LookupEntry>()
    private val itemList = JList<LookupEntry>()
    private val stockModel = StockTableModel()
    private val preview = JTextArea()
    private val status = JLabel("Loading cache...")

    private var lookup =
        LookupData(emptyList(), emptyList(), emptyList(), emptyList(), listOf(CurrencyOption.StandardGp))
    private var updatingShopVariant = false
    private var activeNpcInternal: String? = null
    private var activeShopVariantInternal: String? = null

    init {
        minimumSize = Dimension(1180, 760)
        preferredSize = Dimension(1280, 820)

        npcList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        itemList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        npcList.cellRenderer = LookupRenderer()
        itemList.cellRenderer = LookupRenderer()
        preview.isEditable = false
        preview.lineWrap = false

        add(buildMainPanel(), BorderLayout.CENTER)
        add(status.withPadding(), BorderLayout.SOUTH)

        shopName.document.addDocumentListener(SimpleDocumentListener { updateDerivedSlug() })
        npcSearch.document.addDocumentListener(SimpleDocumentListener { refreshNpcList() })
        npcRegion.addActionListener { refreshNpcList() }
        itemSearch.document.addDocumentListener(SimpleDocumentListener { refreshItemList() })
        loadSelectedShopButton.addActionListener { loadSelectedNpcShop() }
        shopVariant.addActionListener {
            if (!updatingShopVariant) {
                loadSelectedShopVariant()
            }
        }
        shopVariant.isEnabled = false
        shopCurrency.toolTipText = "Select the native OpenRune currency this shop should use."

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
        val filters = JPanel(GridLayout(2, 1, 0, 4))
        filters.add(npcSearch.withLabel("Search NPC"))
        filters.add(npcRegion.withLabel("Region"))
        panel.add(filters, BorderLayout.NORTH)
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
        panel.border = BorderFactory.createTitledBorder("Native Shop")

        val north = JPanel(BorderLayout(8, 8))
        val fields = JPanel(GridBagLayout())
        var row = 0
        fields.addRow(row++, "Shop title", shopName)
        fields.addRow(row++, "Shop slug", shopSlug)
        fields.addRow(row++, "Shop inventory", shopVariant)
        fields.addRow(row++, "Currency", shopCurrency)
        fields.addRow(row++, "Buy multiplier", buyMultiplier)
        fields.addRow(row++, "Sell multiplier", sellMultiplier)
        fields.addRow(row++, "Price delta", changeDelta)
        north.add(fields, BorderLayout.CENTER)

        val removeStock = JButton("Remove stock row")
        removeStock.addActionListener { removeSelectedStockRow() }
        val setPrice = JButton("Set price")
        setPrice.addActionListener { setSelectedStockPrice() }
        val makePreview = JButton("Preview")
        makePreview.addActionListener { previewShop() }
        saveShopButton.addActionListener { placeShop() }

        val actions = JPanel(GridLayout(2, 1, 0, 4))
        val loadActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        loadActions.add(loadSelectedShopButton)

        val editActions = JPanel(GridLayout(1, 4, 8, 0))
        editActions.add(saveShopButton)
        editActions.add(setPrice)
        editActions.add(makePreview)
        editActions.add(removeStock)
        actions.add(loadActions)
        actions.add(editActions)

        val stockTable = JTable(stockModel)
        stockTable.fillsViewportHeight = true

        val stockScroll = JScrollPane(stockTable)
        stockScroll.minimumSize = Dimension(320, 180)
        val previewScroll = JScrollPane(preview)
        previewScroll.minimumSize = Dimension(320, 90)
        val center = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            stockScroll,
            previewScroll,
        )
        center.resizeWeight = 0.78

        panel.add(north, BorderLayout.NORTH)
        panel.add(center, BorderLayout.CENTER)
        panel.add(actions, BorderLayout.SOUTH)
        return panel
    }

    private fun refreshModeUi() {
        saveShopButton.text = "Save / Fix shop"
        npcHint.text = "Shows in-world Trade NPCs with real cache shop inventories. Empty native shops can be filled."
    }

    private fun loadCache() {
        object : SwingWorker<LookupData, String>() {
            override fun doInBackground(): LookupData {
                publish("Loading cache definitions...")
                loadServerCache(repoRoot)
                publish("Indexing NPCs and items...")
                val spawnedNpcRegions = loadSpawnedNpcRegions(repoRoot)
                val shopParams = ShopParamIds.load(repoRoot)
                val rawShopIndex = loadRawShopIndex(repoRoot)
                val scriptedNpcIndex = loadScriptedNpcIndex(repoRoot, rawShopIndex)
                val items = ServerCacheManager.getItems().values.mapNotNull { it.toLookupEntry() }
                    .sortedWith(compareBy<LookupEntry> { it.name }.thenBy { it.id })
                val currencies = loadCurrencyOptions(repoRoot, items)
                val regions = spawnedNpcRegions.values.flatten().distinct().sorted()
                val npcs =
                    ServerCacheManager.getNpcs().values
                        .mapNotNull {
                            it.toLookupEntry(spawnedNpcRegions, shopParams, rawShopIndex, scriptedNpcIndex)
                        }
                val shopUsage = npcs.shopUsageByInventory()
                val shopInventories =
                    rawShopIndex.all()
                        .map { it.withUsage(shopUsage) }
                        .sortedWith(
                            compareBy<ShopInventoryInfo> { it.usedBy.isNotEmpty() }
                                .thenBy { it.title }
                                .thenBy { it.internal },
                        )
                return LookupData(
                    npcs = npcs
                        .map { it.withShopUsage(shopUsage) }
                        .sortedWith(
                            compareBy<LookupEntry> { !it.isEditableShopNpc }
                                .thenBy { !it.hasShopInventory }
                                .thenBy { !it.canOpenShop }
                                .thenBy { it.name }
                                .thenBy { it.id },
                        ),
                    items = items,
                    shopInventories = shopInventories,
                    regions = regions,
                    currencies = currencies,
                )
            }

            override fun process(chunks: MutableList<String>) {
                status.text = chunks.lastOrNull() ?: status.text
            }

            override fun done() {
                try {
                    lookup = get()
                    shopCurrency.removeAllItems()
                    lookup.currencies.forEach(shopCurrency::addItem)
                    npcRegion.removeAllItems()
                    npcRegion.addItem(ALL_REGIONS)
                    lookup.regions.forEach(npcRegion::addItem)
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
        val selectedRegion = npcRegion.selectedItem as? String ?: ALL_REGIONS
        val selectedInternal = npcList.selectedValue?.internal ?: activeNpcInternal
        val filtered =
            lookup.npcs.asSequence()
                .filter { selectedRegion == ALL_REGIONS || selectedRegion in it.regions }
                .filter { it.isEditableShopNpc }
                .filter { it.matches(query) }
                .take(MAX_SEARCH_RESULTS)
                .toMutableList()
        val selected =
            selectedInternal
                ?.let { internal -> lookup.npcs.firstOrNull { it.internal == internal && it.isEditableShopNpc } }
        if (selected != null && filtered.none { it.internal == selected.internal }) {
            filtered.add(0, selected)
        }
        npcList.setListData(filtered.toTypedArray())
        val nextSelection = selectedInternal?.let { internal -> filtered.firstOrNull { it.internal == internal } }
        if (nextSelection != null) {
            npcList.setSelectedValue(nextSelection, true)
        } else if (npcList.selectedValue !in filtered) {
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

    private fun setSelectedStockPrice() {
        val table = findStockTable(this) ?: return
        val viewRow = table.selectedRow
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, "Pick a stock row first.")
            return
        }
        val row = table.convertRowIndexToModel(viewRow)
        val stock = stockModel.rows.getOrNull(row) ?: return
        val baseCost = stock.item.cost
        if (baseCost <= 0) {
            JOptionPane.showMessageDialog(
                this,
                "${stock.item.name} has no cache value, so a multiplier price cannot be calculated.",
            )
            return
        }
        val currentMultiplier = sellMultiplier.value as Int
        val currentPrice = initialShopPrice(baseCost, currentMultiplier)
        val currency = shopCurrency.selectedItem as? CurrencyOption ?: CurrencyOption.StandardGp
        val input =
            JOptionPane.showInputDialog(
                this,
                "Target price for ${stock.item.name} in ${currency.label}:",
                currentPrice.toString(),
            ) ?: return
        val targetPrice = input.replace(",", "").trim().toIntOrNull()
        if (targetPrice == null || targetPrice < 0) {
            JOptionPane.showMessageDialog(this, "Price must be a whole number.")
            return
        }
        val multiplier = multiplierForTargetPrice(baseCost, targetPrice)
        sellMultiplier.value = multiplier
        val actualPrice = initialShopPrice(baseCost, multiplier)
        status.text =
            if (actualPrice == targetPrice) {
                "Set ${stock.item.name} to cost $actualPrice ${currency.label} at normal stock."
            } else {
                "Closest native multiplier makes ${stock.item.name} cost $actualPrice ${currency.label} at normal stock."
            }
    }

    private fun loadSelectedNpcShop() {
        try {
            val selectedNpc = selectedNpcOrActive() ?: error("Pick a shop NPC first.")
            activeNpcInternal = selectedNpc.internal
            val variant = populateShopVariants(selectedNpc, activeShopVariantInternal ?: selectedNpc.shopInventoryInternal)
            if (variant != null) {
                loadShopVariant(selectedNpc, variant)
                return
            }

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
                    val inventoryId = selectedNpc.shopInventoryId
                        ?: error(
                            "${selectedNpc.name} does not have a real cache shop inventory. " +
                                "Pick an NPC with a matched inv.* shop before saving stock.",
                        )
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
            val slug = selectedNpc.shopInventoryInternal
                ?.removePrefix("inv.")
                ?.takeIf { it.isNotBlank() }
                ?: (selectedNpc.shopTitle ?: selectedNpc.name).toSlug()
            applyLoadedShop(
                LoadedShop(
                    label = selectedNpc.name,
                    npcInternal = selectedNpc.internal,
                    variantInternal = selectedNpc.shopInventoryInternal,
                    currencyInternal = selectedNpc.shopCurrencyInternal,
                    title = selectedNpc.shopTitle ?: "${selectedNpc.name} Shop",
                    slug = slug,
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

    private fun loadSelectedShopVariant() {
        try {
            val selectedNpc = selectedNpcOrActive() ?: return
            val variant = shopVariant.selectedItem as? ShopInventoryInfo ?: return
            loadShopVariant(selectedNpc, variant)
        } catch (e: Exception) {
            showError("Could not load the selected shop variant.", e)
        }
    }

    private fun loadShopVariant(selectedNpc: LookupEntry, variant: ShopInventoryInfo) {
        activeNpcInternal = selectedNpc.internal
        activeShopVariantInternal = variant.internal
        val isNpcPrimaryShop = variant.internal == selectedNpc.shopInventoryInternal
        val stock =
            variant.stock.map { stock ->
                StockEntry(
                    item = lookup.itemByInternal(stock.itemInternal),
                    count = stock.count,
                    restockCycles = stock.restockCycles,
                )
            }
        applyLoadedShop(
            LoadedShop(
                label = selectedNpc.name,
                npcInternal = selectedNpc.internal,
                variantInternal = variant.internal,
                currencyInternal = selectedNpc.shopCurrencyInternal,
                title = if (isNpcPrimaryShop) selectedNpc.shopTitle ?: variant.title else variant.title,
                slug = variant.internal.removePrefix("inv.").takeIf { it.isNotBlank() }
                    ?: variant.title.toSlug(),
                buyMultiplier = if (isNpcPrimaryShop) selectedNpc.shopBuyMultiplier ?: variant.buyMultiplier else variant.buyMultiplier,
                sellMultiplier = if (isNpcPrimaryShop) selectedNpc.shopSellMultiplier ?: variant.sellMultiplier else variant.sellMultiplier,
                changeDelta = if (isNpcPrimaryShop) selectedNpc.shopChangeDelta ?: variant.changeDelta else variant.changeDelta,
                stock = stock,
            ),
        )
    }

    private fun populateShopVariants(selectedNpc: LookupEntry, selectedInternal: String? = null): ShopInventoryInfo? {
        updatingShopVariant = true
        return try {
            val choices = inventoryChoicesFor(selectedNpc)
            shopVariant.removeAllItems()
            choices.forEach(shopVariant::addItem)
            val selected =
                choices.firstOrNull { it.internal == selectedInternal }
                    ?: choices.firstOrNull { it.internal == selectedNpc.shopInventoryInternal }
                    ?: choices.firstOrNull()
            if (selected != null) {
                shopVariant.selectedItem = selected
            }
            shopVariant.isEnabled = choices.size > 1
            selected
        } finally {
            updatingShopVariant = false
        }
    }

    private fun inventoryChoicesFor(selectedNpc: LookupEntry): List<ShopInventoryInfo> =
        selectedNpc.shopVariants.takeIf { it.isNotEmpty() } ?: lookup.shopInventories

    private fun applyLoadedShop(shop: LoadedShop) {
        activeNpcInternal = shop.npcInternal
        activeShopVariantInternal = shop.variantInternal
        shopSlug.text = shop.slug
        shopName.text = shop.title
        shopCurrency.selectedItem = lookup.currencyByInternal(shop.currencyInternal)
        buyMultiplier.value = shop.buyMultiplier
        sellMultiplier.value = shop.sellMultiplier
        changeDelta.value = shop.changeDelta
        stockModel.replaceAll(shop.stock)
        val selectedNpc = lookup.npcs.firstOrNull { it.internal == shop.npcInternal }
        if (selectedNpc != null) {
            npcSearch.text = ""
            npcRegion.selectedItem = ALL_REGIONS
            refreshNpcList()
            npcList.setSelectedValue(selectedNpc, true)
            populateShopVariants(selectedNpc, shop.variantInternal)
        }
        preview.text = ""
        val variantText = shop.variantInternal?.let { " ($it)" } ?: ""
        val otherUsers =
            shop.variantInternal
                ?.let { internal -> lookup.shopInventories.firstOrNull { it.internal == internal } }
                ?.usedBy
                .orEmpty()
                .filterNot { it.npcInternal == shop.npcInternal }
        val sharedText =
            if (otherUsers.isEmpty()) {
                ""
            } else {
                " Shared with ${otherUsers.take(2).joinToString { it.npcName }}${if (otherUsers.size > 2) " +${otherUsers.size - 2}" else ""}."
            }
        status.text = "Loaded ${shop.title}$variantText. Edit it, then save the changes.$sharedText"
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
            if (!confirmSharedInventoryWrite(spec)) {
                status.text = "Save cancelled."
                return
            }
            val generated = buildGeneratedFiles(spec)
            generated.write(repoRoot)
            applySavedShopToLookup(spec)
            preview.text = generated.preview()
            status.text = "Saved ${spec.shopTitle}. Rebuild cache and restart server before testing."
            JOptionPane.showMessageDialog(
                this,
                "Shop saved/fixed. Rebuild cache with :or-cache:buildCache, then restart the server.",
            )
        } catch (e: Exception) {
            showError("Could not place shop.", e)
        }
    }

    private fun confirmSharedInventoryWrite(spec: ShopSpec): Boolean {
        val otherUsers =
            lookup.shopInventories
                .firstOrNull { it.internal == spec.rawShopInternal }
                ?.usedBy
                .orEmpty()
                .filterNot { it.npcInternal == spec.npcInternal }
        if (otherUsers.isEmpty()) {
            return true
        }
        val previewUsers = otherUsers.take(5).joinToString(separator = "\n") { "- ${it.label}" }
        val more = if (otherUsers.size > 5) "\n- and ${otherUsers.size - 5} more" else ""
        val result =
            JOptionPane.showConfirmDialog(
                this,
                "This shop inventory is already used by:\n\n$previewUsers$more\n\n" +
                    "Saving will change stock for every NPC using ${spec.rawShopInternal}. Continue?",
                "Shared shop inventory",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )
        return result == JOptionPane.YES_OPTION
    }

    private fun readSpec(): ShopSpec {
        val selectedNpc = selectedNpcOrActive()
            ?: error("Pick a shop NPC first.")
        require(selectedNpc.canOpenShop) { "Pick an NPC that can open a shop." }
        require(selectedNpc.isEditableShopNpc) {
            "Only in-world Trade NPCs can be edited."
        }
        val inventoryChoices = inventoryChoicesFor(selectedNpc)
        val selectedVariant =
            (shopVariant.selectedItem as? ShopInventoryInfo)
                ?.takeIf { variant -> inventoryChoices.any { it.internal == variant.internal } }
        val selectedCurrency = shopCurrency.selectedItem as? CurrencyOption ?: CurrencyOption.StandardGp
        val slug = shopSlug.text.toSlug()
        require(slug.isNotBlank()) { "Shop slug cannot be empty." }
        val rawShopInternal =
            selectedVariant?.internal
                ?: selectedNpc.shopInventoryInternal
                ?: selectedNpc.scriptedShopInternal
                ?: error(
                    "${selectedNpc.name} does not have a real cache shop inventory. " +
                        "Choose an existing real inv.* shop from the Shop variant dropdown.",
                )
        require(stockModel.rows.isNotEmpty()) { "Add at least one stock item." }
        require(stockModel.rows.size <= MAX_RENDER_STOCK_ROWS) {
            "Generated shops currently support up to $MAX_RENDER_STOCK_ROWS stock rows. " +
                "Split larger shops or add a larger render inventory first."
        }
        val scriptMarker = "native_shop_${selectedNpc.internal.removePrefix("npc.").toSlug()}_" +
            rawShopInternal.removePrefix("inv.").toSlug()
        val generatedScriptExists = Files.exists(repoRoot.resolve(nativeShopScriptPath(scriptMarker)))
        val writesNativeScript =
            generatedScriptExists ||
                (selectedNpc.scriptedShopInternal == null && !selectedNpc.hasNativeTradeScript)
        val selectedInventoryAlreadyHandled =
            selectedNpc.shopVariants.any { it.internal == rawShopInternal } ||
                selectedNpc.shopInventoryInternal == rawShopInternal ||
                selectedNpc.scriptedShopInternal == rawShopInternal
        require(writesNativeScript || !selectedNpc.hasNativeTradeScript || selectedInventoryAlreadyHandled) {
            "${selectedNpc.name} already has a hand-written Trade script, and the tool could not detect a shop " +
                "inventory in that script. Add this inventory to that script manually, or remove the old handler first."
        }
        val existingScriptAlreadyUsesCurrency =
            selectedNpc.hasNativeTradeScript && selectedNpc.shopCurrencyInternal == selectedCurrency.internal
        require(writesNativeScript || !selectedNpc.hasNativeTradeScript || existingScriptAlreadyUsesCurrency) {
            "${selectedNpc.name} already uses a hand-written native Trade script. " +
                "The tool will not create a duplicate click handler. Add " +
                "currency = ${selectedCurrency.internal.kotlinString()} to that script's shops.open call, " +
                "or choose the currency already used by that script."
        }

        return ShopSpec(
            shopTitle = shopName.text.trim().ifBlank { "Shop" },
            slug = slug,
            inheritedNpc = selectedNpc,
            rawShopInternal = rawShopInternal,
            currency = selectedCurrency,
            writeNativeScript = writesNativeScript,
            serverOnlyInventory = true,
            buyMultiplier = buyMultiplier.value as Int,
            sellMultiplier = sellMultiplier.value as Int,
            changeDelta = changeDelta.value as Int,
            stock = stockModel.rows.toList(),
        )
    }

    private fun selectedNpcOrActive(): LookupEntry? =
        npcList.selectedValue
            ?: activeNpcInternal?.let { internal -> lookup.npcs.firstOrNull { it.internal == internal } }

    private fun applySavedShopToLookup(spec: ShopSpec) {
        val savedStock =
            spec.stock.map { stock ->
                RawStockEntry(
                    itemInternal = stock.item.internal,
                    count = stock.count,
                    restockCycles = stock.restockCycles,
                )
            }
        val selectedUsage = ShopUsage(npcName = spec.inheritedNpc.name, npcInternal = spec.npcInternal)
        val savedUsage =
            (
                lookup.shopInventories
                    .firstOrNull { it.internal == spec.rawShopInternal }
                    ?.usedBy
                    .orEmpty()
                    .filterNot { it.npcInternal == selectedUsage.npcInternal } + selectedUsage
            ).sortedBy { it.npcName }
        val savedShop =
            ShopInventoryInfo(
                internal = spec.rawShopInternal,
                title = spec.shopTitle,
                buyMultiplier = spec.buyMultiplier,
                sellMultiplier = spec.sellMultiplier,
                changeDelta = spec.changeDelta,
                stock = savedStock,
                usedBy = savedUsage,
            )
        val nextShopInventories =
            (lookup.shopInventories.filterNot { it.internal == savedShop.internal } + savedShop)
                .sortedWith(
                    compareBy<ShopInventoryInfo> { it.usedBy.isNotEmpty() }
                        .thenBy { it.title }
                        .thenBy { it.internal },
                )
        lookup =
            lookup.copy(
                shopInventories = nextShopInventories,
                npcs =
                    lookup.npcs.map { npc ->
                        if (npc.internal == spec.npcInternal) {
                            val variants =
                                (npc.shopVariants.filterNot { it.internal == savedShop.internal } + savedShop)
                                    .distinctBy { it.internal }
                            npc.copy(
                                hasShopInventory = true,
                                shopInventoryInternal = spec.rawShopInternal,
                                shopTitle = spec.shopTitle,
                                shopBuyMultiplier = spec.buyMultiplier,
                                shopSellMultiplier = spec.sellMultiplier,
                                shopChangeDelta = spec.changeDelta,
                                shopStock = savedStock,
                                shopVariants = variants,
                                shopCurrencyInternal = spec.currency.internal,
                                scriptedShopInternal = npc.scriptedShopInternal ?: spec.rawShopInternal,
                                hasNativeTradeScript = npc.hasNativeTradeScript || spec.writeNativeScript,
                            )
                        } else {
                            val variants =
                                npc.shopVariants
                                    .map { if (it.internal == savedShop.internal) savedShop else it }
                                    .distinctBy { it.internal }
                            if (variants == npc.shopVariants) npc else npc.copy(shopVariants = variants)
                        }
                    },
            )
        activeNpcInternal = spec.npcInternal
        activeShopVariantInternal = spec.rawShopInternal
        refreshNpcList()
        selectedNpcOrActive()?.let { selectedNpc ->
            npcList.setSelectedValue(selectedNpc, true)
            populateShopVariants(selectedNpc, spec.rawShopInternal)
        }
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
        private const val MAX_RENDER_STOCK_ROWS = 28
    }
}

private data class LookupData(
    val npcs: List<LookupEntry>,
    val items: List<LookupEntry>,
    val shopInventories: List<ShopInventoryInfo>,
    val regions: List<String>,
    val currencies: List<CurrencyOption>,
) {
    private val itemsById: Map<Int, LookupEntry> = items.associateBy { it.id }
    private val itemsByInternal: Map<String, LookupEntry> = items.associateBy { it.internal }
    private val currenciesByInternal: Map<String, CurrencyOption> = currencies.associateBy { it.internal }

    fun itemById(id: Int): LookupEntry =
        itemsById[id] ?: LookupEntry(
            id = id,
            internal = safeReverseMapping(RSCMType.OBJ, id),
            name = safeReverseMapping(RSCMType.OBJ, id).removePrefix("obj."),
            actions = "",
            hasTrade = false,
            regions = emptySet(),
        )

    fun itemByInternal(internal: String): LookupEntry =
        itemsByInternal[internal] ?: LookupEntry(
            id = -1,
            internal = internal,
            name = internal.removePrefix("obj."),
            actions = "",
            hasTrade = false,
            regions = emptySet(),
        )

    fun currencyByInternal(internal: String?): CurrencyOption =
        internal?.let(currenciesByInternal::get) ?: CurrencyOption.StandardGp
}

private data class CurrencyOption(
    val internal: String,
    val label: String,
    val objInternal: String? = null,
    val varbitInternal: String? = null,
    val singularName: String = label,
    val pluralName: String = label,
    val registered: Boolean = true,
) {
    val isStandard: Boolean get() = internal == StandardGp.internal
    val key: String get() = internal.removePrefix("currency.")
    val dbrowKey: String get() = "shop_currency_$key"
    val dbrowInternal: String get() = "dbrow.$dbrowKey"
    val needsRegistration: Boolean get() = !registered

    init {
        require(objInternal != null || varbitInternal != null) {
            "Currency $internal must define an item or varbit backing."
        }
    }

    override fun toString(): String =
        if (needsRegistration) "$label (will register)" else label

    companion object {
        val StandardGp =
            CurrencyOption(
                internal = "currency.standard_gp",
                label = "Coins (standard GP)",
                objInternal = "obj.coins",
                singularName = "coin",
                pluralName = "coins",
            )
    }
}

private data class KnownCurrencySeed(
    val key: String,
    val label: String,
    val singularName: String,
    val pluralName: String,
    val objCandidates: List<String>,
) {
    val internal: String get() = "currency.$key"
}

private data class LookupEntry(
    val id: Int,
    val internal: String,
    val name: String,
    val actions: String,
    val hasTrade: Boolean,
    val regions: Set<String>,
    val hasShopInventory: Boolean = false,
    val isSpawned: Boolean = false,
    val shopInventoryId: Int? = null,
    val shopInventoryInternal: String? = null,
    val shopTitle: String? = null,
    val shopBuyMultiplier: Int? = null,
    val shopSellMultiplier: Int? = null,
    val shopChangeDelta: Int? = null,
    val shopStock: List<RawStockEntry> = emptyList(),
    val shopVariants: List<ShopInventoryInfo> = emptyList(),
    val shopCurrencyInternal: String = CurrencyOption.StandardGp.internal,
    val scriptedShopInternal: String? = null,
    val hasNativeTradeScript: Boolean = false,
    val cost: Int = 0,
) {
    val canOpenShop: Boolean
        get() = hasTrade || scriptedShopInternal != null || shopVariants.isNotEmpty()

    val isExistingShopNpc: Boolean
        get() = canOpenShop && hasShopInventory && isSpawned

    val isEditableShopNpc: Boolean
        get() = isSpawned && canOpenShop

    val tradeOpSlot: Int?
        get() =
            actions.split(',')
                .asSequence()
                .map { it.trim() }
                .mapNotNull { action ->
                    val slot = action.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                    val option = action.substringAfter(':', "")
                    if (option.equals("trade", ignoreCase = true)) slot else null
                }
                .firstOrNull()

    private val regionLabel: String
        get() =
            when {
                regions.isEmpty() -> ""
                regions.size == 1 -> regions.single()
                regions.size == 2 -> regions.sorted().joinToString(", ")
                else -> regions.sorted().take(2).joinToString(", ") + " +${regions.size - 2}"
            }

    val label: String
        get() {
            val actionText = if (actions.isBlank()) "" else " | $actions"
            val scriptText = if (!hasTrade && scriptedShopInternal != null) " | scripted shop" else ""
            val tradeScriptText = if (hasTrade && hasNativeTradeScript) " | native Trade script" else ""
            val variantText = if (shopVariants.size > 1) " | ${shopVariants.size} shop variants" else ""
            val missingShopText = if (!hasShopInventory) " | choose inv.*" else ""
            val regionText = if (regionLabel.isBlank()) "" else " | $regionLabel"
            val costText = if (cost > 0) " | ${cost}gp" else ""
            return "$name [$id] $internal$regionText$actionText$scriptText$tradeScriptText$variantText$missingShopText$costText"
        }

    fun matches(query: String): Boolean {
        if (query.isBlank()) {
            return true
        }
        return label.lowercase().contains(query)
    }

    fun defaultNewShopSlug(): String =
        when {
            name.isNotBlank() -> "${name.toSlug()}_shop"
            else -> "${internal.removePrefix("npc.").toSlug()}_shop"
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

private data class ShopUsage(
    val npcName: String,
    val npcInternal: String,
) {
    val label: String get() = "$npcName [$npcInternal]"
}

private data class ShopInventoryInfo(
    val internal: String,
    val title: String,
    val buyMultiplier: Int,
    val sellMultiplier: Int,
    val changeDelta: Int,
    val stock: List<RawStockEntry>,
    val usedBy: List<ShopUsage> = emptyList(),
) {
    private val usedByText: String
        get() =
            when {
                usedBy.isEmpty() -> "unused"
                usedBy.size == 1 -> "used by ${usedBy.single().npcName}"
                else -> "used by ${usedBy.take(2).joinToString { it.npcName }} +${usedBy.size - 2}"
            }

    override fun toString(): String = "$title ($internal) - $usedByText"
}

private data class ScriptedNpcIndex(
    val shopLinks: Map<String, List<ShopInventoryInfo>>,
    val currencies: Map<String, String>,
    val opPatterns: List<NpcOpPattern>,
) {
    fun hasOp(npcInternal: String, opSlot: Int): Boolean =
        opPatterns.any { it.opSlot == opSlot && it.matches(npcInternal) }

    fun shopsFor(npcInternal: String): List<ShopInventoryInfo> =
        shopLinks.asSequence()
            .filter { (npcPattern, _) -> NpcOpPattern(DEFAULT_TRADE_SLOT, npcPattern).matches(npcInternal) }
            .flatMap { (_, shops) -> shops.asSequence() }
            .distinctBy { it.internal }
            .toList()

    fun currencyFor(npcInternal: String): String =
        currencies.asSequence()
            .firstOrNull { (npcPattern, _) -> NpcOpPattern(DEFAULT_TRADE_SLOT, npcPattern).matches(npcInternal) }
            ?.value
            ?: CurrencyOption.StandardGp.internal
}

private data class NpcOpPattern(
    val opSlot: Int,
    val npcPattern: String,
) {
    fun matches(npcInternal: String): Boolean {
        if ('$' !in npcPattern) {
            return npcPattern == npcInternal
        }
        val prefix = npcPattern.substringBefore('$')
        return prefix.isNotBlank() && npcInternal.startsWith(prefix)
    }
}

private data class RawShopIndex(
    val byInternal: Map<String, ShopInventoryInfo>,
) {
    private val shops: List<ShopInventoryInfo> = byInternal.values.toList()

    fun byInternal(internal: String?): ShopInventoryInfo? =
        internal?.let { byInternal[it] }

    fun all(): List<ShopInventoryInfo> = shops

    fun matchNpc(name: String, internal: String, regions: Set<String>): ShopInventoryInfo? {
        val npcKey = internal.removePrefix("npc.")
        val candidates =
            sequenceOf(
                name,
                npcKey.replace('_', ' '),
                npcKey.replace("merchant", "shop").replace('_', ' '),
                npcKey.replace("merchant_", "shop_").replace('_', ' '),
                npcKey.replace("_merchant", "_shop").replace('_', ' '),
            )
                .map(::shopSearchKey)
                .filter { it.length >= 3 && it !in GENERIC_SHOP_MATCH_KEYS }
                .flatMap { key -> sequenceOf(key, "${key}s") }
                .distinct()
                .toList()
        val directMatch = shops.firstOrNull { shop ->
            val titleKey = shopSearchKey(shop.title)
            candidates.any { candidate -> candidate in titleKey }
        }
        if (directMatch != null) {
            return directMatch
        }
        return matchGenericRegionShop(name, internal, regions)
    }

    private fun matchGenericRegionShop(name: String, internal: String, regions: Set<String>): ShopInventoryInfo? {
        val npcKey = shopSearchKey("$name ${internal.removePrefix("npc.")}")
        if (regions.isEmpty() || !GENERIC_SHOP_MATCH_KEYS.any { it in npcKey }) {
            return null
        }
        val regionKeys = regions.map(::shopSearchKey).filter { it.length >= 3 }.sortedByDescending { it.length }
        return regionKeys
            .asSequence()
            .mapNotNull { regionKey ->
                shops
                    .filter { shop ->
                        val titleKey = shopSearchKey(shop.title)
                        regionKey in titleKey && ("generalstore" in titleKey || "shop" in titleKey)
                    }
                    .sortedWith(
                        compareByDescending<ShopInventoryInfo> { "generalstore" in shopSearchKey(it.title) }
                            .thenBy { it.internal },
                    )
                    .firstOrNull()
            }
            .firstOrNull()
    }
}

private fun List<LookupEntry>.shopUsageByInventory(): Map<String, List<ShopUsage>> {
    val usageByInventory = linkedMapOf<String, MutableList<ShopUsage>>()
    for (npc in this) {
        if (!npc.isSpawned) {
            continue
        }
        val inventories = linkedSetOf<String>()
        npc.shopInventoryInternal?.let(inventories::add)
        npc.scriptedShopInternal?.let(inventories::add)
        npc.shopVariants.forEach { inventories += it.internal }
        if (inventories.isEmpty()) {
            continue
        }
        val usage = ShopUsage(npcName = npc.name, npcInternal = npc.internal)
        inventories.forEach { inventory ->
            val usages = usageByInventory.getOrPut(inventory) { mutableListOf() }
            if (usages.none { it.npcInternal == usage.npcInternal }) {
                usages += usage
            }
        }
    }
    return usageByInventory.mapValues { (_, usages) -> usages.sortedBy { it.npcName } }
}

private fun ShopInventoryInfo.withUsage(usageByInventory: Map<String, List<ShopUsage>>): ShopInventoryInfo =
    copy(usedBy = usageByInventory[internal].orEmpty())

private fun LookupEntry.withShopUsage(usageByInventory: Map<String, List<ShopUsage>>): LookupEntry =
    copy(shopVariants = shopVariants.map { it.withUsage(usageByInventory) })

private data class LoadedShop(
    val label: String,
    val npcInternal: String,
    val variantInternal: String?,
    val currencyInternal: String,
    val title: String,
    val slug: String,
    val buyMultiplier: Int,
    val sellMultiplier: Int,
    val changeDelta: Int,
    val stock: List<StockEntry>,
) {
    override fun toString(): String = "$title - $label"
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
    val rawShopInternal: String,
    val currency: CurrencyOption,
    val writeNativeScript: Boolean,
    val serverOnlyInventory: Boolean,
    val buyMultiplier: Int,
    val sellMultiplier: Int,
    val changeDelta: Int,
    val stock: List<StockEntry>,
) {
    val openInvInternal: String get() = rawShopInternal
    val npcInternal: String get() = inheritedNpc.internal
    val scriptMarker: String
        get() = "native_shop_${inheritedNpc.internal.removePrefix("npc.").toSlug()}_" +
            openInvInternal.removePrefix("inv.").toSlug()
}

private data class GeneratedFiles(
    val nativeScriptPath: String?,
    val nativeScript: String?,
    val rawShopInternal: String,
    val rawShopToml: String,
    val currency: CurrencyOption,
) {
    fun preview(): String =
        buildString {
            appendLine("Currency: ${currency.label} (${currency.internal})")
            if (currency.needsRegistration) {
                appendLine("Registers native OpenRune currency row: ${currency.dbrowInternal}")
            }
            appendLine()
            appendLine("----- Native shop inventory -----")
            appendLine("Updates native shop inventory: $rawShopInternal")
            appendLine(rawShopToml)
            appendLine()
            appendLine("----- Native OpenRune shop script -----")
            appendLine(
                if (nativeScript != null) {
                    "Repairs the existing NPC Trade click with a native OpenRune shop script."
                } else {
                    "Uses the existing native OpenRune Trade script."
                },
            )
            if (nativeScriptPath != null) {
                appendLine(nativeScriptPath)
            }
            if (nativeScript != null) {
                appendLine(nativeScript)
            }
        }

    fun write(root: Path) {
        if (currency.needsRegistration) {
            registerCurrency(root, currency)
        }
        writeRawShop(root, rawShopInternal, rawShopToml)
        if (nativeScriptPath != null && nativeScript != null) {
            writeNativeShopScript(root.resolve(nativeScriptPath), nativeScript)
            ensureGradleDependency(root.resolve(GENERIC_NPCS_BUILD_FILE), SHOPS_API_DEPENDENCY)
        }
    }
}

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
    val rawShopToml = buildShopInventoryToml(
        invInternal = spec.rawShopInternal,
        title = spec.shopTitle,
        serverOnly = spec.serverOnlyInventory,
        buyMultiplier = spec.buyMultiplier,
        sellMultiplier = spec.sellMultiplier,
        changeDelta = spec.changeDelta,
        stock = spec.stock,
    )
    val nativeScriptPath = if (spec.writeNativeScript) nativeShopScriptPath(spec.scriptMarker) else null
    val nativeScript =
        if (spec.writeNativeScript) {
            buildNativeShopScript(spec, spec.inheritedNpc.tradeOpSlot ?: DEFAULT_TRADE_SLOT)
        } else {
            null
        }

    return GeneratedFiles(
        nativeScriptPath = nativeScriptPath,
        nativeScript = nativeScript,
        rawShopInternal = spec.rawShopInternal,
        rawShopToml = rawShopToml,
        currency = spec.currency,
    )
}

private fun buildShopInventoryToml(
    invInternal: String,
    title: String,
    serverOnly: Boolean,
    buyMultiplier: Int,
    sellMultiplier: Int,
    changeDelta: Int,
    stock: List<StockEntry>,
): String =
    buildString {
        appendLine("[[inventory]]")
        appendLine("isServerOnly = $serverOnly")
        appendLine("id = ${invInternal.tomlString()}")
        appendLine("name = ${title.tomlString()}")
        appendLine()
        appendLine("scope = \"Shared\"")
        appendLine("stack = \"Always\"")
        appendLine()
        appendLine("sellMultiplier = $sellMultiplier")
        appendLine("buyMultiplier = $buyMultiplier")
        appendLine("delta = $changeDelta")
        appendLine()
        appendLine("size = ${max(stock.size, 1)}")
        appendLine()
        appendLine("protect = false")
        appendLine("runWeight = false")
        appendLine("restock = true")
        appendLine("allStock = false")
        appendLine("placeholders = false")
        appendLine()
        stock.forEach { entry ->
            appendLine("[[inventory.stock]]")
            appendLine("obj = ${entry.item.internal.tomlString()}")
            appendLine("count = ${entry.count}")
            appendLine("restockCycles = ${entry.restockCycles}")
            appendLine()
        }
    }.trimEnd()

private fun buildNativeShopScript(spec: ShopSpec, tradeSlot: Int): String {
    val className = nativeShopClassName(spec.scriptMarker)
    val openFunction = "open$className"
    return buildString {
        appendLine("package org.rsmod.content.generic.npcs.shops")
        appendLine()
        appendLine("import jakarta.inject.Inject")
        appendLine("import org.rsmod.api.script.onOpNpc$tradeSlot")
        appendLine("import org.rsmod.api.shops.Shops")
        appendLine("import org.rsmod.game.entity.Npc")
        appendLine("import org.rsmod.game.entity.Player")
        appendLine("import org.rsmod.plugin.scripts.PluginScript")
        appendLine("import org.rsmod.plugin.scripts.ScriptContext")
        appendLine()
        appendLine("class $className @Inject constructor(private val shops: Shops) : PluginScript() {")
        appendLine("    override fun ScriptContext.startup() {")
        appendLine("        onOpNpc$tradeSlot(${spec.npcInternal.kotlinString()}) { player.$openFunction(it.npc) }")
        appendLine("    }")
        appendLine()
        appendLine("    private fun Player.$openFunction(npc: Npc) {")
        appendLine("        shops.open(")
        appendLine("            player = this,")
        appendLine("            title = ${spec.shopTitle.kotlinString()},")
        appendLine("            shopInv = ${spec.openInvInternal.kotlinString()},")
        appendLine("            buyPercentage = ${spec.buyMultiplier / 10.0},")
        appendLine("            sellPercentage = ${spec.sellMultiplier / 10.0},")
        appendLine("            changePercentage = ${spec.changeDelta / 10.0},")
        appendLine("            currency = ${spec.currency.internal.kotlinString()},")
        appendLine("        )")
        appendLine("    }")
        appendLine("}")
    }.trimEnd()
}

private fun initialShopPrice(baseCost: Int, sellMultiplier: Int): Int {
    val sellPercentage = sellMultiplier / 10.0
    val basePriceWithMarkup = floor(baseCost * (sellPercentage / 100.0))
    return max(1.0, max(basePriceWithMarkup, baseCost * 0.3)).toInt()
}

private fun multiplierForTargetPrice(baseCost: Int, targetPrice: Int): Int {
    if (targetPrice <= 0) {
        return 0
    }
    var multiplier = ((targetPrice * 1000.0) / baseCost).roundToInt().coerceIn(0, PRICE_MULTIPLIER_MAX)
    while (multiplier < PRICE_MULTIPLIER_MAX && initialShopPrice(baseCost, multiplier) < targetPrice) {
        multiplier++
    }
    while (multiplier > 0 && initialShopPrice(baseCost, multiplier - 1) >= targetPrice) {
        multiplier--
    }
    return multiplier
}

private fun nativeShopScriptPath(marker: String): String =
    "$NATIVE_SHOP_SCRIPT_DIR/${nativeShopClassName(marker)}.kt"

private fun nativeShopClassName(marker: String): String =
    marker.split('_')
        .filter { it.isNotBlank() }
        .joinToString(separator = "", prefix = "ShopMaker") { part ->
            part.replace(Regex("""[^A-Za-z0-9]"""), "")
                .replaceFirstChar { char -> char.uppercase() }
        }

private fun tomlStringValue(block: String, key: String): String? {
    val regex = Regex("""(?m)^\s*"?\Q$key\E"?\s*=\s*"([^"]*)"""")
    return regex.find(block)?.groupValues?.get(1)
}

private fun tomlIntValue(block: String, key: String): Int? {
    val regex = Regex("""(?m)^\s*"?\Q$key\E"?\s*=\s*(-?\d+)""")
    return regex.find(block)?.groupValues?.get(1)?.toIntOrNull()
}

private fun writeNativeShopScript(file: Path, script: String) {
    Files.createDirectories(file.parent)
    Files.writeString(file, script.trimEnd() + "\n")
}

private fun writeRawShop(root: Path, invInternal: String, block: String) {
    val file = findRawShopFile(root, invInternal) ?: createRawShopFile(root, invInternal)
    val text = Files.readString(file)
    val inventoryPattern = Regex("""(?s)\[\[inventory]](.*?)(?=\R\[\[inventory]]|\z)""")
    var replaced = false
    val next =
        inventoryPattern.replace(text) { match ->
            val current = match.value
            if (tomlStringValue(current, "id") == invInternal) {
                replaced = true
                block.trimEnd()
            } else {
                current
            }
        }
    if (replaced) {
        Files.writeString(file, next.trimEnd() + "\n")
    } else {
        val prefix = if (text.isBlank()) "" else text.trimEnd() + "\n\n"
        Files.writeString(file, prefix + block.trimEnd() + "\n")
    }
}

private fun createRawShopFile(root: Path, invInternal: String): Path {
    val dir = root.resolve(RAW_SHOPS_DIR)
    Files.createDirectories(dir)
    val slug = invInternal.removePrefix("inv.").toSlug()
    require(isRealCacheShopInventory(root, invInternal)) {
        "$invInternal is not a real cache shop inventory. Pick an existing inv.* shop inventory; " +
            "invented shop inventories open blank in the client."
    }
    val file = dir.resolve("$slug.toml")
    require(!Files.exists(file)) {
        "Could not find the raw shop file for $invInternal, and $file already exists."
    }
    Files.writeString(file, "")
    return file
}

private fun registerCurrency(root: Path, currency: CurrencyOption) {
    val tableFile = root.resolve(SHOP_CURRENCY_TABLE_FILE)
    require(Files.exists(tableFile)) {
        "Could not find OpenRune's native shop currency table."
    }
    validateShopCurrencyTableTarget(tableFile, currency)
    ensureGameval(root, CURRENCY_GAMEVALS, RSCMType.CURRENCY, currency.key)
    ensureGameval(root, DBROW_GAMEVALS, RSCMType.DBROW, currency.dbrowKey)
    ensureShopCurrencyTableRow(tableFile, currency)
}

private fun ensureGameval(root: Path, relativeFile: String, type: RSCMType, key: String) {
    val file = root.resolve(relativeFile)
    Files.createDirectories(file.parent)
    if (existingGameval(file, key) != null) {
        return
    }
    if (gamevalExists(root, type, key)) {
        return
    }
    val value = if (type == RSCMType.INV) nextLowGamevalId(root, type) else -1
    val prefix =
        if (Files.exists(file) && Files.size(file) > 0L && !Files.readString(file).endsWith("\n")) {
            "\n"
        } else {
            ""
        }
    Files.writeString(file, prefix + "$key=$value\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
}

private fun gamevalExists(root: Path, type: RSCMType, key: String): Boolean {
    val rootPrefix = root.toAbsolutePath().normalize().toString().replace('\\', '/') + "/"
    val provider = GameValProvider.loadIsolated(rootPrefix, autoAssignIds = false)
    return "${type.prefix}.$key" in provider.mappings[type.prefix].orEmpty()
}

private fun nextLowGamevalId(root: Path, type: RSCMType): Int {
    val rootPrefix = root.toAbsolutePath().normalize().toString().replace('\\', '/') + "/"
    val provider = GameValProvider.loadIsolated(rootPrefix, autoAssignIds = false)
    val used = provider.mappings[type.prefix]?.values.orEmpty().toSet()
    val floor = max((provider.maxBaseID[type.prefix] ?: -1) + 1, 0)
    return (floor..MAX_GAMEVAL_ID).firstOrNull { it !in used }
        ?: error("No free ${type.prefix} gameval IDs are available.")
}

private fun ensureShopCurrencyTableRow(file: Path, currency: CurrencyOption) {
    require(currency.objInternal != null || currency.varbitInternal != null) {
        "Currency ${currency.internal} must have an item or varbit backing."
    }
    val text = Files.readString(file)
    if (currency.internal in text || currency.dbrowInternal in text) {
        return
    }
    val index = shopCurrencyInsertIndex(text)
    val row =
        buildString {
            appendLine()
            appendLine("        row(${currency.dbrowInternal.kotlinString()}) {")
            appendLine("            column(KEY, ${currency.internal.kotlinString()})")
            appendLine("            column(SINGULAR_NAME, ${currency.singularName.kotlinString()})")
            appendLine("            column(PLURAL_NAME, ${currency.pluralName.kotlinString()})")
            if (currency.objInternal != null) {
                appendLine("            columnRSCM(OBJ, ${currency.objInternal.kotlinString()})")
            } else {
                appendLine("            columnRSCM(VARBIT, ${currency.varbitInternal!!.kotlinString()})")
            }
            appendLine("        }")
        }
    Files.writeString(file, text.substring(0, index) + row + text.substring(index))
}

private fun validateShopCurrencyTableTarget(file: Path, currency: CurrencyOption) {
    val text = Files.readString(file)
    if (currency.internal in text || currency.dbrowInternal in text) {
        return
    }
    shopCurrencyInsertIndex(text)
}

private fun shopCurrencyInsertIndex(text: String): Int {
    val endOfDbTable = Regex("""(?m)^[ \t]{4}\}[ \t]*\R\}""")
        .findAll(text)
        .lastOrNull()
        ?.range
        ?.first
    require(endOfDbTable != null) {
        "Could not find the end of ShopCurrencyTable.shopCurrencies()."
    }
    return endOfDbTable
}

private fun findRawShopFile(root: Path, invInternal: String): Path? {
    val dir = root.resolve(RAW_SHOPS_DIR)
    if (!Files.isDirectory(dir)) {
        return null
    }
    Files.walk(dir).use { paths ->
        return paths.iterator().asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".toml") }
            .firstOrNull { file -> inventoryBlocks(Files.readString(file)).any { tomlStringValue(it, "id") == invInternal } }
    }
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

private fun loadSpawnedNpcRegions(root: Path): Map<String, Set<String>> {
    val dir = root.resolve(MAP_NPC_SPAWNS_DIR)
    if (!Files.isDirectory(dir)) {
        return emptyMap()
    }
    val npcLine = Regex("""^\s*npc\s*=\s*"([^"]+)"""")
    val regionsByNpc = mutableMapOf<String, MutableSet<String>>()
    Files.walk(dir).use { paths ->
        paths.iterator().asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".toml") }
            .forEach { file ->
                val region = file.spawnRegionName()
                Files.readAllLines(file)
                    .asSequence()
                    .mapNotNull { line -> npcLine.find(line)?.groupValues?.get(1) }
                    .filter { it.startsWith("npc.") }
                    .forEach { npc ->
                        regionsByNpc.getOrPut(npc) { mutableSetOf() } += region
                    }
            }
    }
    return regionsByNpc.mapValues { (_, regions) -> regions.toSortedSet() }
}

private fun loadRawShopIndex(root: Path): RawShopIndex {
    val dir = root.resolve(RAW_SHOPS_DIR)
    val rawShops =
        if (Files.isDirectory(dir)) {
            Files.walk(dir).use { paths ->
                paths.iterator().asSequence()
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".toml") }
                    .flatMap { file -> inventoryBlocks(Files.readString(file)).asSequence() }
                    .mapNotNull(::parseShopInventoryInfo)
                    .associateBy { it.internal }
            }
        } else {
            emptyMap()
        }
    val cacheShops =
        loadCacheShopInventories(root, rawShops.keys)
            .associateBy { it.internal }
    return RawShopIndex(cacheShops + rawShops)
}

private fun loadCacheShopInventories(root: Path, rawShopInternals: Set<String>): List<ShopInventoryInfo> {
    val rootPrefix = root.toAbsolutePath().normalize().toString().replace('\\', '/') + "/"
    val provider = GameValProvider.loadIsolated(rootPrefix, autoAssignIds = false)
    val localInvInternals = localGamevalInternals(root.resolve(INV_GAMEVALS), RSCMType.INV.prefix)
    return provider.mappings[RSCMType.INV.prefix].orEmpty()
        .asSequence()
        .filter { (internal, _) -> internal !in rawShopInternals }
        .filter { (internal, _) -> internal !in localInvInternals }
        .mapNotNull { (internal, id) ->
            ServerCacheManager.getInventory(id)
                ?.takeIf { it.isLikelyNativeShopInventory(internal) }
                ?.toShopInventoryInfo(internal)
        }
        .toList()
}

private fun InventoryServerType.isLikelyNativeShopInventory(internal: String): Boolean {
    val key = shopSearchKey(internal)
    return size in 1..MAX_NATIVE_SHOP_SIZE &&
        (
            "shop" in key ||
                scope == InvScope.Shared && stock.isNotEmpty()
        )
}

private fun InventoryServerType.toShopInventoryInfo(internal: String): ShopInventoryInfo =
    ShopInventoryInfo(
        internal = internal,
        title = internal.removePrefix("inv.").humanizedName(),
        buyMultiplier = CACHE_SHOP_DEFAULT_BUY_MULTIPLIER,
        sellMultiplier = CACHE_SHOP_DEFAULT_SELL_MULTIPLIER,
        changeDelta = CACHE_SHOP_DEFAULT_CHANGE_DELTA,
        stock = stock.map { entry ->
            RawStockEntry(
                itemInternal = safeReverseMapping(RSCMType.OBJ, entry.obj),
                count = entry.count,
                restockCycles = entry.restockCycles,
            )
        },
    )

private fun localGamevalInternals(file: Path, prefix: String): Set<String> {
    if (!Files.exists(file)) {
        return emptySet()
    }
    val assignment = Regex("""^\s*([A-Za-z0-9_]+)\s*=""")
    return Files.readAllLines(file)
        .asSequence()
        .mapNotNull { line -> assignment.find(line)?.groupValues?.get(1) }
        .map { key -> "$prefix.$key" }
        .toSet()
}

private fun isRealCacheShopInventory(root: Path, invInternal: String): Boolean {
    if (invInternal in localGamevalInternals(root.resolve(INV_GAMEVALS), RSCMType.INV.prefix)) {
        return false
    }
    val id = runCatching { RSCM.getRSCM(invInternal) }.getOrNull() ?: return false
    return ServerCacheManager.getInventory(id)?.isLikelyNativeShopInventory(invInternal) == true
}

private fun loadCurrencyOptions(root: Path, items: List<LookupEntry>): List<CurrencyOption> {
    val registered = loadRegisteredCurrencyOptions(root)
    val byInternal = registered.associateBy { it.internal }.toMutableMap()
    knownCurrencySeeds(items).forEach { seed ->
        if (seed.internal !in byInternal) {
            resolveCurrencyObj(seed, items)?.let { objInternal ->
                byInternal[seed.internal] =
                    CurrencyOption(
                        internal = seed.internal,
                        label = seed.label,
                        objInternal = objInternal,
                        singularName = seed.singularName,
                        pluralName = seed.pluralName,
                        registered = false,
                    )
            }
        }
    }
    return byInternal.values.sortedWith(
        compareBy<CurrencyOption> { !it.isStandard }
            .thenBy { it.needsRegistration }
            .thenBy { it.label.lowercase() },
    )
}

private fun loadRegisteredCurrencyOptions(root: Path): List<CurrencyOption> {
    val table = root.resolve(SHOP_CURRENCY_TABLE_FILE)
    if (!Files.exists(table)) {
        return listOf(CurrencyOption.StandardGp)
    }
    val rowPattern = Regex("""(?s)row\("dbrow\.shop_currency_[^"]+"\)\s*\{(.*?)\n\s*}""")
    val keyPattern = Regex("""column\(KEY,\s*"([^"]+)"""")
    val singularPattern = Regex("""column\(SINGULAR_NAME,\s*"([^"]+)"""")
    val pluralPattern = Regex("""column\(PLURAL_NAME,\s*"([^"]+)"""")
    val objPattern = Regex("""columnRSCM\(OBJ,\s*"([^"]+)"""")
    val varbitPattern = Regex("""columnRSCM\(VARBIT,\s*"([^"]+)"""")
    val rows =
        rowPattern.findAll(Files.readString(table))
            .mapNotNull { row ->
                val block = row.groupValues[1]
                val internal = keyPattern.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
                val singular = singularPattern.find(block)?.groupValues?.get(1) ?: internal.removePrefix("currency.")
                val plural = pluralPattern.find(block)?.groupValues?.get(1) ?: singular
                val objInternal = objPattern.find(block)?.groupValues?.get(1)
                val varbitInternal = varbitPattern.find(block)?.groupValues?.get(1)
                CurrencyOption(
                    internal = internal,
                    label = if (internal == CurrencyOption.StandardGp.internal) {
                        CurrencyOption.StandardGp.label
                    } else {
                        plural.humanizedName()
                    },
                    objInternal = objInternal,
                    varbitInternal = varbitInternal,
                    singularName = singular,
                    pluralName = plural,
                    registered = true,
                )
            }
            .toList()
    return (rows + CurrencyOption.StandardGp).distinctBy { it.internal }
}

private fun knownCurrencySeeds(items: List<LookupEntry>): List<KnownCurrencySeed> =
    listOf(
        KnownCurrencySeed("tokkul", "Tokkul", "tokkul", "tokkul", listOf("obj.tokkul", "obj.tzhaar_token")),
        KnownCurrencySeed("trading_sticks", "Trading sticks", "trading stick", "trading sticks", listOf("obj.trading_sticks", "obj.village_trade_sticks")),
        KnownCurrencySeed("numulite", "Numulite", "numulite", "numulite", listOf("obj.numulite", "obj.fossil_numulite")),
        KnownCurrencySeed("warrior_guild_token", "Warrior Guild tokens", "Warrior Guild token", "Warrior Guild tokens", listOf("obj.warrior_guild_token", "obj.warguild_tokens")),
        KnownCurrencySeed("castle_wars_ticket", "Castle Wars tickets", "Castle Wars ticket", "Castle Wars tickets", listOf("obj.castle_wars_ticket", "obj.castlewars_ticket")),
        KnownCurrencySeed("mark_of_grace", "Marks of grace", "mark of grace", "marks of grace", listOf("obj.mark_of_grace", "obj.grace")),
        KnownCurrencySeed("golden_nugget", "Golden nuggets", "golden nugget", "golden nuggets", listOf("obj.golden_nugget", "obj.motherlode_nugget")),
        KnownCurrencySeed("abyssal_pearl", "Abyssal pearls", "abyssal pearl", "abyssal pearls", listOf("obj.abyssal_pearl")),
        KnownCurrencySeed("molch_pearl", "Molch pearls", "molch pearl", "molch pearls", listOf("obj.molch_pearl", "obj.aerial_fishing_pearl")),
        KnownCurrencySeed("stardust", "Stardust", "stardust", "stardust", listOf("obj.star_dust")),
        KnownCurrencySeed("barronite_shard", "Barronite shards", "Barronite shard", "Barronite shards", listOf("obj.barronite_shard", "obj.barronite_shards", "obj.camdozaal_barronite_shard")),
        KnownCurrencySeed("ancient_essence", "Ancient essence", "ancient essence", "ancient essence", listOf("obj.ancient_essence")),
        KnownCurrencySeed("termite", "Termites", "termite", "termites", listOf("obj.termite", "obj.varlamore_wyrm_agility_termite")),
        KnownCurrencySeed("ecto_token", "Ecto-tokens", "ecto-token", "ecto-tokens", listOf("obj.ecto_token", "obj.ectotoken")),
        KnownCurrencySeed("agility_arena_ticket", "Agility arena tickets", "Agility arena ticket", "Agility arena tickets", listOf("obj.agility_arena_ticket", "obj.agilityarena_ticket")),
        KnownCurrencySeed("brimhaven_voucher", "Brimhaven vouchers", "Brimhaven voucher", "Brimhaven vouchers", listOf("obj.brimhaven_voucher", "obj.agilityarena_voucher")),
        KnownCurrencySeed("blood_money", "Blood money", "blood money", "blood money", listOf("obj.blood_money", "obj.deadman_coins")),
        KnownCurrencySeed("platinum_token", "Platinum tokens", "platinum token", "platinum tokens", listOf("obj.platinum_token", "obj.platinum")),
        KnownCurrencySeed("chaos_rune", "Chaos runes", "chaos rune", "chaos runes", listOf("obj.chaosrune", "obj.chaos_rune")),
    ).filter { seed ->
        resolveCurrencyObj(seed, items) != null
    }

private fun resolveCurrencyObj(seed: KnownCurrencySeed, items: List<LookupEntry>): String? {
    seed.objCandidates.firstOrNull(::canResolveObj)?.let { return it }
    val nameKeys = setOf(seed.label, seed.singularName, seed.pluralName).map(::shopSearchKey).toSet()
    return items.firstOrNull { item -> shopSearchKey(item.name) in nameKeys }?.internal
}

private fun canResolveObj(internal: String): Boolean =
    runCatching { RSCM.getRSCM(internal) }.isSuccess

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

private fun loadScriptedNpcIndex(root: Path, rawShopIndex: RawShopIndex): ScriptedNpcIndex {
    val contentDir = root.resolve("content")
    if (!Files.isDirectory(contentDir)) {
        return ScriptedNpcIndex(emptyMap(), emptyMap(), emptyList())
    }
    val links = mutableMapOf<String, MutableList<ShopInventoryInfo>>()
    val currencies = mutableMapOf<String, String>()
    val opPatterns = mutableListOf<NpcOpPattern>()
    val npcPattern = Regex("""onOpNpc(\d)\s*\(\s*"([^"]+)"""")
    val shopOpenPattern =
        Regex("""shops\.open\s*\((?s:.*?)"[^"]+"\s*,\s*"(inv\.[^"]+)"""")
    val namedShopInvPattern = Regex("""shopInv\s*=\s*"(inv\.[^"]+)"""")
    val invLiteralPattern = Regex(""""(inv\.[^"]+)"""")
    val currencyPattern = Regex("""currency\s*=\s*"([^"]+)"""")
    Files.walk(contentDir).use { paths ->
        paths.iterator().asSequence()
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .forEach { file ->
                val text = Files.readString(file)
                val npcOps =
                    npcPattern.findAll(text)
                        .mapNotNull { match ->
                            val opSlot = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                            val npcInternal = match.groupValues[2].takeIf { it.startsWith("npc.") }
                                ?: return@mapNotNull null
                            NpcOpPattern(opSlot, npcInternal)
                        }
                        .toList()
                opPatterns += npcOps
                val npcs = npcOps.map { it.npcPattern }.toSet()
                val directShopInvs =
                    (
                        shopOpenPattern.findAll(text).map { it.groupValues[1] } +
                            namedShopInvPattern.findAll(text).map { it.groupValues[1] }
                    ).toSet()
                val shopInvs =
                    if (directShopInvs.isNotEmpty()) {
                        directShopInvs
                    } else {
                        invLiteralPattern.findAll(text).map { it.groupValues[1] }.toSet()
                    }
                if (npcs.isNotEmpty() && shopInvs.isNotEmpty()) {
                    val shops = shopInvs.mapNotNull(rawShopIndex::byInternal).distinctBy { it.internal }
                    if (shops.isEmpty()) {
                        return@forEach
                    }
                    val currency = currencyPattern.find(text)?.groupValues?.get(1) ?: CurrencyOption.StandardGp.internal
                    npcs.forEach { npc ->
                        val linked = links.getOrPut(npc) { mutableListOf() }
                        shops.forEach { shop ->
                            if (linked.none { it.internal == shop.internal }) {
                                linked += shop
                            }
                        }
                        currencies.putIfAbsent(npc, currency)
                    }
                }
            }
    }
    return ScriptedNpcIndex(
        shopLinks = links.mapValues { (_, shops) -> shops.distinctBy { it.internal } },
        currencies = currencies,
        opPatterns = opPatterns,
    )
}

private fun chooseScriptedShop(shops: List<ShopInventoryInfo>): ShopInventoryInfo? {
    val distinct = shops.distinctBy { it.internal }
    if (distinct.isEmpty()) {
        return null
    }
    if (distinct.size == 1) {
        return distinct.single()
    }
    val titleGroups = distinct.groupBy { shopSearchKey(it.title) }
    if (titleGroups.size != 1) {
        return null
    }
    return distinct.sortedWith(
        compareBy<ShopInventoryInfo> { "skillcape" in it.internal }
            .thenBy { it.internal.length }
            .thenBy { it.internal },
    ).first()
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
    spawnedNpcRegions: Map<String, Set<String>>,
    shopParams: ShopParamIds,
    rawShopIndex: RawShopIndex,
    scriptedNpcIndex: ScriptedNpcIndex,
): LookupEntry? {
    val internal = runCatching { internalName }.getOrNull()?.takeIf { it.startsWith("npc.") } ?: return null
    val regions = spawnedNpcRegions[internal].orEmpty()
    val ops = (1..5).mapNotNull { slot ->
        actions.getOpOrNull(slot - 1)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("hidden", ignoreCase = true) }
            ?.let { "$slot:$it" }
    }
    val hasTrade = ops.any { it.substringAfter(':').equals("trade", ignoreCase = true) }
    val tradeSlot =
        ops.asSequence()
            .map { it.trim() }
            .mapNotNull { action ->
                val slot = action.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                val option = action.substringAfter(':', "")
                if (option.equals("trade", ignoreCase = true)) slot else null
            }
            .firstOrNull()
    val paramShopInventoryId = paramsRaw?.get(shopParams.inventory).asParamInt()
    val paramShopInventoryInternal = paramShopInventoryId?.let { safeReverseMapping(RSCMType.INV, it) }
    val scriptedShops = scriptedNpcIndex.shopsFor(internal)
    val scriptedShop = chooseScriptedShop(scriptedShops)
    val shopCurrency = scriptedNpcIndex.currencyFor(internal)
    val hasNativeTradeScript = tradeSlot?.let { scriptedNpcIndex.hasOp(internal, it) } == true
    val paramShop = rawShopIndex.byInternal(paramShopInventoryInternal)
    val matchedShop = rawShopIndex.matchNpc(name, internal, regions)
    val shopVariants =
        buildList {
                if (paramShop != null) {
                    add(paramShop)
                }
                addAll(scriptedShops)
                if (paramShop == null && scriptedShops.isEmpty() && matchedShop != null) {
                    add(matchedShop)
                }
            }
            .distinctBy { it.internal }
    val linkedShop =
        paramShop
            ?: chooseScriptedShop(shopVariants)
            ?: scriptedShop
            ?: matchedShop
    val shopInventoryId =
        paramShopInventoryId ?: linkedShop?.internal?.let { runCatching { RSCM.getRSCM(it) }.getOrNull() }
    val hasShopInventory = paramShopInventoryId != null || shopVariants.isNotEmpty() || linkedShop != null
    return LookupEntry(
        id = id,
        internal = internal,
        name = name.ifBlank { internal.removePrefix("npc.") },
        actions = ops.joinToString(", "),
        hasTrade = hasTrade,
        regions = regions,
        hasShopInventory = hasShopInventory,
        isSpawned = regions.isNotEmpty(),
        shopInventoryId = shopInventoryId,
        shopInventoryInternal = paramShopInventoryInternal ?: linkedShop?.internal,
        shopTitle = paramsRaw?.get(shopParams.name) as? String ?: linkedShop?.title,
        shopBuyMultiplier = paramsRaw?.get(shopParams.buyPercentage).asParamInt() ?: linkedShop?.buyMultiplier,
        shopSellMultiplier = paramsRaw?.get(shopParams.sellPercentage).asParamInt() ?: linkedShop?.sellMultiplier,
        shopChangeDelta = paramsRaw?.get(shopParams.changePercentage).asParamInt() ?: linkedShop?.changeDelta,
        shopStock = linkedShop?.stock.orEmpty(),
        shopVariants = shopVariants,
        shopCurrencyInternal = shopCurrency,
        scriptedShopInternal = scriptedShop?.internal,
        hasNativeTradeScript = hasNativeTradeScript,
    )
}

private fun loadServerCache(repoRoot: Path) {
    val root = repoRoot.toAbsolutePath().normalize()
    val previousUserDir = System.getProperty("user.dir")
    try {
        val rootPrefix = root.toString().replace('\\', '/') + "/"
        GameValProvider.load(rootPrefix)
        System.setProperty("user.dir", root.toString())
        ServerCacheManager.init(root.resolve(".data").resolve("cache").resolve("SERVER"), 239)
    } finally {
        System.setProperty("user.dir", previousUserDir)
    }
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
        regions = emptySet(),
        cost = cost,
    )
}

private fun ensureShopMakerCompatibility(repoRoot: Path): Boolean {
    return try {
        ensureInventoryOverlaySupport(repoRoot)
        true
    } catch (e: Exception) {
        JOptionPane.showMessageDialog(
            null,
            "Shop Manager needs native inventory overlay support, but the OpenRune file " +
                "does not match the expected layout.\n\nNo files were changed.\n\n${e.message}",
            "Shop Manager",
            JOptionPane.ERROR_MESSAGE,
        )
        false
    }
}

private fun ensureInventoryOverlaySupport(repoRoot: Path) {
    val file = repoRoot.resolve(INVENTORY_CODEC_FILE)
    if (!Files.exists(file)) {
        return
    }
    val original = Files.readString(file)
    val normalized = original.replace("\r\n", "\n")
    if ("customData.size != InventoryServerType().size" in normalized) {
        return
    }
    val safeSizeOverlay =
        listOf(
            "            if (inventoryType == null || customData.size != InventoryServerType().size) {",
            "                size = customData.size",
            "            }",
        ).joinToString("\n")
    if ("            size = customData.size" in normalized) {
        val patched = normalized.replace("            size = customData.size", safeSizeOverlay)
        val newline = if ("\r\n" in original) "\r\n" else "\n"
        Files.writeString(file, patched.replace("\n", newline))
        return
    }

    val headerPattern =
        Regex(
            """(?m)^        val inventoryType = types\[id] \?: return\R""" +
                """        size = inventoryType\.size\R""" +
                """        val customData = custom\?\.get\(id\)""",
        )
    val withOverlayOnlyHeader =
        headerPattern.replace(normalized) {
            listOf(
                "        val customData = custom?.get(id)",
                "        val inventoryType = types[id]",
                "",
                "        if (inventoryType != null) {",
                "            size = inventoryType.size",
                "        }",
            ).joinToString("\n")
        }
    require(withOverlayOnlyHeader != normalized) {
        "Could not find InventoryServerCodec's base inventory lookup block."
    }

    val customPattern =
        Regex(
            """(?m)^        if \(customData != null\) \{\R""" +
                """            scope = customData\.scope""",
        )
    val patched =
        customPattern.replace(withOverlayOnlyHeader) {
            listOf(
                "        if (customData != null) {",
                safeSizeOverlay,
                "            scope = customData.scope",
            ).joinToString("\n")
        }
    require(patched != withOverlayOnlyHeader) {
        "Could not find InventoryServerCodec's custom inventory block."
    }

    val newline = if ("\r\n" in original) "\r\n" else "\n"
    Files.writeString(file, patched.replace("\n", newline))
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

private const val GENERIC_NPCS_BUILD_FILE = "content/generic/generic-npcs/build.gradle.kts"
private const val SHOPS_API_DEPENDENCY = "implementation(projects.api.shops)"
private const val RAW_SHOPS_DIR = ".data/raw-cache/server/shops"
private const val NATIVE_SHOP_SCRIPT_DIR =
    "content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/shops/generated"
private const val INVENTORY_CODEC_FILE =
    "or-cache/src/main/kotlin/dev/openrune/codec/osrs/impl/InventoryServerCodec.kt"
private const val PARAM_GAMEVALS = ".data/gamevals/param.rscm"
private const val INV_GAMEVALS = ".data/gamevals/inv.rscm"
private const val CURRENCY_GAMEVALS = ".data/gamevals/currency.rscm"
private const val DBROW_GAMEVALS = ".data/gamevals/dbrow.rscm"
private const val SHOP_CURRENCY_TABLE_FILE = "or-cache/src/main/kotlin/dev/openrune/tables/ShopCurrencyTable.kt"
private const val MAP_NPC_SPAWNS_DIR = ".data/raw-cache/map/npcs"
private const val MAX_GAMEVAL_ID = 65535
private const val ALL_REGIONS = "All regions"
private const val SHOP_INVENTORY_PARAM_ID = 65525
private const val SHOP_NAME_PARAM_ID = 65524
private const val SHOP_SELL_PERCENTAGE_PARAM_ID = 65497
private const val SHOP_BUY_PERCENTAGE_PARAM_ID = 65498
private const val SHOP_CHANGE_PERCENTAGE_PARAM_ID = 65499
private const val DEFAULT_TRADE_SLOT = 3
private const val PRICE_MULTIPLIER_MAX = 10_000_000
private const val MAX_NATIVE_SHOP_SIZE = 28
private const val CACHE_SHOP_DEFAULT_BUY_MULTIPLIER = 600
private const val CACHE_SHOP_DEFAULT_SELL_MULTIPLIER = 1000
private const val CACHE_SHOP_DEFAULT_CHANGE_DELTA = 20
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

private fun String.normalizedQuery(): String = trim().lowercase()

private fun Path.spawnRegionName(): String =
    fileName.toString()
        .removeSuffix(".toml")
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }

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

private fun String.humanizedName(): String =
    replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }

private fun String.tomlString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun String.kotlinString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

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

private fun JComboBox<String>.withLabel(label: String): JPanel {
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
