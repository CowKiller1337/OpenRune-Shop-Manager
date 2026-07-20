package org.rsmod.tools.shopmaker.intellij

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import org.rsmod.tools.shopmaker.createShopMakerPanel

class OpenRuneShopManagerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun isApplicable(project: Project): Boolean = project.openRuneRoot() != null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = project.openRuneRoot()?.let(::createShopMakerPanel) ?: unavailablePanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun Project.openRuneRoot(): Path? {
        val root = basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return null
        return root.takeIf { it.hasOpenRuneLayout() }
    }

    private fun Path.hasOpenRuneLayout(): Boolean =
        Files.exists(resolve("settings.gradle.kts")) &&
            Files.isDirectory(resolve(".data/raw-cache/server")) &&
            Files.isDirectory(resolve(".data/raw-cache/map"))

    private fun unavailablePanel(): JPanel =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JLabel("Open an OpenRune server project to use the Shop Manager."), BorderLayout.NORTH)
        }
}
