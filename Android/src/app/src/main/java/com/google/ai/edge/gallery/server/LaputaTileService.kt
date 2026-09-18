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

import android.annotation.SuppressLint
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.google.ai.edge.gallery.R

/** Quick Settings tile: shows whether the model server is on; tap to start or stop it. */
class LaputaTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    ServerRegistry.appContext = applicationContext
    updateTile()
  }

  override fun onClick() {
    super.onClick()
    val state = ServerRegistry.state.value
    if (state.running || state.starting) {
      LlmServerService.stop(this)
      return
    }
    if (isLocked) unlockAndRun { openToStart() } else openToStart()
  }

  @SuppressLint("StartActivityAndCollapseDeprecated")
  @Suppress("DEPRECATION")
  private fun openToStart() {
    if (Build.VERSION.SDK_INT >= 34) {
      startActivityAndCollapse(ServerControls.startPendingIntent(this))
    } else {
      startActivityAndCollapse(ServerControls.startIntent(this))
    }
  }

  private fun updateTile() {
    val tile = qsTile ?: return
    val state = ServerRegistry.state.value
    tile.icon = Icon.createWithResource(this, R.drawable.ic_laputa_small)
    tile.label = getString(R.string.laputa_tile_label)
    tile.state = if (state.running || state.starting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.subtitle =
      when {
        state.running -> state.modelName
        state.starting -> getString(R.string.laputa_widget_starting)
        else -> getString(R.string.laputa_widget_off)
      }
    tile.updateTile()
  }
}
