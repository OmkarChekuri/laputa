/*
 * Copyright 2026 The Laputa contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "LaputaModelHost"

/** A prior turn replayed into the conversation so requests stay stateless. */
data class HistoryTurn(val fromUser: Boolean, val text: String)

/** Exactly what the live conversation holds, so a request that continues it can reuse it. */
private data class LiveState(val systemPrompt: String, val turns: List<HistoryTurn>)

/**
 * Owns the loaded LiteRT-LM engine for serving.
 *
 * Only one inference may run at a time: a `Conversation` is not safe to drive concurrently, and
 * cancelling does not roll back the KV cache. Every request takes [lock].
 *
 * Requests are stateless, as in the OpenAI API: the client sends the whole message list every
 * time. But re-reading a long system prompt (instructions plus documents) costs seconds on a
 * phone, so when a request exactly continues the live conversation — same system prompt, and its
 * history is everything already said — only the new message is sent, and the engine keeps what it
 * has already read. Anything else, or any doubt (audio, a cancelled or failed turn), starts a
 * fresh conversation. The engine allows only one live conversation, so this is a single slot.
 */
object ModelHost {
  private val lock = Mutex()

  @Volatile private var loadedName: String = ""

  /** What the live conversation contains; null when unknown or not reusable. */
  @Volatile private var live: LiveState? = null

  val loadedModelName: String
    get() = loadedName

  /** Loads [model] if it isn't already the loaded one. Blocking and slow (~10s); call off-main. */
  suspend fun ensureLoaded(context: Context, model: Model, taskId: String) {
    lock.withLock {
      if (loadedName == model.name && model.instance != null) return
      if (loadedName.isNotEmpty()) unloadLocked()

      val done = CompletableDeferred<String>()
      LlmChatModelHelper.initialize(
        context = context,
        model = model,
        taskId = taskId,
        supportImage = model.supportImage,
        supportAudio = model.supportAudio,
        onDone = { error -> done.complete(error) },
      )
      val error = done.await()
      if (error.isNotEmpty()) throw IllegalStateException(error)
      loadedName = model.name
      live = null
      Log.d(TAG, "Loaded '${model.name}' (image=${model.supportImage} audio=${model.supportAudio})")
    }
  }

  suspend fun unload(model: Model?) {
    lock.withLock {
      if (model != null && model.instance != null) {
        LlmChatModelHelper.cleanUp(model) {}
      }
      loadedName = ""
      live = null
    }
  }

  private fun unloadLocked() {
    val previous = ServerRegistry.candidates.firstOrNull { it.name == loadedName }
    if (previous?.instance != null) {
      LlmChatModelHelper.cleanUp(previous) {}
    }
    loadedName = ""
  }

  /**
   * Runs one request and emits the reply as deltas (`resultListener` hands back increments, which
   * the caller concatenates). Holds [lock] for the whole generation.
   */
  fun generate(
    model: Model,
    systemPrompt: String,
    history: List<HistoryTurn>,
    userText: String,
    audioClips: List<ByteArray>,
  ): Flow<String> = callbackFlow {
    lock.lock()
    val reply = StringBuilder()
    var finished = false
    try {
      val current = live
      val continues =
        audioClips.isEmpty() &&
          current != null &&
          current.systemPrompt == systemPrompt &&
          current.turns == history
      // Until this turn completes cleanly, the conversation's contents are unknown.
      live = null
      if (continues) {
        Log.d(TAG, "Continuing the live conversation (${history.size} turns already read)")
      } else {
        LlmChatModelHelper.resetConversation(
          model = model,
          supportImage = model.supportImage,
          supportAudio = model.supportAudio,
          systemInstruction = if (systemPrompt.isBlank()) null else Contents.of(systemPrompt),
          initialMessages =
            history.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
        )
      }

      LlmChatModelHelper.runInference(
        model = model,
        input = userText,
        resultListener = { partialResult, done, _ ->
          if (partialResult.isNotEmpty()) {
            reply.append(partialResult)
            trySend(partialResult)
          }
          if (done) {
            finished = true
            // A text turn that ran to completion can be continued by the next request.
            // (A cancelled turn also reports done, but the collector has gone by then and
            // awaitClose has already marked the state unknown.)
            if (audioClips.isEmpty() && !isClosedForSend) {
              live =
                LiveState(
                  systemPrompt,
                  history + HistoryTurn(true, userText) + HistoryTurn(false, reply.toString()),
                )
            }
            close()
          }
        },
        cleanUpListener = {},
        onError = { message -> close(IllegalStateException(message)) },
        audioClips = audioClips,
      )
    } catch (e: Throwable) {
      close(e)
    }

    awaitClose {
      if (!finished) {
        // Collector gone mid-generation (client disconnected or an error): stop the native
        // generation, which the Flow itself does not do. The KV cache now holds a partial
        // turn, so the next request must start afresh.
        live = null
        runCatching { LlmChatModelHelper.stopResponse(model) }
      }
      if (lock.isLocked) runCatching { lock.unlock() }
    }
  }
}
