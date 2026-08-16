package com.bluefoxconsultant.sms.data

enum class ThemeMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun from(value: String?): ThemeMode =
            entries.firstOrNull { it.stored == value } ?: SYSTEM
    }
}
