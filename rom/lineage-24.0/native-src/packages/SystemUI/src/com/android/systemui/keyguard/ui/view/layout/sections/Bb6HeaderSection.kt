/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0
 *
 * Native BBOS6 keyguard header. This is a SystemUI KeyguardSection, not
 * an LSPosed/Vector overlay. No native clock, date or status view is hidden.
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.Utils
import javax.inject.Inject

/**
 * SystemUI-owned BBOS6 clock/date and active-notification indicator row.
 *
 * The native KeyguardStatusBarView remains the sole owner of carrier,
 * cellular signal, Wi-Fi, battery, privacy and system status indicators.
 *
 * This section never receives key input and never touches credential UI.
 */
@SysUISingleton
class Bb6HeaderSection
@Inject constructor(
    @ShadeDisplayAware private val context: Context,
    private val notifPipeline: NotifPipeline,
    private val lockscreenUsers: NotificationLockscreenUserManager,
) : KeyguardSection() {
    private val headerId = View.generateViewId()
    private var rootView: LinearLayout? = null
    private var iconRow: LinearLayout? = null
    private var listening = false

    private val notificationsListener = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) = requestRefresh()
        override fun onEntryUpdated(entry: NotificationEntry) = requestRefresh()
        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) = requestRefresh()
        override fun onRankingApplied() = requestRefresh()
    }

    private val privacyListener =
        object : NotificationLockscreenUserManager.NotificationStateChangedListener {
            override fun onNotificationStateChanged() = requestRefresh()
        }

    private val userListener =
        object : NotificationLockscreenUserManager.UserChangedListener {
            override fun onUserChanged(userId: Int) = requestRefresh()
        }

    override fun addViews(constraintLayout: ConstraintLayout) {
        val root = LinearLayout(context).apply {
            id = headerId
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(5))
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }

        val clockLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        val date = TextClock(context).apply {
            format12Hour = "EEE, MMM d"
            format24Hour = "EEE, MMM d"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isSingleLine = true
            isFocusable = false
        }
        val time = TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 33f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            isSingleLine = true
            isFocusable = false
        }
        clockLine.addView(
            date, LinearLayout.LayoutParams(0, dp(49), 1f)
        )
        clockLine.addView(
            time, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(49)
            )
        )
        root.addView(clockLine)

        val divider = View(context).apply {
            setBackgroundColor(0x88ffffff.toInt())
            isFocusable = false
        }
        root.addView(
            divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
        )

        val strip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
        val icons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        strip.addView(icons)
        root.addView(
            strip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(31)
            )
        )
        iconRow = icons
        rootView = root
        constraintLayout.addView(root)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (listening) return
        listening = true
        notifPipeline.addCollectionListener(notificationsListener)
        lockscreenUsers.addNotificationStateChangedListener(privacyListener)
        lockscreenUsers.addUserChangedListener(userListener)
        requestRefresh()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(headerId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(headerId, ConstraintSet.WRAP_CONTENT)
            connect(
                headerId, ConstraintSet.TOP,
                ConstraintSet.PARENT_ID, ConstraintSet.TOP,
                Utils.getStatusBarHeaderHeightKeyguard(context)
            )
            connect(headerId, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(headerId, ConstraintSet.END,
                ConstraintSet.PARENT_ID, ConstraintSet.END)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (listening) {
            notifPipeline.removeCollectionListener(notificationsListener)
            lockscreenUsers.removeNotificationStateChangedListener(privacyListener)
            lockscreenUsers.removeUserChangedListener(userListener)
            listening = false
        }
        constraintLayout.removeView(headerId)
        iconRow = null
        rootView = null
    }

    private fun requestRefresh() {
        // Collection/ranking callbacks may be delivered while SystemUI is
        // processing a pipeline change. Read its snapshot on the UI thread.
        rootView?.post { renderCounts() }
    }

    private fun renderCounts() {
        val row = iconRow ?: return
        row.removeAllViews()
        if (!lockscreenUsers.shouldShowLockscreenNotifications()) return

        val entries = notifPipeline.allNotifs
        val visible = entries.filter { entry ->
            val sbn = entry.sbn
            val notification = sbn.notification
            val userId = sbn.user.identifier
            val visibility = entry.ranking.lockscreenVisibilityOverride
            lockscreenUsers.isCurrentProfile(userId) &&
                lockscreenUsers.userAllowsNotificationsInPublic(userId) &&
                lockscreenUsers.getRedactionType(entry) !=
                    NotificationLockscreenUserManager.REDACTION_TYPE_PUBLIC &&
                notification.visibility != Notification.VISIBILITY_SECRET &&
                visibility != Notification.VISIBILITY_SECRET &&
                entry.ranking.importance != NotificationManager.IMPORTANCE_NONE &&
                notification.flags and (
                    Notification.FLAG_ONGOING_EVENT or
                        Notification.FLAG_FOREGROUND_SERVICE
                    ) == 0
        }
        val childGroups = visible
            .filter { it.sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { it.sbn.groupKey }
            .toSet()
        val counts = linkedMapOf<String, Int>()
        for (entry in visible) {
            val sbn = entry.sbn
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
                sbn.groupKey in childGroups) continue
            counts[sbn.packageName] = (counts[sbn.packageName] ?: 0) + 1
        }

        for ((pkg, count) in counts) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false
                isClickable = false
            }
            val icon = ImageView(context).apply {
                isFocusable = false
                isClickable = false
                try {
                    setImageDrawable(context.packageManager.getApplicationIcon(pkg))
                } catch (_: PackageManager.NameNotFoundException) {
                    setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
            val label = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                text = if (count > 99) "99+" else count.toString()
                isFocusable = false
                isClickable = false
            }
            item.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) })
            row.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { marginEnd = dp(14) })
        }
        // The entire indicator strip naturally occupies no space if no apps
        // are notifying only after the native blueprint is reconstructed.
        (row.parent as? View)?.visibility =
            if (counts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int =
        (context.resources.displayMetrics.density * value + .5f).toInt()
}
