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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val TAG = "LaputaServer"
private const val TASK_ID = "llm_chat"

/**
 * An OpenAI-compatible HTTP API over the on-device model, bound to the loopback interface so only
 * apps on this phone can reach it. A bearer token is required because any local app can open a
 * loopback socket.
 */
class LlmServer(
  private val context: Context,
  private val port: Int,
  private val token: String,
) {
  private var server: EmbeddedServer<*, *>? = null
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  fun start() {
    server = embeddedServer(CIO, port = port, host = LOOPBACK) { routes() }
    server?.start(wait = false)
    Log.i(TAG, "Serving on http://" + LOOPBACK + ":" + port)
  }

  fun stop() {
    runCatching { server?.stop(gracePeriodMillis = 300, timeoutMillis = 1500) }
    server = null
    Log.i(TAG, "Stopped")
  }

  private fun Application.routes() {
    routing {
      get("/health") {
        val body =
          json.encodeToString(
            HealthOut(model = ModelHost.loadedModelName, port = port)
          )
        call.respondJson(body)
      }

      get("/v1/models") {
        if (!call.authorize()) return@get
        val loaded = ModelHost.loadedModelName
        val entries =
          servableModels().map {
            ModelEntryOut(
              id = it.name,
              supportsAudio = it.supportAudio,
              supportsImage = it.supportImage,
              loaded = it.name == loaded,
            )
          }
        call.respondJson(json.encodeToString(ModelListOut(data = entries)))
      }

      post("/v1/chat/completions") {
        if (!call.authorize()) return@post
        handleChat(call)
      }
    }
  }

  private suspend fun handleChat(call: ApplicationCall) {
    val request =
      try {
        parseChatRequest(json.parseToJsonElement(call.receiveText()).jsonObject)
      } catch (e: BadRequestException) {
        return call.respondError(HttpStatusCode.BadRequest, e.message ?: "bad request")
      } catch (e: Exception) {
        return call.respondError(HttpStatusCode.BadRequest, "Malformed JSON body: " + e.message)
      }

    val model =
      ServerRegistry.selected
        ?: return call.respondError(
          HttpStatusCode.ServiceUnavailable,
          "No model is being served. Pick one in Laputa and start the server.",
        )

    // One model is served at a time; be explicit rather than silently answering with another.
    if (request.model.isNotEmpty() && request.model != model.name) {
      return call.respondError(
        HttpStatusCode.NotFound,
        "Model '" + request.model + "' is not being served. Currently serving '" + model.name + "'.",
      )
    }
    if (request.audioClips.isNotEmpty() && !model.supportAudio) {
      return call.respondError(
        HttpStatusCode.BadRequest,
        "Model '" +
          model.name +
          "' does not accept audio. Serve an audio-capable model (e.g. Gemma 4).",
      )
    }

    try {
      ModelHost.ensureLoaded(context, model, TASK_ID)
    } catch (e: Exception) {
      return call.respondError(
        HttpStatusCode.InternalServerError,
        "Could not load '" + model.name + "': " + e.message,
      )
    }

    ServerRegistry.noteActivity(
      (if (request.stream) "stream" else "chat") +
        (if (request.audioClips.isNotEmpty()) " +audio" else "")
    )

    val deltas =
      ModelHost.generate(
        model = model,
        systemPrompt = request.systemPrompt,
        history = request.history,
        userText = request.userText,
        audioClips = request.audioClips,
      )
    val id = "chatcmpl-" + System.currentTimeMillis()
    val created = System.currentTimeMillis() / 1000

    if (!request.stream) {
      val text =
        try {
          deltas.toList().joinToString("")
        } catch (e: Exception) {
          return call.respondError(
            HttpStatusCode.InternalServerError,
            "Generation failed: " + e.message,
          )
        }
      return call.respondJson(
        json.encodeToString(
          ChatCompletionOut(
            id = id,
            created = created,
            model = model.name,
            choices = listOf(ChoiceOut(message = ChatMessageOut(content = text))),
          )
        )
      )
    }

    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
      try {
        writeEvent(json.encodeToString(chunk(id, created, model.name, DeltaOut(role = "assistant"))))
        deltas.collect { delta ->
          writeEvent(json.encodeToString(chunk(id, created, model.name, DeltaOut(content = delta))))
        }
        writeEvent(
          json.encodeToString(chunk(id, created, model.name, DeltaOut(), finishReason = "stop"))
        )
      } catch (e: Exception) {
        Log.e(TAG, "stream failed", e)
        writeEvent(
          json.encodeToString(ErrorOut(ErrorBody(e.message ?: "generation failed")))
        )
      }
      writeEvent("[DONE]")
    }
  }

  private fun chunk(
    id: String,
    created: Long,
    modelName: String,
    delta: DeltaOut,
    finishReason: String? = null,
  ) =
    ChatChunkOut(
      id = id,
      created = created,
      model = modelName,
      choices = listOf(ChunkChoiceOut(delta = delta, finishReason = finishReason)),
    )

  /** Models the UI published as downloaded, plus whatever is currently selected. */
  private fun servableModels(): List<Model> {
    val selected = ServerRegistry.selected
    val all = ServerRegistry.candidates
    return if (selected != null && all.none { it.name == selected.name }) all + selected else all
  }

  private suspend fun ApplicationCall.authorize(): Boolean {
    val header = request.headers["Authorization"].orEmpty()
    val provided = header.removePrefix("Bearer ").trim()
    if (token.isNotEmpty() && provided != token) {
      respondError(HttpStatusCode.Unauthorized, "Invalid or missing bearer token.")
      return false
    }
    return true
  }

  private suspend fun ApplicationCall.respondJson(body: String) =
    respondText(body, ContentType.Application.Json)

  private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respondText(
      json.encodeToString(ErrorOut(ErrorBody(message))),
      ContentType.Application.Json,
      status,
    )

  companion object {
    const val LOOPBACK = "127.0.0.1"
  }
}

/** SSE frame: `data: <payload>` followed by a blank line, flushed so clients see it promptly. */
private fun java.io.Writer.writeEvent(payload: String) {
  write("data: ")
  write(payload)
  write("\n\n")
  flush()
}
