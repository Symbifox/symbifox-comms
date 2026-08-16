package com.bluefoxconsultant.sms.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The brand the app wears, taken from the Odoo instance it is connected to.
 *
 * Symbifox is the *product*, but an instance running `bluefox_branding` (or
 * simply Odoo's native company colours) belongs to somebody with their own
 * identity. The app asking the server who it works for is what makes it
 * white-label instead of a Symbifox-branded client someone else has to live
 * with.
 *
 * Persisted per instance and applied before the first paint, so the app does
 * not flash Symbifox blue on launch and then repaint. Absent or malformed
 * values simply leave the defaults in place — an unbranded instance is normal.
 */
data class Brand(
    val name: String = "",
    val primary: Int = SYMBIFOX_PRIMARY,
    val dark: Int = SYMBIFOX_DARK,
) {
    companion object {
        /** Product defaults, used until an instance says otherwise. */
        const val SYMBIFOX_PRIMARY = 0xFF176CF2.toInt()
        const val SYMBIFOX_DARK = 0xFF0E3E8C.toInt()

        /** `#RRGGBB` → ARGB int, or null. Never throws on rubbish input. */
        fun parseColour(value: String?): Int? {
            val hex = value?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return null
            }
            return runCatching { (0xFF000000L or hex.toLong(16)).toInt() }.getOrNull()
        }
    }
}

class BrandStore(context: Context) {

    private val prefs = context.getSharedPreferences("bf_brand", Context.MODE_PRIVATE)

    private val _brand = MutableStateFlow(load())
    val brandFlow: StateFlow<Brand> = _brand.asStateFlow()
    val brand: Brand get() = _brand.value

    private fun load() = Brand(
        name = prefs.getString(KEY_NAME, "").orEmpty(),
        primary = prefs.getInt(KEY_PRIMARY, Brand.SYMBIFOX_PRIMARY),
        dark = prefs.getInt(KEY_DARK, Brand.SYMBIFOX_DARK),
    )

    /**
     * Store what an instance reported. A field the server left null keeps the
     * current value rather than resetting it — a partially branded instance
     * should not wipe the half it did configure.
     */
    fun save(name: String?, primary: String?, dark: String?) {
        val updated = Brand(
            name = name?.takeIf { it.isNotBlank() } ?: _brand.value.name,
            primary = Brand.parseColour(primary) ?: Brand.SYMBIFOX_PRIMARY,
            dark = Brand.parseColour(dark) ?: Brand.SYMBIFOX_DARK,
        )
        prefs.edit()
            .putString(KEY_NAME, updated.name)
            .putInt(KEY_PRIMARY, updated.primary)
            .putInt(KEY_DARK, updated.dark)
            .apply()
        _brand.value = updated
    }

    /** Back to product defaults — on sign-out, or when changing instance. */
    fun reset() {
        prefs.edit().clear().apply()
        _brand.value = Brand()
    }

    private companion object {
        const val KEY_NAME = "brand_name"
        const val KEY_PRIMARY = "brand_primary"
        const val KEY_DARK = "brand_dark"
    }
}
