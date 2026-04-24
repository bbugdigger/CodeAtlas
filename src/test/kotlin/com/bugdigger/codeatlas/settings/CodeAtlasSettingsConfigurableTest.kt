package com.bugdigger.codeatlas.settings

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JCheckBox
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

    private fun checkBox(configurable: CodeAtlasSettingsConfigurable, fieldName: String): JCheckBox {
        val field = CodeAtlasSettingsConfigurable::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(configurable) as JCheckBox
    }

    private fun textField(configurable: CodeAtlasSettingsConfigurable, fieldName: String): JTextField {
        val field = CodeAtlasSettingsConfigurable::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(configurable) as JTextField
    }
}
