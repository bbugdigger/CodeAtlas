package com.bugdigger.codeatlas.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CodeAtlasSettings", storages = [Storage("codeAtlas.xml")])
@Service(Service.Level.PROJECT)
class CodeAtlasSettingsService :
    SerializablePersistentStateComponent<CodeAtlasSettingsService.SettingsState>(SettingsState()) {

    data class SettingsState(
        val includeTestSources: Boolean = false,
        val cacheDirOverride: String = "",
    )

    var includeTestSources: Boolean
        get() = state.includeTestSources
        set(value) {
            updateState { it.copy(includeTestSources = value) }
        }

    var cacheDirOverride: String?
        get() = state.cacheDirOverride.trim().ifEmpty { null }
        set(value) {
            updateState { it.copy(cacheDirOverride = value?.trim().orEmpty()) }
        }
}
