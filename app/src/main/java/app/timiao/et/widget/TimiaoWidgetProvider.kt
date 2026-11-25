package app.timiao.et.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** 2x1 (resizable to 4x1) connection widget for the launcher. */
class TimiaoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        TimiaoWidget.refresh(context, force = true)
    }

    override fun onEnabled(context: Context) {
        TimiaoWidget.refresh(context, force = true)
    }

    override fun onDisabled(context: Context) {
        TimiaoWidget.forget()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        TimiaoWidget.refresh(context, force = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            TimiaoWidget.toggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "app.timiao.et.widget.TOGGLE"
    }
}
