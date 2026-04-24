package com.bugdigger.codeatlas.language

import com.bugdigger.codeatlas.index.ChunkKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JavaLanguageAdapterTest : BasePlatformTestCase() {

    fun testExtractsClassConstructorAndMethod() {
        val file = myFixture.configureByText(
            "AuthService.java",
            """
            package demo;
            /** Handles authentication. */
            public class AuthService {
                public AuthService() {}
                /** Log in. */
                public boolean login(String user, String pass) { return true; }
                public void logout() {}
            }
            """.trimIndent(),
        )

        val chunks = JavaLanguageAdapter().extract(file)

        val kinds = chunks.map { it.kind }.toSet()
        assertTrue("expected CLASS kind, got $kinds", kinds.contains(ChunkKind.CLASS))
        assertTrue("expected CONSTRUCTOR kind, got $kinds", kinds.contains(ChunkKind.CONSTRUCTOR))
        assertTrue("expected METHOD kind, got $kinds", kinds.contains(ChunkKind.METHOD))

        val classChunk = chunks.single { it.qualifiedName == "demo.AuthService" }
        assertNotNull("AuthService should carry its JavaDoc", classChunk.docComment)

        val loginChunk = chunks.single { it.qualifiedName == "demo.AuthService.login" }
        assertEquals(ChunkKind.METHOD, loginChunk.kind)
        assertTrue(loginChunk.signature.contains("login"))
        assertTrue(loginChunk.signature.contains("boolean"))
    }

    fun testInterfaceAndEnumKinds() {
        val file = myFixture.configureByText(
            "Types.java",
            """
            package demo;
            interface Repo { String find(String id); }
            enum Status { OK, FAIL }
            """.trimIndent(),
        )
        val chunks = JavaLanguageAdapter().extract(file)
        assertEquals(ChunkKind.INTERFACE, chunks.single { it.qualifiedName == "demo.Repo" }.kind)
        assertEquals(ChunkKind.ENUM, chunks.single { it.qualifiedName == "demo.Status" }.kind)
    }
}
