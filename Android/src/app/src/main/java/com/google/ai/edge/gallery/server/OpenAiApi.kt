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

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A chat request reduced to what the on-device runtime needs. */
data class ParsedChatRequest(
  val model: String,
  val stream: Boolean,
  val systemPrompt: String,
  val history: List<HistoryTurn>,
  val userText: String,
  val audioClips: List<ByteArray>,
  val imageCount: Int,
)

class BadRequestException(message: String) : Exception(message)

/**
 * Parses an OpenAI Chat Completions body.
 *
 * `content` may be a bare string or an array of parts; audio arrives as
 * `{"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}`. The last user
 * message becomes the prompt; earlier turns are replayed as conversation history.
 */
fun parseChatRequest(root: JsonObject): ParsedChatRequest {
  val model = root["model"]?.jsonPrimitive?.content.orEmpty()
  val stream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false
  val messages =
    (root["messages"] as? JsonArray) ?: throw BadRequestException("'messages' must be an array")
  if (messages.isEmpty()) throw BadRequestException("'messages' must not be empty")

  val systemParts = mutableListOf<String>()
  val turns = mutableListOf<HistoryTurn>()
  val audio = mutableListOf<ByteArray>()
  var images = 0

  for (element in messages) {
    val message = element.jsonObject
    val role = message["role"]?.jsonPrimitive?.content.orEmpty()
    val text = StringBuilder()

    when (val content = message["content"]) {
      is JsonPrimitive -> text.append(content.content.orEmpty())
      is JsonArray ->
        for (partElement in content) {
          val part = partElement.jsonObject
          when (part["type"]?.jsonPrimitive?.content) {
            "text" -> text.append(part["text"]?.jsonPrimitive?.content.orEmpty())
            "input_audio" -> {
              val data =
                part["input_audio"]?.jsonObject?.get("data")?.jsonPrimitive?.content
                  ?: throw BadRequestException("input_audio is missing 'data'")
              val format =
                part["input_audio"]?.jsonObject?.get("format")?.jsonPrimitive?.content
                  ?: "wav"
              if (format != "wav" && format != "mp3" && format != "flac") {
                throw BadRequestException(
                  "Unsupported audio format '$format'. The on-device decoder accepts wav, mp3 or flac."
                )
              }
              audio.add(decodeBase64(data))
            }
            // Images are accepted by the schema but not yet forwarded to the runtime.
            "image_url" -> images++
            else -> Unit
          }
        }
      else -> Unit
    }

    when (role) {
      "system", "developer" -> systemParts.add(text.toString())
      "user" -> turns.add(HistoryTurn(fromUser = true, text = text.toString()))
      "assistant" -> turns.add(HistoryTurn(fromUser = false, text = text.toString()))
      else -> Unit
    }
  }

  val lastUser = turns.indexOfLast { it.fromUser }
  if (lastUser == -1) throw BadRequestException("no 'user' message found")
  val userText = turns[lastUser].text
  val history = turns.subList(0, lastUser).toList()

  return ParsedChatRequest(
    model = model,
    stream = stream,
    systemPrompt = systemParts.joinToString("\n\n").trim(),
    history = history,
    userText = userText,
    audioClips = audio,
    imageCount = images,
  )
}


private fun decodeBase64(data: String): ByteArray =
  try {
    // Tolerate data URLs and whitespace/newlines from clients.
    val payload = data.substringAfterLast(",").filterNot { it.isWhitespace() }
    Base64.getDecoder().decode(payload)
  } catch (e: IllegalArgumentException) {
    throw BadRequestException("input_audio 'data' is not valid base64")
  }

// ---- Response shapes ----

@Serializable
data class ChatMessageOut(val role: String = "assistant", val content: String)

@Serializable
data class ChoiceOut(
  val index: Int = 0,
  val message: ChatMessageOut,
  @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class UsageOut(
  @SerialName("prompt_tokens") val promptTokens: Int = 0,
  @SerialName("completion_tokens") val completionTokens: Int = 0,
  @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ChatCompletionOut(
  val id: String,
  val `object`: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<ChoiceOut>,
  val usage: UsageOut = UsageOut(),
)

@Serializable data class DeltaOut(val role: String? = null, val content: String? = null)

@Serializable
data class ChunkChoiceOut(
  val index: Int = 0,
  val delta: DeltaOut,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatChunkOut(
  val id: String,
  val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChunkChoiceOut>,
)

@Serializable
data class ModelEntryOut(
  val id: String,
  val `object`: String = "model",
  val created: Long = 0,
  @SerialName("owned_by") val ownedBy: String = "laputa",
  // Laputa extensions so clients can tell what the model accepts.
  @SerialName("supports_audio") val supportsAudio: Boolean = false,
  @SerialName("supports_image") val supportsImage: Boolean = false,
  @SerialName("loaded") val loaded: Boolean = false,
)

@Serializable
data class ModelListOut(val `object`: String = "list", val data: List<ModelEntryOut>)

@Serializable data class ErrorBody(val message: String, val type: String = "invalid_request_error")

@Serializable data class ErrorOut(val error: ErrorBody)

@Serializable
data class HealthOut(val status: String = "ok", val model: String, val port: Int)
