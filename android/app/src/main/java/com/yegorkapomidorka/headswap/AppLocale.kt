package com.yegorkapomidorka.headswap

import android.content.Context

/**
 * English-only build: the app ships a single language and ignores the system/app locale.
 * The class is kept because services and the activity call [wrap] in attachBaseContext.
 */
object AppLocale {
    const val SYSTEM = "en"
    val TAGS = listOf(SYSTEM)

    fun get(ctx: Context): String = SYSTEM

    fun set(ctx: Context, tag: String) {
        // English-only: nothing to switch.
    }

    val needsRecreate: Boolean get() = false

    fun wrap(base: Context): Context = base
}
