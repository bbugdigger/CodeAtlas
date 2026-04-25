package com.bugdigger.codeatlas.settings

import com.bugdigger.codeatlas.rag.LlmProvider
import com.bugdigger.codeatlas.rag.LlmProviderKind
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Project-level settings UI for CodeAtlas.
 *
 * Renders indexing preferences plus an LLM provider section with a per-provider
 * model field and (for hosted providers) an API key field backed by
 * [com.intellij.ide.passwordSafe.PasswordSafe].
 *
 * API key fields start empty and only write to PasswordSafe on [apply] when
 * the user has edited them, so re-opening the dialog never exposes stored keys.
 */
class CodeAtlasSettingsConfigurable(private val project: Project) :
    SearchableConfigurable,
    Configurable.NoScroll {

    private val includeTestsCheckBox = JCheckBox("Include test sources in indexing")
    private val cacheDirField = JTextField()

    private val providerCombo = JComboBox(LlmProviderKind.entries.toTypedArray())
    private val anthropicModelField = JTextField()
    private val anthropicKeyField = JBPasswordField()
    private val openAiModelField = JTextField()
    private val openAiKeyField = JBPasswordField()
    private val ollamaEndpointField = JTextField()
    private val ollamaModelField = JTextField()

    // Track whether the user edited a password field since last reset; only then do we write.
    private var anthropicKeyDirty = false
    private var openAiKeyDirty = false

    override fun getId(): String = "com.bugdigger.codeatlas.settings"

    override fun getDisplayName(): String = "CodeAtlas"

    override fun createComponent(): JComponent {
        anthropicKeyField.document.addDocumentListener(simpleListener { anthropicKeyDirty = true })
        openAiKeyField.document.addDocumentListener(simpleListener { openAiKeyDirty = true })

        return FormBuilder.createFormBuilder()
            .addComponent(includeTestsCheckBox)
            .addLabeledComponent("Cache directory override", cacheDirField)
            .addSeparator()
            .addLabeledComponent("LLM provider", providerCombo)
            .addLabeledComponent("Anthropic model", anthropicModelField)
            .addLabeledComponent("Anthropic API key", anthropicKeyField)
            .addLabeledComponent("OpenAI model", openAiModelField)
            .addLabeledComponent("OpenAI API key", openAiKeyField)
            .addLabeledComponent("Ollama endpoint", ollamaEndpointField)
            .addLabeledComponent("Ollama model", ollamaModelField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = project.service<CodeAtlasSettingsService>()
        return includeTestsCheckBox.isSelected != s.includeTestSources ||
            cacheDirField.text.trim().ifEmpty { null } != s.cacheDirOverride ||
            providerCombo.selectedItem != s.llmProvider ||
            anthropicModelField.text.trim() != s.anthropicModel ||
            openAiModelField.text.trim() != s.openAiModel ||
            ollamaEndpointField.text.trim() != s.ollamaEndpoint ||
            ollamaModelField.text.trim() != s.ollamaModel ||
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
    }

    private fun tooltipFor(current: String?): String =
        if (current.isNullOrBlank()) "No key stored" else "Key stored in PasswordSafe; leave blank to keep"

    private inline fun simpleListener(crossinline onChange: () -> Unit) =
        object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange()
        }
}
