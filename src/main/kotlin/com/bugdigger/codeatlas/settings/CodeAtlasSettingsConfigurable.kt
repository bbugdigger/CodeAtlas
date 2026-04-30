package com.bugdigger.codeatlas.settings

import com.bugdigger.codeatlas.mcp.McpServerService
import com.bugdigger.codeatlas.mcp.McpServerSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Project-level settings UI for CodeAtlas.
 *
 * Layout:
 *  - Indexing preferences at the top (`includeTestSources`, cache directory override).
 *  - MCP server section: enable toggle, port, status, and a "Copy Claude Desktop config"
 *    button. Application-scoped (one MCP server per IDE instance) but exposed in the
 *    project configurable for discoverability — apply triggers a server restart.
 */
class CodeAtlasSettingsConfigurable(private val project: Project) :
    SearchableConfigurable,
    Configurable.NoScroll {

    private val includeTestsCheckBox = JCheckBox("Include test sources in indexing")
    private val cacheDirField = JTextField()

    private val mcpEnabledCheckBox =
        JCheckBox("Enable MCP server (shared across all open projects in this IDE)")
    private val mcpPortField = JTextField()
    private val mcpStatusLabel = JBLabel().apply { foreground = JBColor.GRAY }
    private val mcpCopyConfigButton = JButton("Copy Claude Desktop config")

    private val applyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getId(): String = "com.bugdigger.codeatlas.settings"

    override fun getDisplayName(): String = "CodeAtlas"

    override fun createComponent(): JComponent {
        mcpCopyConfigButton.addActionListener { copyClaudeDesktopConfig() }

        return FormBuilder.createFormBuilder()
            .addComponent(includeTestsCheckBox)
            .addLabeledComponent("Cache directory override", cacheDirField)
            .addSeparator()
            .addComponent(mcpEnabledCheckBox)
            .addLabeledComponent("MCP server port", mcpPortField)
            .addComponent(mcpStatusLabel)
            .addComponent(mcpCopyConfigButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = project.service<CodeAtlasSettingsService>()
        val mcp = McpServerSettings.getInstance()
        return includeTestsCheckBox.isSelected != s.includeTestSources ||
            cacheDirField.text.trim().ifEmpty { null } != s.cacheDirOverride ||
            mcpEnabledCheckBox.isSelected != mcp.enabled ||
            parsedMcpPort() != mcp.port
    }

    override fun apply() {
        val s = project.service<CodeAtlasSettingsService>()
        s.includeTestSources = includeTestsCheckBox.isSelected
        s.cacheDirOverride = cacheDirField.text.trim().ifEmpty { null }

        val mcp = McpServerSettings.getInstance()
        val newPort = parsedMcpPort()
        val mcpChanged = mcpEnabledCheckBox.isSelected != mcp.enabled || newPort != mcp.port
        if (mcpChanged) {
            mcp.enabled = mcpEnabledCheckBox.isSelected
            mcp.port = newPort
            // Restart asynchronously so apply() returns quickly even on a slow port rebind.
            applyScope.launch(Dispatchers.IO) {
                runCatching { McpServerService.getInstance().restart() }
                SwingUtilities.invokeLater { refreshMcpStatus() }
            }
        }
    }

    override fun reset() {
        val s = project.service<CodeAtlasSettingsService>()
        includeTestsCheckBox.isSelected = s.includeTestSources
        cacheDirField.text = s.cacheDirOverride.orEmpty()

        val mcp = McpServerSettings.getInstance()
        mcpEnabledCheckBox.isSelected = mcp.enabled
        mcpPortField.text = mcp.port.toString()
        refreshMcpStatus()
    }

    override fun disposeUIResources() {
        applyScope.cancel()
    }

    private fun parsedMcpPort(): Int {
        val raw = mcpPortField.text.trim().toIntOrNull() ?: McpServerSettings.DEFAULT_PORT
        return raw.coerceIn(McpServerSettings.MIN_PORT, McpServerSettings.MAX_PORT)
    }

    private fun refreshMcpStatus() {
        when (val s = McpServerService.getInstance().currentStatus) {
            is McpServerService.Status.Listening -> {
                mcpStatusLabel.foreground = JBColor.namedColor(
                    "Component.successColor", JBColor(0x008000, 0x6CB33F),
                )
                mcpStatusLabel.text = "Listening on ${McpServerService.url(s.port)}"
                mcpCopyConfigButton.isEnabled = true
            }
            is McpServerService.Status.Stopped -> {
                mcpStatusLabel.foreground = JBColor.GRAY
                mcpStatusLabel.text = "Disabled"
                mcpCopyConfigButton.isEnabled = mcpEnabledCheckBox.isSelected
            }
            is McpServerService.Status.Failed -> {
                mcpStatusLabel.foreground = JBColor.RED
                mcpStatusLabel.text = "Port ${s.port} unavailable: ${s.reason.take(120)}"
                mcpCopyConfigButton.isEnabled = false
            }
        }
    }

    private fun copyClaudeDesktopConfig() {
        val port = parsedMcpPort()
        val snippet = """
            {
              "mcpServers": {
                "codeatlas": { "url": "${McpServerService.url(port)}" }
              }
            }
        """.trimIndent()
        CopyPasteManager.getInstance().setContents(StringSelection(snippet))
        mcpStatusLabel.foreground = JBColor.namedColor(
            "Component.successColor", JBColor(0x008000, 0x6CB33F),
        )
        mcpStatusLabel.text = "Config snippet copied to clipboard."
    }
}
