package com.bugdigger.codeatlas.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class CodeAtlasSettingsConfigurable(private val project: Project) :
    SearchableConfigurable,
    Configurable.NoScroll {

    private val includeTestsCheckBox = JCheckBox("Include test sources in indexing")
    private val cacheDirField = JTextField()

    override fun getId(): String = "com.bugdigger.codeatlas.settings"

    override fun getDisplayName(): String = "CodeAtlas"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(includeTestsCheckBox)
        .addLabeledComponent("Cache directory override", cacheDirField)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun isModified(): Boolean {
        val settings = project.service<CodeAtlasSettingsService>()
        return includeTestsCheckBox.isSelected != settings.includeTestSources ||
            cacheDirField.text.trim().ifEmpty { null } != settings.cacheDirOverride
    }

    override fun apply() {
        val settings = project.service<CodeAtlasSettingsService>()
        settings.includeTestSources = includeTestsCheckBox.isSelected
        settings.cacheDirOverride = cacheDirField.text.trim().ifEmpty { null }
    }

    override fun reset() {
        val settings = project.service<CodeAtlasSettingsService>()
        includeTestsCheckBox.isSelected = settings.includeTestSources
        cacheDirField.text = settings.cacheDirOverride.orEmpty()
    }
}
