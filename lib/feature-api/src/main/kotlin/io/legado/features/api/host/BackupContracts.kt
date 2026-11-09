package io.legado.features.api.host

data class FeatureBackupEntry(val id: String, val version: Int, val payload: String)
fun interface PreparedFeatureRestore { fun apply() }
interface FeatureBackupContributor {
    val id: String
    val version: Int
    fun snapshot(): String
    /** Validate the complete payload before applying any contributor. */
    fun prepare(payload: String): PreparedFeatureRestore
}
enum class PreferenceType { STRING, BOOLEAN, INT, LONG, FLOAT, STRING_SET }
data class PortablePreference(val type: PreferenceType, val value: String, val values: List<String> = emptyList())
interface FeaturePreferencePort {
    fun snapshot(): Map<String, Map<String, PortablePreference>>
    /** Replace only the named groups. A missing group in an old archive is untouched. */
    fun replace(groups: Map<String, Map<String, PortablePreference>>)
}
