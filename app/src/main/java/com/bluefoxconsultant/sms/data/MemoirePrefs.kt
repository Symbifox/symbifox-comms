package com.bluefoxconsultant.sms.data

import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Des préférences qui ne touchent jamais le disque.
 *
 * C'est là que vivent les secrets quand le Keystore refuse d'ouvrir le
 * magasin chiffré : le temps du processus, et pas une seconde de plus. Voir
 * [ouvrirMagasins]. Sert aussi de double aux essais, puisque
 * [SharedPreferences] n'est qu'une interface.
 *
 * Même sémantique que l'implémentation d'Android sur ce qui compte ici :
 * `clear()` passe AVANT les écritures du même lot, une valeur du mauvais type
 * rend la valeur par défaut plutôt que de lever, et `apply()` vaut `commit()`.
 */
class MemoirePrefs : SharedPreferences {

    private val valeurs = HashMap<String, Any>()
    private val ecouteurs =
        CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized
    override fun getAll(): MutableMap<String, *> = HashMap(valeurs)

    @Synchronized
    override fun getString(key: String?, defValue: String?): String? =
        valeurs[key] as? String ?: defValue

    @Synchronized
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (valeurs[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues

    @Synchronized
    override fun getInt(key: String?, defValue: Int): Int = valeurs[key] as? Int ?: defValue

    @Synchronized
    override fun getLong(key: String?, defValue: Long): Long = valeurs[key] as? Long ?: defValue

    @Synchronized
    override fun getFloat(key: String?, defValue: Float): Float = valeurs[key] as? Float ?: defValue

    @Synchronized
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        valeurs[key] as? Boolean ?: defValue

    @Synchronized
    override fun contains(key: String?): Boolean = valeurs.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editeur()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener?.let { ecouteurs.add(it) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener?.let { ecouteurs.remove(it) }
    }

    private inner class Editeur : SharedPreferences.Editor {
        /** `null` veut dire « retirer ». */
        private val changements = LinkedHashMap<String, Any?>()
        private var vider = false

        override fun putString(key: String?, value: String?) = poser(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            poser(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = poser(key, value)
        override fun putLong(key: String?, value: Long) = poser(key, value)
        override fun putFloat(key: String?, value: Float) = poser(key, value)
        override fun putBoolean(key: String?, value: Boolean) = poser(key, value)
        override fun remove(key: String?) = poser(key, null)

        override fun clear(): SharedPreferences.Editor {
            synchronized(this) { vider = true }
            return this
        }

        private fun poser(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) synchronized(this) { changements[key] = value }
            return this
        }

        override fun commit(): Boolean {
            val touchees = synchronized(this@MemoirePrefs) {
                synchronized(this) {
                    val avant = valeurs.keys.toSet()
                    if (vider) valeurs.clear()
                    changements.forEach { (cle, valeur) ->
                        if (valeur == null) valeurs.remove(cle) else valeurs[cle] = valeur
                    }
                    (if (vider) avant else emptySet()) + changements.keys
                }
            }
            touchees.forEach { cle ->
                ecouteurs.forEach { it.onSharedPreferenceChanged(this@MemoirePrefs, cle) }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
