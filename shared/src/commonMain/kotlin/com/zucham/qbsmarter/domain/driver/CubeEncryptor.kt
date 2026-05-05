package com.zucham.qbsmarter.domain.driver

/** Symmetric encryption for cube wire protocols. GAN Gen2 uses AES-CBC. */
interface CubeEncryptor {
    fun encrypt(data: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** No-op encryptor for cubes that don't encrypt their traffic. */
object NoOpCubeEncryptor : CubeEncryptor {
    override fun encrypt(data: ByteArray): ByteArray = data
    override fun decrypt(data: ByteArray): ByteArray = data
}
