package com.mammonrn.phoneaikiosk.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves
 * it: the key cannot be read out of the phone, backed up, or copied to another
 * device, so neither can anything it encrypts (Poom: "เข้ารหัสด้วย Android
 * Keystore และห้ามสำรองขึ้น cloud"). The files it writes go in
 * noBackupFilesDir, which Android's backup never includes — see AuthStore.
 *
 * No user authentication on the key: the kiosk has no screen lock to tie it
 * to, and the face or pattern IS the authentication.
 *
 * Format: one version byte, the 12-byte IV, then ciphertext with its tag.
 */
object SecretBox {

    private const val ALIAS = "kiosk-auth-v1"
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 1 + IV_BYTES && sealed[0] == VERSION) { "not a sealed record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
                    GCMParameterSpec(128, sealed.copyOfRange(1, 1 + IV_BYTES)))
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }
}
