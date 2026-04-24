package com.bugdigger.codeatlas.language

import java.security.MessageDigest

internal fun sha256Hex(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()
