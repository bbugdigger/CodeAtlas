package com.bugdigger.codeatlas.settings

import com.bugdigger.codeatlas.mcp.McpServerService
import com.bugdigger.codeatlas.mcp.McpServerSettings
import com.bugdigger.codeatlas.rag.ConnectionTester
import com.bugdigger.codeatlas.rag.LlmProvider
import com.bugdigger.codeatlas.rag.LlmProviderKind
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.CardLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Project-level settings UI for CodeAtlas.
 *
 * Layout:
 *  - Indexing preferences at the top.
 *  - LLM provider section driven by a [CardLayout]: only the active provider's
 *    fields are visible at any time.
 *  - Each hosted provider has an API key field (backed by [PasswordSafe] on
 *    apply), a help link to the provider's API key console, and a Test
 *    Connection button that fires a tiny live request through [ConnectionTester].
 *  - MCP server section: enable toggle, port, status, and a "Copy Claude
 *    Desktop config" button. These settings are application-scoped (one MCP
 *    server per IDE instance) but exposed in the project configurable for
 *    discoverability — the apply path triggers a server restart.
 *
 * API key fields start empty and are written to PasswordSafe only on [apply]
 * when the user has edited them, so re-opening the dialog never exposes
 * stored keys. Tooltips show "Key stored in PasswordSafe (N chars)" so users
 * can sanity-check a paste without seeing the secret.
 */
