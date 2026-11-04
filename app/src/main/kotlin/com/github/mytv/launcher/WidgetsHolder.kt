package com.github.mytv.launcher

import android.content.Context
import com.github.mytv.data.widgets.WidgetsRepository

/** One AppWidgetHost per process; the pin-confirmation activity shares it. */
object WidgetsHolder {
    @Volatile
    private var instance: WidgetsRepository? = null

    fun get(context: Context): WidgetsRepository =
        instance ?: synchronized(this) {
            instance ?: WidgetsRepository(context.applicationContext).also { instance = it }
        }
}
