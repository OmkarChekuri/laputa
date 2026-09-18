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
import com.google.ai.edge.gallery.data.Model
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_SERVER_PORT = 11435

private const val PREFS = "laputa_server"
private const val KEY_PORT = "port"
private const val KEY_TOKEN = "token"
private const val KEY_MODEL = "model_name"
private const val KEY_KNOWN = "known_models"

/** A downloaded model, as remembered for the widget's picker (no live `Model` needed). */
data class KnownModel(val name: String, val audio: Boolean)

data class ServerState(
  val running: Boolean = false,
  val starting: Boolean = false,
  val port: Int = DEFAULT_SERVER_PORT,
  val modelName: String = "",
  val error: String = "",
  val requestCount: Int = 0,
  val lastActivity: String = "",
)

/**
 * Bridge between the UI and the server service.
 *
 * The service needs live [Model] objects, but the list of downloaded models lives in
 * `ModelManagerViewModel`, which is UI-scoped and holds non-serializable state (`model.instance`).
 * Rather than reach into the ViewModel from a Service, the screen publishes the servable models
 * here and the service reads them. Keeps the upstream ViewModel untouched.
 */
object ServerRegistry {
  /** Downloaded models the UI considers servable. Published by ServerScreen. */
  @Volatile var candidates: List<Model> = emptyList()

  /** The model the service should load. Set before starting the service. */
  @Volatile var selected: Model? = null

  private val _state = MutableStateFlow(ServerState())
  val state: StateFlow<ServerState> = _state.asStateFlow()

  /** Set by the app, service, widget and tile, so state changes can refresh the widget. */
  @Volatile var appContext: Context? = null

  /** A Start tapped on the widget or tile, waiting for the app's model list. */
  val pendingStart = MutableStateFlow(false)

  fun update(transform: (ServerState) -> ServerState) {
    _state.value = transform(_state.value)
    appContext?.let { ServerControls.refresh(it) }
  }

  fun noteActivity(line: String) {
    _state.value =
      _state.value.copy(requestCount = _state.value.requestCount + 1, lastActivity = line)
  }

  fun port(context: Context): Int =
    prefs(context).getInt(KEY_PORT, DEFAULT_SERVER_PORT)

  fun setPort(context: Context, port: Int) {
    prefs(context).edit().putInt(KEY_PORT, port).apply()
  }

  /** Bearer token clients must present. Generated once and kept on the device. */
  fun token(context: Context): String {
    val existing = prefs(context).getString(KEY_TOKEN, null)
    if (!existing.isNullOrEmpty()) return existing
    val fresh = generateToken()
    prefs(context).edit().putString(KEY_TOKEN, fresh).apply()
    return fresh
  }

  fun regenerateToken(context: Context): String {
    val fresh = generateToken()
    prefs(context).edit().putString(KEY_TOKEN, fresh).apply()
    return fresh
  }

  /** Remembered so the screen can preselect the model served last time. */
  fun lastModelName(context: Context): String =
    prefs(context).getString(KEY_MODEL, "") ?: ""

  fun setLastModelName(context: Context, name: String) {
    prefs(context).edit().putString(KEY_MODEL, name).apply()
  }

  /** Downloaded models, as last seen by the app: what the widget's picker offers. */
  fun knownModels(context: Context): List<KnownModel> =
    (prefs(context).getString(KEY_KNOWN, "") ?: "")
      .lines()
      .filter { it.isNotBlank() }
      .map { KnownModel(name = it.substringBeforeLast('|'), audio = it.endsWith("|1")) }

  fun setKnownModels(context: Context, models: List<KnownModel>) {
    val encoded = models.joinToString("\n") { it.name + "|" + (if (it.audio) "1" else "0") }
    if (encoded == prefs(context).getString(KEY_KNOWN, "")) return
    prefs(context).edit().putString(KEY_KNOWN, encoded).apply()
    ServerControls.refresh(context)
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun generateToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }
}