class CodeAtlasSettingsConfigurable(private val project: Project) :
    SearchableConfigurable,
    Configurable.NoScroll {

    private val includeTestsCheckBox = JCheckBox("Include test sources in indexing")
    private val cacheDirField = JTextField()

    private val providerCombo = JComboBox(LlmProviderKind.entries.toTypedArray())
    private val providerCards = JPanel(CardLayout())

    private val anthropicModelField = JTextField()
    private val anthropicKeyField = JBPasswordField()
    private val anthropicTestButton = JButton("Test connection")
    private val anthropicTestStatus = statusLabel()

    private val openAiModelField = JTextField()
    private val openAiKeyField = JBPasswordField()
    private val openAiTestButton = JButton("Test connection")
    private val openAiTestStatus = statusLabel()

    private val ollamaEndpointField = JTextField()
    private val ollamaModelField = JTextField()
    private val ollamaTestButton = JButton("Test connection")
    private val ollamaTestStatus = statusLabel()

    private val mcpEnabledCheckBox =
        JCheckBox("Enable MCP server (shared across all open projects in this IDE)")
    private val mcpPortField = JTextField()
    private val mcpStatusLabel = statusLabel()
    private val mcpCopyConfigButton = JButton("Copy Claude Desktop config")

    // Track whether the user edited a password field since last reset; only then do we write.
    private var anthropicKeyDirty = false
    private var openAiKeyDirty = false

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getId(): String = "com.bugdigger.codeatlas.settings"

    override fun getDisplayName(): String = "CodeAtlas"

    override fun createComponent(): JComponent {
        anthropicKeyField.document.addDocumentListener(simpleListener { anthropicKeyDirty = true })
        openAiKeyField.document.addDocumentListener(simpleListener { openAiKeyDirty = true })

        providerCards.add(buildAnthropicPanel(), LlmProviderKind.ANTHROPIC.name)
        providerCards.add(buildOpenAiPanel(), LlmProviderKind.OPENAI.name)
        providerCards.add(buildOllamaPanel(), LlmProviderKind.OLLAMA.name)

        providerCombo.addActionListener { showActiveProviderCard() }

        anthropicTestButton.addActionListener {
            runTest(LlmProviderKind.ANTHROPIC, anthropicTestStatus, anthropicTestButton)
        }
        openAiTestButton.addActionListener {
            runTest(LlmProviderKind.OPENAI, openAiTestStatus, openAiTestButton)
        }
        ollamaTestButton.addActionListener {
            runTest(LlmProviderKind.OLLAMA, ollamaTestStatus, ollamaTestButton)
        }

        mcpCopyConfigButton.addActionListener { copyClaudeDesktopConfig() }

        return FormBuilder.createFormBuilder()
            .addComponent(includeTestsCheckBox)
            .addLabeledComponent("Cache directory override", cacheDirField)
            .addSeparator()
            .addLabeledComponent("LLM provider", providerCombo)
            .addComponent(providerCards)
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
            providerCombo.selectedItem != s.llmProvider ||
            anthropicModelField.text.trim() != s.anthropicModel ||
            openAiModelField.text.trim() != s.openAiModel ||
            ollamaEndpointField.text.trim() != s.ollamaEndpoint ||
            ollamaModelField.text.trim() != s.ollamaModel ||
            mcpEnabledCheckBox.isSelected != mcp.enabled ||
            parsedMcpPort() != mcp.port ||
            anthropicKeyDirty ||
            openAiKeyDirty
    }

    override fun apply() {
        val s = project.service<CodeAtlasSettingsService>()
        s.includeTestSources = includeTestsCheckBox.isSelected
        s.cacheDirOverride = cacheDirField.text.trim().ifEmpty { null }
        s.llmProvider = providerCombo.selectedItem as LlmProviderKind
        s.anthropicModel = anthropicModelField.text.trim().ifEmpty { LlmProvider.DEFAULT_ANTHROPIC_MODEL }
        s.openAiModel = openAiModelField.text.trim().ifEmpty { LlmProvider.DEFAULT_OPENAI_MODEL }
        s.ollamaEndpoint = ollamaEndpointField.text.trim().ifEmpty { LlmProvider.DEFAULT_OLLAMA_ENDPOINT }
        s.ollamaModel = ollamaModelField.text.trim().ifEmpty { LlmProvider.DEFAULT_OLLAMA_MODEL }

        if (anthropicKeyDirty) {
            val raw = String(anthropicKeyField.password).trim()
            s.setApiKey(LlmProviderKind.ANTHROPIC, raw.ifEmpty { null })
            anthropicKeyField.text = ""
            anthropicKeyDirty = false
            anthropicKeyField.toolTipText = tooltipFor(s.getApiKey(LlmProviderKind.ANTHROPIC))
        }
        if (openAiKeyDirty) {
            val raw = String(openAiKeyField.password).trim()
            s.setApiKey(LlmProviderKind.OPENAI, raw.ifEmpty { null })
            openAiKeyField.text = ""
            openAiKeyDirty = false
            openAiKeyField.toolTipText = tooltipFor(s.getApiKey(LlmProviderKind.OPENAI))
        }

        val mcp = McpServerSettings.getInstance()
        val newPort = parsedMcpPort()
        val mcpChanged = mcpEnabledCheckBox.isSelected != mcp.enabled || newPort != mcp.port
        if (mcpChanged) {
            mcp.enabled = mcpEnabledCheckBox.isSelected
            mcp.port = newPort
            // Restart asynchronously so apply() returns quickly even on a slow port rebind.
            testScope.launch(Dispatchers.IO) {
                runCatching { McpServerService.getInstance().restart() }
                SwingUtilities.invokeLater { refreshMcpStatus() }
            }
        }
    }

    override fun reset() {
        val s = project.service<CodeAtlasSettingsService>()
        includeTestsCheckBox.isSelected = s.includeTestSources
        cacheDirField.text = s.cacheDirOverride.orEmpty()
        providerCombo.selectedItem = s.llmProvider
        anthropicModelField.text = s.anthropicModel
        openAiModelField.text = s.openAiModel
        ollamaEndpointField.text = s.ollamaEndpoint
        ollamaModelField.text = s.ollamaModel
        // API keys are never pre-populated; tooltip signals whether one is already stored.
        anthropicKeyField.text = ""
        openAiKeyField.text = ""
        anthropicKeyField.toolTipText = tooltipFor(s.getApiKey(LlmProviderKind.ANTHROPIC))
        openAiKeyField.toolTipText = tooltipFor(s.getApiKey(LlmProviderKind.OPENAI))
        anthropicKeyDirty = false
        openAiKeyDirty = false
        anthropicTestStatus.text = ""
        openAiTestStatus.text = ""
        ollamaTestStatus.text = ""

        val mcp = McpServerSettings.getInstance()
        mcpEnabledCheckBox.isSelected = mcp.enabled
        mcpPortField.text = mcp.port.toString()
        refreshMcpStatus()

        showActiveProviderCard()
    }

    override fun disposeUIResources() {
        testScope.cancel()
    }

    private fun showActiveProviderCard() {
        val kind = providerCombo.selectedItem as? LlmProviderKind ?: return
        (providerCards.layout as CardLayout).show(providerCards, kind.name)
    }

    /** Build a transient [LlmProvider] from the current dirty form state, if possible. */
    private fun resolveProviderForTest(kind: LlmProviderKind): LlmProvider? {
        val s = project.service<CodeAtlasSettingsService>()
        return when (kind) {
            LlmProviderKind.ANTHROPIC -> {
                val key = String(anthropicKeyField.password).trim()
                    .ifEmpty { s.getApiKey(LlmProviderKind.ANTHROPIC).orEmpty() }
                if (key.isBlank()) null
                else LlmProvider.Anthropic(key, anthropicModelField.text.trim().ifEmpty { LlmProvider.DEFAULT_ANTHROPIC_MODEL })
            }
            LlmProviderKind.OPENAI -> {
                val key = String(openAiKeyField.password).trim()
                    .ifEmpty { s.getApiKey(LlmProviderKind.OPENAI).orEmpty() }
                if (key.isBlank()) null
                else LlmProvider.OpenAI(key, openAiModelField.text.trim().ifEmpty { LlmProvider.DEFAULT_OPENAI_MODEL })
            }
            LlmProviderKind.OLLAMA -> LlmProvider.Ollama(
                ollamaEndpointField.text.trim().ifEmpty { LlmProvider.DEFAULT_OLLAMA_ENDPOINT },
                ollamaModelField.text.trim().ifEmpty { LlmProvider.DEFAULT_OLLAMA_MODEL },
            )
        }
    }

    private fun runTest(kind: LlmProviderKind, status: JBLabel, button: JButton) {
        val provider = resolveProviderForTest(kind)
        if (provider == null) {
            status.foreground = JBColor.RED
            status.text = "Enter an API key first."
            return
        }
        button.isEnabled = false
        status.foreground = JBColor.GRAY
        status.text = "Testing…"
        testScope.launch {
            val result = withContext(Dispatchers.IO) { ConnectionTester.test(provider) }
            SwingUtilities.invokeLater {
                if (result.isSuccess) {
                    status.foreground = JBColor.namedColor("Component.successColor", JBColor(0x008000, 0x6CB33F))
                    status.text = "Connected."
                } else {
                    status.foreground = JBColor.RED
                    status.text = result.exceptionOrNull()?.message
                        ?.take(120)
                        ?: "Connection failed."
                }
                button.isEnabled = true
            }
        }
    }

    private fun buildAnthropicPanel(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Anthropic model", anthropicModelField)
        .addLabeledComponent("Anthropic API key", anthropicKeyField)
        .addComponentToRightColumn(helpLink("Get a key at console.anthropic.com", ANTHROPIC_KEYS_URL))
        .addComponent(testRow(anthropicTestButton, anthropicTestStatus))
        .panel
        .withInsets()

    private fun buildOpenAiPanel(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("OpenAI model", openAiModelField)
        .addLabeledComponent("OpenAI API key", openAiKeyField)
        .addComponentToRightColumn(helpLink("Get a key at platform.openai.com", OPENAI_KEYS_URL))
        .addComponent(testRow(openAiTestButton, openAiTestStatus))
        .panel
        .withInsets()

    private fun buildOllamaPanel(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Ollama endpoint", ollamaEndpointField)
        .addLabeledComponent("Ollama model", ollamaModelField)
        .addComponent(testRow(ollamaTestButton, ollamaTestStatus))
        .panel
        .withInsets()

    private fun helpLink(text: String, url: String): HyperlinkLabel = HyperlinkLabel(text).apply {
        setHyperlinkTarget(url)
    }

    private fun testRow(button: JButton, status: JBLabel): JPanel = JPanel().apply {
        layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)
        add(button)
        add(status)
    }

    private fun statusLabel(): JBLabel = JBLabel().apply { foreground = JBColor.GRAY }

    private fun JPanel.withInsets(): JPanel = apply { border = JBUI.Borders.emptyTop(4) }

    private fun tooltipFor(current: String?): String = when {
        current.isNullOrBlank() -> "No key stored"
        else -> "Key stored in PasswordSafe (${current.length} chars). Leave blank to keep."
    }

    private inline fun simpleListener(crossinline onChange: () -> Unit) =
        object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange()
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

    private companion object {
        const val ANTHROPIC_KEYS_URL = "https://console.anthropic.com/settings/keys"
        const val OPENAI_KEYS_URL = "https://platform.openai.com/api-keys"
    }
}
