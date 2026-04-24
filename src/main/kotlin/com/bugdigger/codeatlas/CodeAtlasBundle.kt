package com.bugdigger.codeatlas

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE = "messages.CodeAtlasBundle"

internal object CodeAtlasBundle {
    private val instance = DynamicBundle(CodeAtlasBundle::class.java, BUNDLE)

    @JvmStatic
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any?,
    ): String = instance.getMessage(key, *params)

    @JvmStatic
    fun lazyMessage(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any?,
    ): Supplier<String> = instance.getLazyMessage(key, *params)
}
