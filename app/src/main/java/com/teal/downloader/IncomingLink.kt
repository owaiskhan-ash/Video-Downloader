package com.teal.downloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands a link from an Intent to the UI.
 *
 * Needed because a shared Intent (or a VIEW on an http URL) can arrive while the
 * activity is already running, via onNewIntent, long after composition happened.
 * A one-slot flow is the simplest thing that survives both paths.
 */
object IncomingLink {
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    fun publish(value: String?) {
        val trimmed = value?.trim()
        if (!trimmed.isNullOrEmpty()) _url.value = trimmed
    }

    fun consume() {
        _url.value = null
    }
}
