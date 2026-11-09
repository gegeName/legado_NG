package io.legado.features.runtime.trust

import io.legado.features.runtime.ExtensionPrefs

object TrustHelper {

    fun isTrusted(pkgName: String, versionCode: Long, fingerprints: List<String>): Boolean {
        if (fingerprints.isEmpty()) return false
        val repoKeys = ExtensionPrefs.getRepos()
            .mapTo(HashSet()) { it.signingKeyFingerprint.lowercase() }
        if (fingerprints.any { it.lowercase() in repoKeys }) return true

        val key = "$pkgName:$versionCode:${fingerprints.last()}"
        return key in ExtensionPrefs.getTrustedSignatures()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        val trusted = ExtensionPrefs.getTrustedSignatures()

            .filterNot { it.startsWith("$pkgName:") }
            .toMutableSet()
        trusted += "$pkgName:$versionCode:$signatureHash"
        ExtensionPrefs.setTrustedSignatures(trusted)
    }

    fun revokeAll() {
        ExtensionPrefs.setTrustedSignatures(emptySet())
    }
}

