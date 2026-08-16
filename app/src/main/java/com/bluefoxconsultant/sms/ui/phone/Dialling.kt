package com.bluefoxconsultant.sms.ui.phone

/**
 * The two rules the keypad needs, kept out of the ViewModel so they can be
 * tested without an Android runtime.
 */

/** Digits only, last ten — what identifies a North American number. */
fun dialledDigits(dialled: String): String = dialled.filter(Char::isDigit).takeLast(10)

/**
 * Whether the call button should light up.
 *
 * Ten digits is the shortest thing the server accepts (North America only), so
 * offering the button earlier would only earn a refusal round trip.
 */
fun isCallable(dialled: String): Boolean = dialled.count(Char::isDigit) >= 10
