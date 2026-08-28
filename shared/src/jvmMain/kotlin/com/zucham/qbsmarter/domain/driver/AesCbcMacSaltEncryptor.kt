package com.zucham.qbsmarter.domain.driver

actual class AesCbcMacSaltEncryptor actual constructor(
    rootKey: ByteArray,
    rootIv: ByteArray,
    salt: ByteArray,
) : CubeEncryptor {
    actual override fun encrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: jvmMain AesCbcMacSaltEncryptor")
    actual override fun decrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: jvmMain AesCbcMacSaltEncryptor")
}
