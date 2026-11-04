/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.blueprints

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.sections.AccessibilityActionsSection
import com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection
import com.android.systemui.keyguard.ui.view.layout.sections.Bb6HeaderSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntrySection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultIndicationAreaSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultStatusBarSection
import com.android.systemui.keyguard.ui.view.layout.sections.DefaultUdfpsAccessibilityOverlaySection
import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSectionsModule.Companion.KEYGUARD_AMBIENT_INDICATION_AREA_SECTION
import com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSectionsModule.Companion.KEYGUARD_BATTERY_CHARGING_SECTION
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import kotlin.jvm.optionals.getOrNull

/**
 * Interactive-only BBOS6 lockscreen.
 *
 * No stock ClockSection, SmartspaceSection, KeyguardSliceViewSection,
 * notification placeholder, promoted notification row, or AOD icon row
 * is CONSTRUCTED by this blueprint. The native default blueprint rebuilds
 * those sections for AOD, shade, and other original modes.
 *
 * Keep the AodBurnInSection in both blueprints: its required anchor and
 * references must remain valid across a transition. We do not detach or
 * reparent clock views directly.
 *
 * KeyguardStatusBarView is retained as the sole carrier/system icon owner.
 * Bb6HeaderSection draws only clock/date and notification icon counts.
 * Password/PIN/pattern and emergency access remain native.
 */
@SysUISingleton
@JvmSuppressWildcards
class Bb6KeyguardBlueprint
@Inject constructor(
    accessibilityActionsSection: AccessibilityActionsSection,
    defaultIndicationAreaSection: DefaultIndicationAreaSection,
    defaultDeviceEntrySection: DefaultDeviceEntrySection,
    defaultShortcutsSection: DefaultShortcutsSection,
    @Named(KEYGUARD_AMBIENT_INDICATION_AREA_SECTION)
    defaultAmbientIndicationAreaSection: Optional<KeyguardSection>,
    defaultSettingsPopupMenuSection: DefaultSettingsPopupMenuSection,
    @Named(KEYGUARD_BATTERY_CHARGING_SECTION)
    batteryChargingPopupMenuSection: Optional<KeyguardSection>,
    defaultStatusBarSection: DefaultStatusBarSection,
    aodBurnInSection: AodBurnInSection,
    bb6HeaderSection: Bb6HeaderSection,
    udfpsAccessibilityOverlaySection: DefaultUdfpsAccessibilityOverlaySection,
) : KeyguardBlueprint {
    override val id: String = ID

    override val sections = listOfNotNull(
        accessibilityActionsSection,
        defaultIndicationAreaSection,
        defaultShortcutsSection,
        defaultAmbientIndicationAreaSection.getOrNull(),
        defaultSettingsPopupMenuSection,
        batteryChargingPopupMenuSection.getOrNull(),
        defaultStatusBarSection,
        aodBurnInSection,
        bb6HeaderSection,
        defaultDeviceEntrySection,
        udfpsAccessibilityOverlaySection,
    )

    companion object {
        const val ID = "bb6-interactive"
    }
}
