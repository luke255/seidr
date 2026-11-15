package eu.bradshaw.seidr.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Strings kept in [prefs] encrypted, under an AES-GCM key that lives in the
 * Android Keystore (no user authentication, so background sync and the car
 * still work). Stored as `<name>.enc`.
 *
 * A plain `<name>` (from an older version, or written by hand) wins over the
 * encrypted one and is encrypted on first read. If the key is lost or the value
 * won't decrypt (it happens after some OEM restores), [read] returns null, as
 * if it had never been saved: for the password, that's signed out.
 */
class Secrets(private val prefs: SharedPreferences) {
    fun read(name: String): String? {
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
        } else {
            runCatching { encrypt(value) }
                .onSuccess { edit.putString("$name.enc", it).remove(name) }
                // No Keystore: keep it as it was rather than lose it.
                .onFailure { Log.w(TAG, "can't encrypt $name; keeping it plain: $it"); edit.putString(name, value) }
        }
        edit.apply()
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

    private companion object {
        const val TAG = "Secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "seidr-prefs"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
