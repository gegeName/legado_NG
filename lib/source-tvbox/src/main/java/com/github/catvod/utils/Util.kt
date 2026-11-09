package com.github.catvod.utils

import okhttp3.OkHttp
import java.net.Inet4Address
import java.net.NetworkInterface

object Util {

    @JvmField
    val OKHTTP: String = "okhttp/" + OkHttp.VERSION

    const val CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

    @JvmField
    val URL_SAFE: Int = 0 or 8 or 2

    @JvmStatic
    fun base64(s: String): String = base64(s.toByteArray(), 0 or 2)

    @JvmStatic
    fun base64(bytes: ByteArray): String = base64(bytes, 0 or 2)

    @JvmStatic
    fun base64(s: String, flags: Int): String = base64(s.toByteArray(), flags)

    @JvmStatic
    fun base64(bytes: ByteArray, flags: Int): String {
        var encoder = if (flags and 8 != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and 1 != 0) encoder = encoder.withoutPadding()
        return encoder.encodeToString(bytes)
    }

    @JvmStatic
    fun decode(s: String): ByteArray = decode(s, 0 or 2)

    @JvmStatic
    fun decode(s: String, flags: Int): ByteArray {
        val normalized = if (flags and 8 != 0) s.replace('-', '+').replace('_', '/') else s
        return java.util.Base64.getMimeDecoder().decode(normalized)
    }

    @JvmStatic
    fun hex2byte(s: String): ByteArray {
        val bytes = ByteArray(s.length / 2)
        for (i in bytes.indices) bytes[i] = Integer.valueOf(s.substring(i * 2, i * 2 + 2), 16).toByte()
        return bytes
    }

    @JvmStatic
    fun containOrMatch(text: String?, regex: String?): Boolean = try {
        text!!.contains(regex!!) || text.matches(regex.toRegex())
    } catch (e: Exception) {
        false
    }

    @JvmStatic
    fun substring(text: String?): String? = substring(text, 1)

    @JvmStatic
    fun substring(text: String?, num: Int): String? {
        if (text != null && text.length > num) return text.substring(0, text.length - num)
        return text
    }

    @JvmStatic
    fun getIp(): String = try {
        var ip = getHostAddress("wlan")
        if (ip.isEmpty()) ip = getHostAddress("eth")
        if (ip.isEmpty()) ip = getHostAddress("")
        ip
    } catch (e: Exception) {
        ""
    }

    private fun getHostAddress(keyword: String): String = try {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val nif = en.nextElement()
            if (keyword.isNotEmpty() && !nif.name.startsWith(keyword)) continue
            for (addresses in nif.inetAddresses) {
                val addr = addresses
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                return addr.hostAddress ?: ""
            }
        }
        ""
    } catch (ignored: Exception) {
        ""
    }
}

