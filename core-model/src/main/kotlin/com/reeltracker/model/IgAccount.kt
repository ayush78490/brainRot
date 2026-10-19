package com.reeltracker.model

/** The Instagram account the phone is logged into, as read off its own profile screen. */
data class IgAccount(val username: String, val fullName: String?) {

    companion object {
        private val VALID = Regex("^[a-z0-9._]{1,30}$")

        /**
         * The action bar title carries decoration around the handle: leading spaces for an icon,
         * sometimes a dropdown arrow or a badge. Keep the first run that is a valid handle.
         */
        fun cleanUsername(raw: String?): String? {
            if (raw == null) return null
            return raw.lowercase()
                .split(Regex("[^a-z0-9._]+"))
                .firstOrNull { it.isNotEmpty() }
                ?.takeIf { VALID.matches(it) }
        }

        fun cleanFullName(raw: String?): String? =
            raw?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
    }
}
