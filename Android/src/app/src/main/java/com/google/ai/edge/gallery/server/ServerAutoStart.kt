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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay

/** How long to wait for download statuses after the model list loads before giving up. */
private const val NO_MODELS_GRACE_MS = 3000L
/** Longest wait for a running server to stop when switching models. */
private const val SWITCH_TIMEOUT_MS = 8000L

/**
 * Lives on the home screen. Keeps the widget's list of downloaded models current, and carries
 * out a Start tapped on the widget or tile: once the model list is ready it starts the server
 * with the chosen model and moves Laputa back behind whatever the user was doing.
 */
@Composable
fun ServerAutoStart(modelManagerViewModel: ModelManagerViewModel, onNeedsModel: () -> Unit) {
  val context = LocalContext.current
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val pending by ServerRegistry.pendingStart.collectAsState()

  val downloaded: List<Model> =
    remember(uiState.modelDownloadStatus, modelManagerViewModel.allowlistModels) {
      modelManagerViewModel.allowlistModels.filter {
        uiState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
      }
    }

  LaunchedEffect(downloaded) {
    ServerRegistry.appContext = context.applicationContext
    if (downloaded.isNotEmpty()) {
      ServerRegistry.candidates = downloaded
      ServerRegistry.setKnownModels(
        context,
        downloaded.map { KnownModel(name = it.name, audio = it.supportAudio) },
      )
    }
  }

  LaunchedEffect(pending, uiState.loadingModelAllowlist, downloaded) {
    if (!pending || uiState.loadingModelAllowlist) return@LaunchedEffect
    if (downloaded.isEmpty()) {
      // Statuses can land just after the list; restarted if they do.
      delay(NO_MODELS_GRACE_MS)
      ServerRegistry.pendingStart.value = false
      onNeedsModel()
      return@LaunchedEffect
    }

    val model =
      downloaded.firstOrNull { it.name == ServerRegistry.lastModelName(context) }
        ?: downloaded.first()
    var state = ServerRegistry.state.value
    val busy = state.running || state.starting
    if (busy && state.modelName != model.name) {
      // Switching models: stop the current one and wait for it to wind down.
      LlmServerService.stop(context)
      var waited = 0L
      while ((state.running || state.starting) && waited < SWITCH_TIMEOUT_MS) {
        delay(100)
        waited += 100
        state = ServerRegistry.state.value
      }
    }
    if (!state.running && !state.starting) {
      ServerRegistry.candidates = downloaded
      ServerRegistry.selected = model
      ServerRegistry.setLastModelName(context, model.name)
      LlmServerService.start(context)
      Toast.makeText(context, "Starting ${model.name}…", Toast.LENGTH_SHORT).show()
    }
    // Back to the home screen (or wherever the user came from); the server keeps running.
    context.findActivity()?.moveTaskToBack(true)
    // Cleared last: it's a key of this effect, so clearing it earlier would cancel the work.
    ServerRegistry.pendingStart.value = false
  }
}

private fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}
