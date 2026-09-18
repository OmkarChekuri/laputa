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
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast

/**
 * The widget's model "drop-down": a small dialog over the home screen listing the models
 * downloaded in Laputa. Picking one while the server runs restarts it with that model.
 */
class ModelPickerActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ServerRegistry.appContext = applicationContext

    val models = ServerRegistry.knownModels(this)
    val builder =
      AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Model to serve")
        .setOnDismissListener { finish() }

    if (models.isEmpty()) {
      builder
        .setMessage("No downloaded models found yet. Open Laputa and download one (Gemma 4 accepts audio too).")
        .setPositiveButton("Open Laputa") { _, _ -> startActivity(ServerControls.startIntent(this).setAction(null)) }
        .setNegativeButton("Close", null)
        .show()
      return
    }

    val current = ServerRegistry.lastModelName(this)
    val labels = models.map { if (it.audio) it.name + "  · audio" else it.name }.toTypedArray()
    builder
      .setSingleChoiceItems(labels, models.indexOfFirst { it.name == current }) { dialog, which ->
        val picked = models[which].name
        ServerRegistry.setLastModelName(this, picked)
        val state = ServerRegistry.state.value
        if ((state.running || state.starting) && state.modelName != picked) {
          // Switch models: Laputa stops the running one and starts this one.
          startActivity(ServerControls.startIntent(this))
          Toast.makeText(this, "Switching to $picked…", Toast.LENGTH_SHORT).show()
        }
        ServerControls.refresh(this)
        dialog.dismiss()
      }
      .setNegativeButton("Cancel", null)
      .show()
  }
}
