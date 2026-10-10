package com.carrie.widgetguide.storage

import android.content.Context
import android.annotation.SuppressLint

/** Dedicated file, a single JSON value, synchronous commit on the repository's IO dispatcher. */
class SharedPreferencesStateStore(context: Context, name: String = "browser_widget_guide") : StringStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString("state_v1", null)
    // KTX edit(commit = true) discards commit's Boolean; failure handling needs that result.
    @SuppressLint("UseKtx")
    override fun write(value: String): Boolean = preferences.edit().putString("state_v1", value).commit()
}
