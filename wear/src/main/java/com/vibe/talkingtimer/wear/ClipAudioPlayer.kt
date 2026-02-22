package com.vibe.talkingtimer.wear

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.coroutines.resume

class ClipAudioPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val queue = Channel<List<String>>(Channel.UNLIMITED)
    private val localeSearchPaths: List<String> = buildLocaleSearchPaths()
    private val worker: Job = scope.launch(Dispatchers.Main.immediate) {
        while (isActive) {
            val tokens = queue.receive()
            for (token in tokens) {
                playToken(token)
                delay(50)
            }
        }
    }

    fun playTokens(tokens: List<String>) {
        if (tokens.isEmpty()) return
        scope.launch {
            queue.send(tokens)
        }
    }

    suspend fun shutdown() {
        queue.close()
        worker.cancelAndJoin()
    }

    private suspend fun playToken(token: String) {
        val afd = openTokenAsset(token) ?: return

        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            player.setOnCompletionListener {
                try {
                    it.reset()
                    it.release()
                } finally {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                try {
                    mp.reset()
                    mp.release()
                } finally {
                    if (cont.isActive) cont.resume(Unit)
                }
                true
            }
            try {
                afd.use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                player.prepare()
                player.start()
            } catch (_: Exception) {
                player.release()
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation {
                try {
                    player.stop()
                } catch (_: Exception) {
                }
                player.release()
            }
        }
    }

    private fun openTokenAsset(token: String) = localeSearchPaths.firstNotNullOfOrNull { prefix ->
        val path = if (prefix.isEmpty()) "audio/$token.mp3" else "audio/$prefix/$token.mp3"
        try {
            context.assets.openFd(path)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun buildLocaleSearchPaths(): List<String> {
        val tag = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
        val language = Locale.getDefault().language.ifBlank { "en" }
        return buildList {
            add(tag)
            if (language != tag) add(language)
            add("en-US")
            add("en")
            add("")
        }.distinct()
    }
}
