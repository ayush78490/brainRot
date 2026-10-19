package com.reeltracker.detect

/**
 * Identifiers for the Reels screen, observed in the M0 capture
 * (Instagram 447.0.0.55.81, OnePlus CPH2467, Android 15).
 *
 * When Instagram changes and counting stops, the fix is here: read the export, see what the
 * pager and tabs are called now, change the constants.
 *
 * Note: the Reels view tree stays alive while other tabs are showing, so "is the pager in the
 * tree" is NOT a Reels signal. Only the pager actually scrolling, or the Reels tab being
 * selected, is.
 */
object ReelsContext {
    const val PACKAGE = "com.instagram.android"

    private const val ID = "$PACKAGE:id/"
    const val PAGER_ID = "${ID}clips_viewer_view_pager"
    const val REELS_TAB_ID = "${ID}clips_tab"

    /** Bottom-nav tab ids look like `feed_tab`, `search_tab`, `profile_tab`. */
    private val tabId = Regex("""^${Regex.escape(ID)}\w+_tab$""")

    /** Windows inside Instagram that don't mean we've left Reels. */
    private val hostWindows = setOf("com.instagram.mainactivity.InstagramMainActivity")

    /** Windows from other packages that float over Instagram without replacing it. */
    private val overlayPackages = setOf("com.android.systemui", "com.reeltracker")

    fun isPager(sourceId: String?): Boolean = sourceId == PAGER_ID

    fun isReelsTab(sourceId: String?): Boolean = sourceId == REELS_TAB_ID

    fun isOtherTab(sourceId: String?): Boolean =
        sourceId != null && sourceId != REELS_TAB_ID && tabId.matches(sourceId)

    fun isHostWindow(cls: String?): Boolean = cls in hostWindows

    /** Brainrot's own screen replaces Instagram (an invite link, the profile capture); its overlay doesn't. */
    private const val OWN_ACTIVITY = "com.reeltracker.ui.MainActivity"

    fun isOverlay(pkg: String, cls: String?): Boolean = pkg in overlayPackages && cls != OWN_ACTIVITY
}
