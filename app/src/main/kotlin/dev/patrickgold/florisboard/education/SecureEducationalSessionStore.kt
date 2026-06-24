/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.education

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecureEducationalSessionStore(context: Context) {
    private val file = context.noBackupFilesDir.resolve("education-session.bin")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): EducationalSession? {
        val raw = file.takeIf { it.exists() }?.readText() ?: return null
        return runCatching {
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val iv = Base64.getDecoder().decode(parts[0])
            val encrypted = Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagBits, iv))
            val plain = cipher.doFinal(encrypted).decodeToString()
            json.decodeFromString<EducationalSession>(plain)
        }.getOrNull()
    }

    fun save(session: EducationalSession) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.encodeToString(session).encodeToByteArray())
        val payload = Base64.getEncoder().encodeToString(cipher.iv) + ":" +
            Base64.getEncoder().encodeToString(encrypted)
        file.writeText(payload)
    }

    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        keyStore.getKey(KeyAlias, null)?.let { return it as SecretKey }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    companion object {
        private const val AndroidKeyStore = "AndroidKeyStore"
        private const val KeyAlias = "florisboard.education.session"
        private const val Transformation = "AES/GCM/NoPadding"
        private const val GcmTagBits = 128
    }
}
