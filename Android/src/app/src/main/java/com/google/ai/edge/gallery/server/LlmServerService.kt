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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "LaputaServerService"
private const val CHANNEL_ID = "laputa_server"
private const val NOTIFICATION_ID = 4711

/**
 * Keeps the model loaded and the HTTP server alive while the app is in the background.
 *
 * Runs in the main process on purpose: LiteRT-LM has a reported native crash when an engine is
 * created in a separate `android:process`.
 */
class LlmServerService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var server: LlmServer? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopEverything()
      return START_NOT_STICKY
    }

    val port = ServerRegistry.port(this)
    val model = ServerRegistry.selected

    // Post the notification before any slow work: a foreground service that dawdles before
    // calling startForeground() is killed with "did not then call Service.startForeground()".
    createChannel()
    startForegroundCompat(notification(statusLine = "Starting…", port = port))

    if (model == null) {
      ServerRegistry.update {
        it.copy(running = false, starting = false, error = "No model selected.")
      }
      stopEverything()
      return START_NOT_STICKY
    }

    ServerRegistry.update {
      it.copy(starting = true, error = "", port = port, modelName = model.name)
    }

    scope.launch {
      try {
        // Slow (~10 s), so it happens after startForeground(), off the main thread.
        ModelHost.ensureLoaded(applicationContext, model, TASK_ID)
        server = LlmServer(applicationContext, port, ServerRegistry.token(this@LlmServerService))
        server?.start()
        ServerRegistry.update { it.copy(running = true, starting = false, error = "") }
        notifyNow(notification(statusLine = "Serving " + model.name, port = port))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start server", e)
        ServerRegistry.update {
          it.copy(running = false, starting = false, error = e.message ?: "failed to start")
        }
        stopEverything()
      }
    }

    return START_STICKY
  }

  override fun onDestroy() {
    runCatching { server?.stop() }
    server = null
    scope.cancel()
    ServerRegistry.update { it.copy(running = false, starting = false) }
    super.onDestroy()
  }

  private fun stopEverything() {
    runCatching { server?.stop() }
    server = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun startForegroundCompat(notification: Notification) {
    startForeground(
      NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    )
  }

  private fun notifyNow(notification: Notification) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, notification)
  }

  private fun createChannel() {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Local model server", NotificationManager.IMPORTANCE_LOW)
        .apply { description = "Shown while Laputa is serving a model to other apps." }
    )
  }

  private fun notification(statusLine: String, port: Int): Notification {
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val stop =
      PendingIntent.getService(
        this,
        1,
        Intent(this, LlmServerService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Laputa model server")
      .setContentText(statusLine + " · " + LlmServer.LOOPBACK + ":" + port)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .setContentIntent(open)
      .addAction(0, "Stop", stop)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  companion object {
    private const val TASK_ID = "llm_chat"
    const val ACTION_STOP = "com.laputa.host.STOP_SERVER"

    fun start(context: Context) {
      context.startForegroundService(Intent(context, LlmServerService::class.java))
    }

    fun stop(context: Context) {
      context.startService(
        Intent(context, LlmServerService::class.java).setAction(ACTION_STOP)
      )
    }
  }
}
