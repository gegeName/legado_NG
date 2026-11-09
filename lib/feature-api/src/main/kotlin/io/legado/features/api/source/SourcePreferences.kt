package io.legado.features.api.source

enum class SourcePreferenceKind { GROUP, TEXT, BOOLEAN, SELECT, MULTISELECT, NUMBER, ACTION, UNSUPPORTED }
data class SourcePreferenceValue(val text: String = "", val checked: Boolean = false, val selected: Set<String> = emptySet())
data class SourcePreferenceField(val path: List<Int>, val title: String, val summary: String,
    val kind: SourcePreferenceKind, val value: SourcePreferenceValue = SourcePreferenceValue(),
    val choices: List<Pair<String, String>> = emptyList(), val enabled: Boolean = true,
    val minimum: Int = 0, val maximum: Int = 100, val children: List<SourcePreferenceField> = emptyList())

/** Platform/ABI preference objects remain owned by a single short-lived session. */
interface SourcePreferenceSession : AutoCloseable {
    fun describe(): List<SourcePreferenceField>
    fun set(path: List<Int>, value: SourcePreferenceValue): Boolean
    fun activate(path: List<Int>): Boolean
    override fun close()
}
