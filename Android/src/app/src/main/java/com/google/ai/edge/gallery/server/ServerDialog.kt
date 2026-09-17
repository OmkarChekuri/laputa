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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Start/stop the local model server and show other apps how to reach it.
 *
 * The downloaded-model list lives in the UI layer, so this screen publishes it to
 * [ServerRegistry] for the service to pick up.
 */
@Composable
fun ServerDialog(modelManagerViewModel: ModelManagerViewModel, onDismissed: () -> Unit) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val serverState by ServerRegistry.state.collectAsState()

  // Only models whose files are actually on the device can be served.
  val downloaded: List<Model> =
    remember(uiState.modelDownloadStatus, modelManagerViewModel.allowlistModels) {
      modelManagerViewModel.allowlistModels.filter {
        uiState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
      }
    }

  var selected by remember {
    mutableStateOf(
      downloaded.firstOrNull { it.name == ServerRegistry.lastModelName(context) }
        ?: ServerRegistry.selected
        ?: downloaded.firstOrNull()
    )
  }
  var menuOpen by remember { mutableStateOf(false) }

  // Keep the service's view of the world in sync with the UI.
  LaunchedEffect(downloaded, selected) {
    ServerRegistry.candidates = downloaded
    if (!serverState.running) ServerRegistry.selected = selected
  }

  val token = remember { ServerRegistry.token(context) }
  val port = ServerRegistry.port(context)
  val baseUrl = "http://" + LlmServer.LOOPBACK + ":" + port + "/v1"
  val running = serverState.running
  val busy = serverState.starting

  AlertDialog(
    onDismissRequest = onDismissed,
    title = { Text("Local model server") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          "Serves the on-device model to other apps on this phone over " +
            baseUrl +
            ". Nothing leaves the device.",
          style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()

        if (downloaded.isEmpty()) {
          Text(
            "No models downloaded yet. Download one (Gemma 4 needs no sign-in and accepts audio), " +
              "then come back.",
            style = MaterialTheme.typography.bodyMedium,
          )
        } else {
          Text("Model", style = MaterialTheme.typography.labelMedium)
          OutlinedButton(onClick = { menuOpen = true }, enabled = !running && !busy) {
            Text(selected?.name ?: "Choose a model")
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (model in downloaded) {
              DropdownMenuItem(
                text = {
                  Text(model.name + (if (model.supportAudio) "  (audio)" else ""))
                },
                onClick = {
                  selected = model
                  ServerRegistry.selected = model
                  ServerRegistry.setLastModelName(context, model.name)
                  menuOpen = false
                },
              )
            }
          }
          selected?.let {
            Text(
              if (it.supportAudio) "Accepts text and audio."
              else "Text only — this model cannot transcribe audio.",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }

        HorizontalDivider()
        Text("API key", style = MaterialTheme.typography.labelMedium)
        Text(token, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text("Copy key") }
          TextButton(onClick = { clipboard.setText(AnnotatedString(baseUrl)) }) { Text("Copy URL") }
        }
        Text(
          "Any app on this phone can reach a loopback port, so clients must send this key as " +
            "'Authorization: Bearer <key>'.",
          style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        val status =
          when {
            busy -> "Loading model…"
            running -> "Running · " + serverState.requestCount + " request(s)"
            serverState.error.isNotEmpty() -> "Error: " + serverState.error
            else -> "Stopped"
          }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (running && serverState.lastActivity.isNotEmpty()) {
          Text(
            "Last: " + serverState.lastActivity,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      Button(
        enabled = !busy && (running || selected != null),
        onClick = {
          if (running) {
            LlmServerService.stop(context)
          } else {
            selected?.let {
              ServerRegistry.selected = it
              ServerRegistry.setLastModelName(context, it.name)
            }
            LlmServerService.start(context)
          }
        },
      ) {
        Text(if (running) "Stop server" else "Start server")
      }
    },
    dismissButton = { TextButton(onClick = onDismissed) { Text("Close") } },
    modifier = Modifier.padding(4.dp).fillMaxWidth(),
  )
}
