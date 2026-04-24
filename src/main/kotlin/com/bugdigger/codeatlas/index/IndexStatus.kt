package com.bugdigger.codeatlas.index

import com.intellij.util.messages.Topic

/**
 * Message-bus event published by [CodeAtlasIndexService] as the index changes.
 *
 * UI components (status strip, badges) subscribe via the project message bus:
 * `project.messageBus.connect(disposable).subscribe(CODE_ATLAS_INDEX_TOPIC, listener)`.
 */
sealed interface IndexState {
    /** No chunks indexed yet. Initial state before the first build. */
    data object Empty : IndexState

    /** A full rebuild is in progress. [done]/[total] counts files walked. */
    data class BuildingFullIndex(val done: Int, val total: Int) : IndexState

    /** A file-level incremental update is in progress. [count] is pre-update. */
    data class Updating(val count: Int) : IndexState

    /** Ready to serve queries. [count] chunks indexed. */
    data class Ready(val count: Int) : IndexState
}

fun interface CodeAtlasIndexListener {
    fun stateChanged(state: IndexState)
}

val CODE_ATLAS_INDEX_TOPIC: Topic<CodeAtlasIndexListener> =
    Topic.create("CodeAtlas.Index", CodeAtlasIndexListener::class.java)
