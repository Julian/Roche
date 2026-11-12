package com.grayvines.roche.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val ENCRYPTED_TOKEN_PREFIX = "enc:v1:"
internal const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
internal const val KEYSTORE_KEY_ALIAS = "roche_bearer_token_v1"
internal const val AES_ALGORITHM = "AES"
internal const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
internal const val AES_KEY_SIZE_BITS = 256
internal const val GCM_IV_LENGTH_BYTES = 12
internal const val GCM_TAG_LENGTH_BITS = 128
internal const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / Byte.SIZE_BITS
internal const val CIPHERTEXT_PART_COUNT = 2

internal interface TokenCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(stored: String): String
}

internal class AesGcmSecretKeyTokenCipher(private val keyProvider: () -> SecretKey) : TokenCipher {
    constructor(secretKey: SecretKey) : this(keyProvider = { secretKey })

    override fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return ""
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        check(iv != null && iv.size == GCM_IV_LENGTH_BYTES) {
            "Expected $GCM_IV_LENGTH_BYTES-byte AES-GCM IV"
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedIv = encoder.encodeToString(iv)
        val encodedCiphertext = encoder.encodeToString(ciphertext)
        return "$ENCRYPTED_TOKEN_PREFIX$encodedIv:$encodedCiphertext"
    }

    override fun decrypt(stored: String): String {
        if (!stored.startsWith(ENCRYPTED_TOKEN_PREFIX)) return ""
        return runCatching { decryptPayload(stored.removePrefix(ENCRYPTED_TOKEN_PREFIX)) }
            .getOrDefault("")
    }

    private fun decryptPayload(payload: String): String {
        val parts = payload.split(':')
        if (parts.size != CIPHERTEXT_PART_COUNT) return ""
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(parts[0])
        val ciphertext = decoder.decode(parts[1])
        if (iv.size != GCM_IV_LENGTH_BYTES || ciphertext.size <= GCM_TAG_LENGTH_BYTES) return ""
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }
}

internal class AndroidKeystoreTokenCipher(private val keyAlias: String = KEYSTORE_KEY_ALIAS) :
    TokenCipher {
    private val delegate = AesGcmSecretKeyTokenCipher { getOrCreateKeystoreSecretKey(keyAlias) }

    override fun encrypt(plaintext: String): String = delegate.encrypt(plaintext)

    override fun decrypt(stored: String): String = delegate.decrypt(stored)
}

internal fun createDefaultTokenCipher(): TokenCipher =
    if (Security.getProviders().any { it.name == ANDROID_KEYSTORE_PROVIDER }) {
        AndroidKeystoreTokenCipher()
    } else {
        AesGcmSecretKeyTokenCipher(jvmProcessFallbackSecretKey)
    }

internal val keystoreLock = Any()

internal val jvmProcessFallbackSecretKey: SecretKey by lazy {
    KeyGenerator.getInstance(AES_ALGORITHM).apply { init(AES_KEY_SIZE_BITS) }.generateKey()
}

internal fun getOrCreateKeystoreSecretKey(keyAlias: String): SecretKey =
    synchronized(keystoreLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        val existing = (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) {
            existing
        } else {
            val keyGenerator =
                KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE_PROVIDER,
                )
            val spec =
                KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_SIZE_BITS)
                    .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }
