package com.eshwar.reelplay.report

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.eshwar.reelplay.BuildConfig

/**
 * Takes personal details out of a report before the user sees it and before it's sent: the
 * phone's name (often "<owner>'s Galaxy Tab"), the owner's name taken from it, email, IP and
 * hardware (MAC) addresses, and the app's own package name, which shows up in every stack
 * trace and log line.
 */
object Redactor {

    /** Names this phone goes by that may appear in logs. Best effort: newer Android hides some. */
    fun personalTerms(context: Context): Set<String> {
        val names = listOfNotNull(
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull(),
        )
        return termsFromDeviceNames(names, Build.MODEL)
    }

    /** The device names themselves, plus the owner in "Eshwar's Tab" / "Tab de Eshwar" style names. */
    internal fun termsFromDeviceNames(names: List<String>, model: String): Set<String> {
        val terms = HashSet<String>()
        for (raw in names) {
            val name = raw.trim()
            if (name.length < 3) continue
            // A bare model name ("SM-X930", "Galaxy Tab S11") says nothing about the owner.
            val generic = name.equals(model, true) || GENERIC_DEVICE.containsMatchIn(name) && !name.contains('\'') && !name.contains('’')
            if (!generic) terms += name
            Regex("""^(.+?)['’]s?\s""").find(name)?.groupValues?.get(1)?.trim()?.let { if (it.length >= 3) terms += it }
            Regex("""(?i)\b(?:de|di|von|of)\s+(\p{L}{3,})$""").find(name)?.groupValues?.get(1)?.let { terms += it }
        }
        return terms
    }

    fun redact(text: String, terms: Collection<String>, packageName: String = BuildConfig.APPLICATION_ID): String {
        // Emails first, so a name inside one doesn't leave "[name]@..." behind.
        var out = text.replace(packageName, "app").replace(EMAIL, "[email]")
        // Longest first, so "Eshwar's Tab" goes before "Eshwar" would split it.
        for (term in terms.filter { it.length >= 3 }.sortedByDescending { it.length }) {
            out = out.replace(Regex(Regex.escape(term), RegexOption.IGNORE_CASE), "[name]")
        }
        return out
            .replace(MAC, "[mac]")
            .replace(IPV4, "[ip]")
    }

    private val GENERIC_DEVICE = Regex("""(?i)^(?:(?:galaxy|pixel|redmi|oneplus|moto|nokia|xiaomi|poco|realme|oppo|vivo|tab|android)\b|sm-)""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    // Home, mobile and public addresses start at 10 or above (192.168..., 10...); four-part
    // version numbers (2.1.0.39) start low, so they're left alone.
    private val IPV4 = Regex("""(?<![\w.])(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}(?![\w.])""")
}
