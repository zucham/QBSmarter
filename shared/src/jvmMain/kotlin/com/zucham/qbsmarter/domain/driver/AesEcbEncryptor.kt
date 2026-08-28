package com.zucham.qbsmarter.domain.driver

actual class AesEcbEncryptor actual constructor(
    key: ByteArray,
) : CubeEncryptor {
    actual override fun encrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: jvmMain AesEcbEncryptor")
    actual override fun decrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: jvmMain AesEcbEncryptor")
}
