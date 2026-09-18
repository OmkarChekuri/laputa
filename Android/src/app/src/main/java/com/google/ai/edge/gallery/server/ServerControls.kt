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

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import com.google.ai.edge.gallery.MainActivity

/**
 * Shared plumbing for the home-screen widget and the Quick Settings tile.
 *
 * Stopping talks to the service directly. Starting goes through the app: the service needs live
 * `Model` objects, which only the app's model manager can build, so the widget opens Laputa with
 * [ACTION_START]; the home screen starts the server as soon as its model list is ready and then
 * steps back out of the way (see [ServerAutoStart]).
 */
object ServerControls {
  const val ACTION_START = "com.laputa.host.START_SERVER_FROM_SHORTCUT"

  private const val REQUEST_START = 20
  private const val REQUEST_STOP = 21
  private const val REQUEST_PICK = 22
  private const val REQUEST_OPEN = 23

  /** Redraw every widget and ask the system to refresh the tile. */
  fun refresh(context: Context) {
    LaputaWidget.updateAll(context)
    TileService.requestListeningState(
      context,
      ComponentName(context, LaputaTileService::class.java),
    )
  }

  fun startIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
      .setAction(ACTION_START)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

  fun startPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(context, REQUEST_START, startIntent(context), FLAGS)

  fun stopPendingIntent(context: Context): PendingIntent =
    PendingIntent.getService(
      context,
      REQUEST_STOP,
      Intent(context, LlmServerService::class.java).setAction(LlmServerService.ACTION_STOP),
      FLAGS,
    )

  fun pickModelPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      REQUEST_PICK,
      Intent(context, ModelPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      FLAGS,
    )

  fun openAppPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      REQUEST_OPEN,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      FLAGS,
    )

  private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
