package com.wa2port.core.update

/**
 * Semantic version (`major.minor.patch[-preRelease]`) as used by release tags (`v1.2.0`,
 * `1.2.0-beta.1`). Build metadata (`+…`) is ignored, as semver §10 requires.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {

    /** Release (no pre-release) sorts above any pre-release of the same core version (semver §11). */
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    val isPreRelease: Boolean get() = preRelease != null

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?$""")

        /**
         * Parses `1.2.0`, `v1.2.0`, `V1.2`, `1.2.0-beta1`, `1.2.0+build.5`; null when unparsable
         * (including numeric overflow or empty pre-release identifiers like `1.2.0-beta..1`).
         */
        fun parse(text: String?): SemanticVersion? {
            var s = text?.trim() ?: return null
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            s = s.substringBefore('+')
            val m = PATTERN.matchEntire(s) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val pre = m.groupValues[4].ifEmpty { null }
            if (pre != null && pre.split('.').any { it.isEmpty() }) return null
            return SemanticVersion(major, minor, patch, pre)
        }

        /** True only when both parse and [latestTag] is strictly newer (fail-safe: never nag on garbage). */
        fun isNewerVersion(currentVersion: String?, latestTag: String?): Boolean {
            val current = parse(currentVersion) ?: return false
            val latest = parse(latestTag) ?: return false
            return latest > current
        }

        /**
         * Semver §11 pre-release precedence: null (release) > any pre-release; otherwise compare
         * dot-separated identifiers left to right — numeric ones numerically, numeric < alphanumeric,
         * alphanumeric lexically (ASCII); a shorter prefix sorts lower.
         */
        private fun comparePreRelease(a: String?, b: String?): Int {
            if (a == b) return 0
            if (a == null) return 1
            if (b == null) return -1
            val pa = a.split('.')
            val pb = b.split('.')
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val nx = x.toBigIntegerOrNull()?.takeIf { x.all(Char::isDigit) }
                val ny = y.toBigIntegerOrNull()?.takeIf { y.all(Char::isDigit) }
                val c = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1
                    ny != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
