package com.gitee.prviewer.service

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

object PluginAuthorHeaderEncryptor {
    private const val ALGORITHM = "RSA"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    fun encrypt(username: String, publicKeyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(ALGORITHM)
        val publicKey = keyFactory.generatePublic(keySpec)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedData = cipher.doFinal(username.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(encryptedData)
    }
}
