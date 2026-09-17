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

/**
 * Owns the loaded LiteRT-LM engine for serving.
 *
 * Only one inference may run at a time: a `Conversation` is not safe to drive concurrently, and
 * cancelling does not roll back the KV cache. Every request therefore takes [lock] and starts by
 * resetting the conversation, which makes each HTTP request independent — the same contract the
 * OpenAI API has, where the client sends the whole message list every time.
 */
object ModelHost {
  private val lock = Mutex()

  @Volatile private var loadedName: String = ""

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
      Log.d(TAG, "Loaded '${model.name}' (image=${model.supportImage} audio=${model.supportAudio})")
    }
  }

  suspend fun unload(model: Model?) {
    lock.withLock {
      if (model != null && model.instance != null) {
        LlmChatModelHelper.cleanUp(model) {}
      }
      loadedName = ""
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
    try {
      // Fresh conversation per request: the client owns the history, and a cancelled
      // generation would otherwise leave stale tokens in the KV cache.
      LlmChatModelHelper.resetConversation(
        model = model,
        supportImage = model.supportImage,
        supportAudio = model.supportAudio,
        systemInstruction = if (systemPrompt.isBlank()) null else Contents.of(systemPrompt),
        initialMessages =
          history.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
      )

      LlmChatModelHelper.runInference(
        model = model,
        input = userText,
        resultListener = { partialResult, done, _ ->
          if (partialResult.isNotEmpty()) trySend(partialResult)
          if (done) close()
        },
        cleanUpListener = {},
        onError = { message -> close(IllegalStateException(message)) },
        audioClips = audioClips,
      )
    } catch (e: Throwable) {
      close(e)
    }

    awaitClose {
      // Collector gone (client disconnected or an error): stop the native generation,
      // which the Flow itself does not do.
      runCatching { LlmChatModelHelper.stopResponse(model) }
      if (lock.isLocked) runCatching { lock.unlock() }
    }
  }
}
