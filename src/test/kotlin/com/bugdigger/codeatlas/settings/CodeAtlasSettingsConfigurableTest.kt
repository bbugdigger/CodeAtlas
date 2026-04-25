package com.bugdigger.codeatlas.settings

import com.bugdigger.codeatlas.rag.LlmProviderKind
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBPasswordField
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JTextField

class CodeAtlasSettingsConfigurableTest : BasePlatformTestCase() {

    fun testConfigurableIdAndDisplayName() {
        val configurable = CodeAtlasSettingsConfigurable(project)

        assertEquals("com.bugdigger.codeatlas.settings", configurable.id)
        assertEquals("CodeAtlas", configurable.displayName)
    }

    fun testApplyWritesValuesFromUiToService() {
        val service = project.service<CodeAtlasSettingsService>()
        service.loadState(CodeAtlasSettingsService.SettingsState())

        val configurable = CodeAtlasSettingsConfigurable(project)
        configurable.createComponent()
        configurable.reset()

        checkBox(configurable, "includeTestsCheckBox").isSelected = true
        textField(configurable, "cacheDirField").text = "C:/cache"

        configurable.apply()

        assertTrue(service.includeTestSources)
        assertEquals("C:/cache", service.cacheDirOverride)
    }

    fun testApplyWritesLlmProviderAndModels() {
        val service = project.service<CodeAtlasSettingsService>()
        service.loadState(CodeAtlasSettingsService.SettingsState())

        val configurable = CodeAtlasSettingsConfigurable(project)
        configurable.createComponent()
        configurable.reset()

        @Suppress("UNCHECKED_CAST")
        val combo = field(configurable, "providerCombo") as JComboBox<LlmProviderKind>
        combo.selectedItem = LlmProviderKind.OPENAI
        textField(configurable, "openAiModelField").text = "gpt-4o"
        textField(configurable, "ollamaEndpointField").text = "http://localhost:9999"
        textField(configurable, "ollamaModelField").text = "qwen2.5-coder"

        configurable.apply()

        assertEquals(LlmProviderKind.OPENAI, service.llmProvider)
        assertEquals("gpt-4o", service.openAiModel)
        assertEquals("http://localhost:9999", service.ollamaEndpoint)
        assertEquals("qwen2.5-coder", service.ollamaModel)
    }

    fun testApplyPersistsEditedApiKeyAndClearsField() {
        val service = project.service<CodeAtlasSettingsService>()
        service.loadState(CodeAtlasSettingsService.SettingsState())
        service.setApiKey(LlmProviderKind.ANTHROPIC, null)

        val configurable = CodeAtlasSettingsConfigurable(project)
        configurable.createComponent()
        configurable.reset()

        val keyField = passwordField(configurable, "anthropicKeyField")
        keyField.text = "sk-ant-abc123"

        assertTrue("isModified should be true after key edit", configurable.isModified)

        configurable.apply()

        assertEquals("sk-ant-abc123", service.getApiKey(LlmProviderKind.ANTHROPIC))
        assertEquals("", String(keyField.password))
        assertFalse("isModified should reset after apply", configurable.isModified)

        // Cleanup.
        service.setApiKey(LlmProviderKind.ANTHROPIC, null)
    }

    fun testResetLeavesApiKeyFieldEmptyEvenWhenStored() {
        val service = project.service<CodeAtlasSettingsService>()
        service.loadState(CodeAtlasSettingsService.SettingsState())
        service.setApiKey(LlmProviderKind.OPENAI, "sk-preexisting")

        val configurable = CodeAtlasSettingsConfigurable(project)
        configurable.createComponent()
        configurable.reset()

        val keyField = passwordField(configurable, "openAiKeyField")
        assertEquals("", String(keyField.password))
        assertFalse(configurable.isModified)

        service.setApiKey(LlmProviderKind.OPENAI, null)
    }

    private fun checkBox(configurable: CodeAtlasSettingsConfigurable, fieldName: String): JCheckBox =
        field(configurable, fieldName) as JCheckBox

    private fun textField(configurable: CodeAtlasSettingsConfigurable, fieldName: String): JTextField =
        field(configurable, fieldName) as JTextField

    private fun passwordField(configurable: CodeAtlasSettingsConfigurable, fieldName: String): JBPasswordField =
        field(configurable, fieldName) as JBPasswordField

    private fun field(configurable: CodeAtlasSettingsConfigurable, fieldName: String): Any {
        val f = CodeAtlasSettingsConfigurable::class.java.getDeclaredField(fieldName)
        f.isAccessible = true
        return f.get(configurable)
    }
}
