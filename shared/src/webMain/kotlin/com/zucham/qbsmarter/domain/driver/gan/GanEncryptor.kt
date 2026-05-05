package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.CubeEncryptor

actual class GanGen2Encryptor actual constructor(salt: ByteArray) : CubeEncryptor {
    actual override fun encrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: webMain GanGen2Encryptor")
    actual override fun decrypt(data: ByteArray): ByteArray =
        throw NotImplementedError("TODO: webMain GanGen2Encryptor")
}
