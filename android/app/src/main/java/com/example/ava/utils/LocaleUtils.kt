package com.example.ava.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleUtils {

    private fun getEffectiveLocale(): Locale {
        val lang = Locale.getDefault().language.lowercase()
        return when {
            lang.startsWith("zh") -> Locale.getDefault()
            lang.startsWith("ru") -> Locale.getDefault()
            lang.startsWith("pt") -> Locale.getDefault()
            lang.startsWith("vi") -> Locale.getDefault()
            lang.startsWith("de") -> Locale.getDefault()
            lang.startsWith("fr") -> Locale.getDefault()
            lang.startsWith("es") -> Locale.getDefault()
            else -> Locale.ENGLISH
        }
    }

    fun applyLocale(context: Context): Context {
        val locale = getEffectiveLocale()
        Locale.setDefault(locale)
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(locale)
        })
    }

    fun isChineseLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase()
        return lang.startsWith("zh")
    }

    fun isRussianLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase()
        return lang.startsWith("ru")
    }

    fun isPortugueseLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase()
        return lang.startsWith("pt")
    }

    fun isVietnameseLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase()
        return lang.startsWith("vi")
    }

    fun isGermanLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase()
        return lang.startsWith("de")
    }

    fun isFrenchLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase()
        return lang.startsWith("fr")
    }

    fun isSpanishLocale(): Boolean {
        val lang = Locale.getDefault().language.lowercase()
        return lang.startsWith("es")
    }
}
