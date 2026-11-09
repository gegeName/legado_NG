package io.legado.features.yiciyuan

import cn.hutool.core.codec.Base64
import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.symmetric.SymmetricCrypto
import javax.crypto.spec.SecretKeySpec

/** Legacy ES/AES string inputs may be hex or Base64; byte/stream inputs are raw. */
internal class CompatibilityCrypto(mode: String, key: ByteArray) : SymmetricCrypto(mode, SecretKeySpec(key, mode.substringBefore('/'))) {
    override fun decrypt(data: String): ByteArray = decrypt(
        if (data.length % 2 == 0 && data.matches(Regex("[0-9a-fA-F]+"))) HexUtil.decodeHex(data)
        else Base64.decode(data)
    )
}
