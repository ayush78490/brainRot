package com.reeltracker.service

import android.view.accessibility.AccessibilityNodeInfo
import com.reeltracker.model.IgAccount

/**
 * Reads the logged-in account off Instagram's own profile screen. Looked up by view id, never by
 * walking the tree. IDs from a uiautomator dump of Instagram 451.0.0.53.80 (2026-10-20).
 *
 * "Own profile" = the Profile tab is selected. Other people's profiles open under Home or Search,
 * so this holds without reading any localized label like "Edit profile".
 */
internal object ProfileReader {
    private const val IG = "com.instagram.android"
    private const val PROFILE_TAB = "$IG:id/profile_tab"
    private const val TITLE = "$IG:id/action_bar_title"
    private val FULL_NAME = listOf("$IG:id/profile_header_full_name_above_vanity", "$IG:id/profile_header_full_name")

    fun read(root: AccessibilityNodeInfo?): IgAccount? {
        if (root == null || root.packageName?.toString() != IG) return null
        val tab = root.findAccessibilityNodeInfosByViewId(PROFILE_TAB).firstOrNull() ?: return null
        if (!tab.isSelected) return null
        val title = root.findAccessibilityNodeInfosByViewId(TITLE).firstOrNull()?.text?.toString()
        val username = IgAccount.cleanUsername(title) ?: return null
        val name = FULL_NAME.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString()?.let(IgAccount::cleanFullName)
        }
        return IgAccount(username, name)
    }
}
