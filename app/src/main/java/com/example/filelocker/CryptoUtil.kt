package com.example.filelocker

import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    private const val MAGIC = "FLK1"

    private fun key(secret: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, 120_000, 256))
        return SecretKeySpec(k.encoded, "AES")
    }

    fun encrypt(input: InputStream, output: OutputStream, secret: String, name: String) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret, salt), GCMParameterSpec(128, iv))
        val d = DataOutputStream(output)
        d.writeUTF(MAGIC)
        d.writeUTF(name)
        d.write(salt)
        d.write(iv)
        d.flush()
        CipherOutputStream(output, cipher).use { input.copyTo(it) }
    }

    /** Wrong PIN/Pattern => exception throw hoti hai (GCM tag fail). */
    fun decrypt(input: InputStream, output: OutputStream, secret: String) {
        val d = DataInputStream(input)
        require(d.readUTF() == MAGIC) { "Not a locked file" }
        d.readUTF() // original name
        val salt = ByteArray(16).also { d.readFully(it) }
        val iv = ByteArray(12).also { d.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secret, salt), GCMParameterSpec(128, iv))
        CipherInputStream(input, cipher).copyTo(output)
        output.flush()
    }

    fun originalName(file: File): String = try {
        DataInputStream(FileInputStream(file)).use {
            it.readUTF()
            it.readUTF()
        }
    } catch (e: Exception) {
        file.name
    }
}
