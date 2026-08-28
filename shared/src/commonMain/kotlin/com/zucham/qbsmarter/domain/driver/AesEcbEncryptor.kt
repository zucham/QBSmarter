package com.zucham.qbsmarter.domain.driver

/**
 * AES-128 ECB encryptor over a fixed, vendor-wide key.
 *
 * The counterpart to [AesCbcMacSaltEncryptor], and deliberately a
 * separate class rather than a mode flag on it: the two schemes share
 * nothing but the acronym. CBC-with-MAC-salt is *per cube* (the BLE MAC
 * mixes into key and IV) and only ever touches the first and last block
 * of a payload; ECB is *per vendor* (one hard-coded key for every cube
 * ever made) and covers the whole payload block by block. Folding them
 * together would mean an encryptor whose behaviour depends on which
 * constructor arguments happen to be null.
 *
 * ECB has no chaining, so every 16-byte block is independent. That is
 * ordinarily a reason not to use it, and it is exactly why QiYi's frames
 * can be zero-padded to a block boundary and still decrypt: the padding
 * decrypts to garbage that the frame's declared length tells us to
 * ignore.
 *
 * **Partial trailing blocks are left untouched.** A buffer whose length
 * is not a multiple of 16 has its whole blocks transformed and its tail
 * copied through verbatim. NoPadding cannot process a short block, and
 * throwing would kill the connection over a single malformed
 * notification — the frame's own CRC is the integrity check that
 * matters, and it will reject the packet a moment later.
 *
 * `expect class` for the same reason as [AesCbcMacSaltEncryptor]: only
 * the Android actual is real, and JVM-desktop and Web throw
 * `NotImplementedError`.
 *
 * @param key 16-byte AES-128 key. Vendor-specific, hard-coded.
 */
expect class AesEcbEncryptor(key: ByteArray) : CubeEncryptor {
    override fun encrypt(data: ByteArray): ByteArray
    override fun decrypt(data: ByteArray): ByteArray
}
