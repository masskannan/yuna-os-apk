package com.puyal.os.guard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.KeyEvent
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

object YunaTierGuards {
    @Volatile var emergencyKilled = false
    private var volDownStartMs = 0L

    // Tier 3: Volume Down held 3s = 1ms automation kill-switch
    fun onHardwareKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && volDownStartMs == 0L) {
                volDownStartMs = System.currentTimeMillis()
            } else if (event.action == KeyEvent.ACTION_DOWN && System.currentTimeMillis() - volDownStartMs >= 3000L) {
                emergencyKilled = true
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                volDownStartMs = 0L
            }
        }
        return false
    }

    // Tier 2: 3-5s randomized delay for WhatsApp human-safe replies
    fun nextHumanReplyDelayMs(): Long = Random.nextLong(3000L, 5001L)

    // Tier 3: SHA-256 APK self-update verification
    fun verifyApkSha256(apkFile: File, expectedHex: String): Boolean {
        if (!apkFile.exists()) return false
        val md = MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().use { fis ->
            val buf = ByteArray(8192)
            var r: Int
            while (fis.read(buf).also { r = it } != -1) md.update(buf, 0, r)
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex.trim(), ignoreCase = true)
    }

    // Tier 3: Android Keystore Hardware-Backed AES-256-GCM Vault
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("yuna_tee_vault", null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder("yuna_tee_vault", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun encryptVault(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv to cipher.doFinal(plain)
    }

    fun decryptVault(iv: ByteArray, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
