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

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.google.ai.edge.gallery.R

/**
 * Home-screen widget: server status, a model button (opens a picker) and Start/Stop.
 *
 * Reads the live [ServerRegistry] state. If Laputa's process isn't running, the server can't be
 * either, so the default "off" state is also the correct one.
 */
class LaputaWidget : AppWidgetProvider() {

  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    val views = build(context)
    for (id in ids) manager.updateAppWidget(id, views)
  }

  companion object {
    fun updateAll(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, LaputaWidget::class.java))
      if (ids.isEmpty()) return
      val views = build(context)
      for (id in ids) manager.updateAppWidget(id, views)
    }

    private fun build(context: Context): RemoteViews {
      val state = ServerRegistry.state.value
      val chosen = if (state.running || state.starting) state.modelName else ServerRegistry.lastModelName(context)
      val views = RemoteViews(context.packageName, R.layout.laputa_widget)

      val status =
        when {
          state.running -> "Serving · :" + state.port
          state.starting -> context.getString(R.string.laputa_widget_starting)
          state.error.isNotEmpty() -> "Off · " + state.error
          else -> context.getString(R.string.laputa_widget_off)
        }
      views.setTextViewText(R.id.lw_status, status)
      views.setViewVisibility(R.id.lw_dot, if (state.running) View.VISIBLE else View.GONE)
      views.setTextViewText(
        R.id.lw_model_label,
        chosen.ifEmpty { context.getString(R.string.laputa_widget_pick) },
      )

      val (label, background) =
        when {
          state.running -> R.string.laputa_widget_stop to R.drawable.laputa_widget_stop
          state.starting -> R.string.laputa_widget_starting to R.drawable.laputa_widget_busy
          else -> R.string.laputa_widget_start to R.drawable.laputa_widget_start
        }
      views.setTextViewText(R.id.lw_toggle_label, context.getString(label))
      views.setInt(R.id.lw_toggle, "setBackgroundResource", background)

      views.setOnClickPendingIntent(
        R.id.lw_toggle,
        if (state.running || state.starting) ServerControls.stopPendingIntent(context)
        else ServerControls.startPendingIntent(context),
      )
      views.setOnClickPendingIntent(R.id.lw_model, ServerControls.pickModelPendingIntent(context))
      views.setOnClickPendingIntent(R.id.lw_header, ServerControls.openAppPendingIntent(context))
      return views
    }
  }
}
