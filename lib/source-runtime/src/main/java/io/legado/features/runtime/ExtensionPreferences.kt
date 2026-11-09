package io.legado.features.runtime

import android.content.Context
import android.content.Intent
import androidx.preference.*
import io.legado.features.api.source.*

/** Adapts common AndroidX settings, retaining extension validators and persistence semantics. */
class ExtensionPreferences(context: Context, source: SourceKey, setup: (PreferenceScreen) -> Unit) : SourcePreferenceSession {
    private val manager = PreferenceManager(context).apply { sharedPreferencesName = "source_${source.nativeId}" }
    private var root: PreferenceScreen? = manager.createPreferenceScreen(context).also(setup)
    private fun preference(path: List<Int>): Preference {
        var current: Preference = checkNotNull(root) { "来源设置已关闭" }
        for (index in path) current = (current as? PreferenceGroup)?.getPreference(index) ?: error("设置项已变化")
        return current
    }
    override fun describe(): List<SourcePreferenceField> {
        fun children(group: PreferenceGroup, parent: List<Int>): List<SourcePreferenceField> = (0 until group.preferenceCount).mapNotNull { index ->
            val p = group.getPreference(index)
            if (!p.isVisible) return@mapNotNull null
            val path = parent + index
            val kind = when (p) {
                is PreferenceGroup -> SourcePreferenceKind.GROUP
                is TwoStatePreference -> SourcePreferenceKind.BOOLEAN
                is ListPreference -> SourcePreferenceKind.SELECT
                is MultiSelectListPreference -> SourcePreferenceKind.MULTISELECT
                is EditTextPreference -> SourcePreferenceKind.TEXT
                is SeekBarPreference -> SourcePreferenceKind.NUMBER
                else -> if (p.onPreferenceClickListener != null || p.intent != null) SourcePreferenceKind.ACTION else SourcePreferenceKind.UNSUPPORTED
            }
            val value = when (p) {
                is TwoStatePreference -> SourcePreferenceValue(checked = p.isChecked)
                is ListPreference -> SourcePreferenceValue(text = p.value.orEmpty())
                is MultiSelectListPreference -> SourcePreferenceValue(selected = p.values.toSet())
                is EditTextPreference -> SourcePreferenceValue(text = p.text.orEmpty())
                is SeekBarPreference -> SourcePreferenceValue(text = p.value.toString())
                else -> SourcePreferenceValue()
            }
            val choices = when (p) {
                is ListPreference -> p.entryValues.orEmpty().mapIndexed { i, value -> value.toString() to (p.entries?.getOrNull(i)?.toString() ?: value.toString()) }
                is MultiSelectListPreference -> p.entryValues.orEmpty().mapIndexed { i, value -> value.toString() to (p.entries?.getOrNull(i)?.toString() ?: value.toString()) }
                else -> emptyList()
            }
            SourcePreferenceField(path, p.title?.toString().orEmpty(), p.summary?.toString().orEmpty(), kind, value, choices,
                p.isEnabled, (p as? SeekBarPreference)?.min ?: 0, (p as? SeekBarPreference)?.max ?: 100,
                if (p is PreferenceGroup) children(p, path) else emptyList())
        }
        return children(checkNotNull(root), emptyList())
    }
    override fun set(path: List<Int>, value: SourcePreferenceValue): Boolean {
        val p = preference(path)
        check(p.isEnabled && p.isVisible) { "此设置不可修改" }
        val raw: Any = when (p) {
            is TwoStatePreference -> value.checked
            is ListPreference -> value.text.also { require(it in p.entryValues.orEmpty().map(CharSequence::toString)) }
            is MultiSelectListPreference -> value.selected.also { require(p.entryValues.orEmpty().map(CharSequence::toString).containsAll(it)) }
            is EditTextPreference -> value.text
            is SeekBarPreference -> value.text.toInt().also { require(it in p.min..p.max) }
            else -> error("此设置需要扩展自定义界面")
        }
        if (!p.callChangeListener(raw)) return false
        when (p) {
            is TwoStatePreference -> p.isChecked = raw as Boolean
            is ListPreference -> p.value = raw as String
            is MultiSelectListPreference -> { @Suppress("UNCHECKED_CAST") p.values = raw as Set<String> }
            is EditTextPreference -> p.text = raw as String
            is SeekBarPreference -> p.value = raw as Int
        }
        return true
    }
    override fun activate(path: List<Int>): Boolean {
        val p = preference(path)
        check(p.isEnabled && p.isVisible)
        if (p.onPreferenceClickListener?.onPreferenceClick(p) == true) return true
        val intent = p.intent ?: return false
        p.context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
    override fun close() { root = null }
}
