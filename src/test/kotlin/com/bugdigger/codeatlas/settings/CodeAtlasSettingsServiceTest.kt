package com.bugdigger.codeatlas.settings

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CodeAtlasSettingsServiceTest : BasePlatformTestCase() {

    fun testCacheDirWhitespaceMapsToNull() {
        val service = project.service<CodeAtlasSettingsService>()
        service.loadState(CodeAtlasSettingsService.SettingsState())

        service.cacheDirOverride = "   "

        assertNull(service.cacheDirOverride)
    }

    fun testPersistsSetValuesInMemoryState() {
        val service = project.service<CodeAtlasSettingsService>()
        service.loadState(CodeAtlasSettingsService.SettingsState())

        service.includeTestSources = true
        service.cacheDirOverride = "C:/tmp/codeatlas-cache"

        assertTrue(service.includeTestSources)
        assertEquals("C:/tmp/codeatlas-cache", service.cacheDirOverride)
    }
}
