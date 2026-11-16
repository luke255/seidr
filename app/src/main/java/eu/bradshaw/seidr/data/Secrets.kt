package eu.bradshaw.seidr.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Strings kept encrypted, under an AES-GCM key that lives in the Android
 * Keystore (no user authentication, so background sync and the car still
 * work). They live in their own prefs file, `secrets`, which backups and
 * device transfers leave out (`data_extraction_rules.xml`): Keystore keys
 * never leave the device, so a copy would be useless anyway. Stored as
 * `<name>.enc`; names are shared by every user of the file.
 *
 * If the Keystore won't encrypt, the value is kept plain (still app-private,
 * still never backed up) rather than signing the user out on every start;
 * [unprotected] says so, for Settings to show. Every read retries encrypting.
 *
 * A plain `<name>` (from an older version, or the fallback) wins over the
 * encrypted one and is encrypted on read. Values from before the `secrets`
 * file (in [legacy]) move over on first read. If the key is lost or the value
 * won't decrypt (it happens after some OEM restores), [read] returns null, as
 * if it had never been saved: for the password, that's signed out.
 */
class Secrets(context: Context, private val legacy: SharedPreferences? = null) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        if (!checked) synchronized(Secrets) { if (!checked) { refreshUnprotected(); checked = true } }
    }

    fun read(name: String): String? {
        migrate(name)
        prefs.getString(name, null)?.let { plain ->
            write(name, plain)
            return plain
        }
        val stored = prefs.getString("$name.enc", null) ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { Log.w(TAG, "$name won't decrypt; forgetting it: $it") }
            .getOrNull()
    }

    /** Null removes it. */
    fun write(name: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) {
            edit.remove(name).remove("$name.enc")
            legacy?.edit()?.remove(name)?.remove("$name.enc")?.apply()
        } else {
            runCatching { encrypt(value) }
                .onSuccess { edit.putString("$name.enc", it).remove(name) }
                // No Keystore: keep it rather than lose it, and say so (unprotected).
                .onFailure { Log.w(TAG, "can't encrypt $name; keeping it plain: $it"); edit.putString(name, value) }
        }
        edit.apply()
        refreshUnprotected()
    }

    /** Moves [name] over from [legacy] (the prefs file it lived in before), as stored. */
    private fun migrate(name: String) {
        val old = legacy ?: return
        if (!old.contains(name) && !old.contains("$name.enc")) return
        if (!prefs.contains(name) && !prefs.contains("$name.enc")) {
            prefs.edit().apply {
                old.getString(name, null)?.let { putString(name, it) }
                old.getString("$name.enc", null)?.let { putString("$name.enc", it) }
            }.apply()
        }
        old.edit().remove(name).remove("$name.enc").apply()
    }

    private fun refreshUnprotected() {
        _unprotected.value = prefs.all.keys.any { !it.endsWith(".enc") }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val key = existingKey() ?: error("no key")
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }

    private fun existingKey(): SecretKey? =
        (KeyStore.getInstance(KEYSTORE).apply { load(null) }.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = synchronized(Secrets) {
        existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        /** The prefs file; `data_extraction_rules.xml` keeps it out of backups. */
        const val FILE = "secrets"
        private const val TAG = "Secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "seidr-prefs"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        @Volatile private var checked = false
        private val _unprotected = MutableStateFlow(false)
        /** True while any secret is stored plain because the Keystore wouldn't encrypt it. */
        val unprotected: StateFlow<Boolean> = _unprotected.asStateFlow()
    }
}
