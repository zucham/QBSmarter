package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.CubeEncryptor

actual class GanEncryptor actual constructor(salt: ByteArray) : CubeEncryptor {
    actual override fun encrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: jvmMain GanGen2Encryptor")
    actual override fun decrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: jvmMain GanGen2Encryptor")
}
