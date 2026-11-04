#!/usr/bin/env python3
"""Apply the native BBOS6 keyguard blueprint to LineageOS 24 source.

This tool modifies only frameworks/base/packages/SystemUI source. It never
flashes a device, installs modules, or changes a running Android system.

Usage:
    python3 apply_native.py --check ~/android/lineage
    python3 apply_native.py ~/android/lineage

The command refuses unknown source revisions and validates all edits before
writing any file. Run it from a clean LineageOS 24 checkout.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = Path("packages/SystemUI/src/com/android/systemui")
BLUEPRINTS = SRC / "keyguard/ui/view/layout/blueprints"
SECTIONS = SRC / "keyguard/ui/view/layout/sections"
INTERACTOR = SRC / "keyguard/domain/interactor/KeyguardBlueprintInteractor.kt"
MODULE = BLUEPRINTS / "KeyguardBlueprintModule.kt"

ADD_FILES = (
    BLUEPRINTS / "Bb6KeyguardBlueprint.kt",
    SECTIONS / "Bb6HeaderSection.kt",
)

def replace_exact(text: str, old: str, new: str, name: str) -> str:
    if text.count(old) != 1:
        raise ValueError(
            f"{name}: expected source anchor exactly once, found {text.count(old)}"
        )
    return text.replace(old, new, 1)

def edit_module(text: str) -> str:
    if "bindBb6Blueprint(" in text:
        return text
    marker = (
        "    @Binds\n"
        "    @IntoSet\n"
        "    abstract fun bindSplitShadeBlueprint("
    )
    addition = (
        "    @Binds\n"
        "    @IntoSet\n"
        "    abstract fun bindBb6Blueprint(\n"
        "        bb6Blueprint: Bb6KeyguardBlueprint\n"
        "    ): KeyguardBlueprint\n\n"
    )
    return replace_exact(text, marker, addition + marker, "KeyguardBlueprintModule")

def edit_interactor(text: str) -> str:
    if "Bb6KeyguardBlueprint.ID" in text:
        return text
    text = replace_exact(
        text,
        "import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint\n",
        "import com.android.systemui.keyguard.ui.view.layout.blueprints.Bb6KeyguardBlueprint\n"
        "import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint\n",
        "KeyguardBlueprintInteractor import (blueprint)",
    )
    text = replace_exact(
        text,
        "import com.android.systemui.keyguard.shared.model.KeyguardBlueprint\n",
        "import com.android.systemui.keyguard.shared.model.KeyguardBlueprint\n"
        "import com.android.systemui.keyguard.shared.model.StatusBarState\n",
        "KeyguardBlueprintInteractor import (state)",
    )
    text = replace_exact(
        text,
        "import com.android.systemui.scene.shared.flag.SceneContainerFlag\n",
        "import com.android.systemui.scene.shared.flag.SceneContainerFlag\n"
        "import com.android.systemui.power.domain.interactor.PowerInteractor\n",
        "KeyguardBlueprintInteractor import (power)",
    )
    text = replace_exact(
        text,
        "import kotlinx.coroutines.flow.StateFlow\n",
        "import kotlinx.coroutines.flow.StateFlow\n"
        "import kotlinx.coroutines.flow.combine\n"
        "import kotlinx.coroutines.flow.distinctUntilChanged\n",
        "KeyguardBlueprintInteractor flow imports",
    )
    text = replace_exact(
        text,
        "    private val smartspaceSection: SmartspaceSection,\n",
        "    private val smartspaceSection: SmartspaceSection,\n"
        "    private val keyguardInteractor: KeyguardInteractor,\n"
        "    private val powerInteractor: PowerInteractor,\n",
        "KeyguardBlueprintInteractor injected services",
    )

    original = (
        "    /** Current BlueprintId */\n"
        "    val blueprintId =\n"
        "        shadeModeInteractor.shadeMode.map { shadeMode ->\n"
        "            when {\n"
        "                shadeMode is ShadeMode.Split && !SceneContainerFlag.isEnabled ->\n"
        "                    SplitShadeKeyguardBlueprint.ID\n"
        "                else -> DefaultKeyguardBlueprint.DEFAULT\n"
        "            }\n"
        "        }\n"
    )
    replacement = (
        "    /**\n"
        "     * BB6 owns only the INTERACTIVE, non-occluded, non-bouncer lockscreen.\n"
        "     * Default blueprint is fully restored for AOD/doze, unlocked shade,\n"
        "     * locked shade, bouncer and other states. No live View detachment.\n"
        "     */\n"
        "    private val bb6Eligible =\n"
        "        combine(\n"
        "            keyguardInteractor.isDozing,\n"
        "            keyguardInteractor.isKeyguardShowing,\n"
        "            keyguardInteractor.isKeyguardOccluded,\n"
        "            powerInteractor.isInteractive,\n"
        "            keyguardInteractor.primaryBouncerShowing,\n"
        "        ) { dozing, showing, occluded, interactive, bouncer ->\n"
        "            showing && interactive && !dozing && !occluded && !bouncer\n"
        "        }\n\n"
        "    /** Current BlueprintId, including Q25's native BB6 variant. */\n"
        "    val blueprintId =\n"
        "        combine(\n"
        "            shadeModeInteractor.shadeMode,\n"
        "            bb6Eligible,\n"
        "            keyguardInteractor.statusBarState,\n"
        "        ) { shadeMode, eligible, state ->\n"
        "            when {\n"
        "                shadeMode is ShadeMode.Split && !SceneContainerFlag.isEnabled ->\n"
        "                    SplitShadeKeyguardBlueprint.ID\n"
        "                eligible && state == StatusBarState.KEYGUARD ->\n"
        "                    Bb6KeyguardBlueprint.ID\n"
        "                else -> DefaultKeyguardBlueprint.DEFAULT\n"
        "            }\n"
        "        }.distinctUntilChanged()\n"
    )
    return replace_exact(
        text, original, replacement, "KeyguardBlueprintInteractor blueprintId"
    )

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lineage_root", type=Path)
    parser.add_argument(
        "--check", action="store_true",
        help="validate upstream anchors without modifying files"
    )
    args = parser.parse_args()

    lineage_root = args.lineage_root.resolve()
    frameworks_base = lineage_root / "frameworks/base"
    interactor_path = frameworks_base / INTERACTOR
    module_path = frameworks_base / MODULE

    modifications: dict[Path, str] = {}
    for target, editor in (
        (interactor_path, edit_interactor),
        (module_path, edit_module),
    ):
        if not target.is_file():
            parser.error(f"Official LineageOS 24 source missing: {target}")
        modifications[target] = editor(target.read_text(encoding="utf-8"))

    for target_relative in ADD_FILES:
        source = HERE / "native-src" / target_relative
        target = frameworks_base / target_relative
        if not source.is_file():
            parser.error(f"BB6 source file missing: {source}")
        source_text = source.read_text(encoding="utf-8")
        if target.exists() and target.read_text(encoding="utf-8") != source_text:
            parser.error(f"Existing native source differs; refusing overwrite: {target}")
        modifications[target] = source_text

    if args.check:
        for path in modifications:
            print(f"CHECK OK {path.relative_to(lineage_root)}")
        return 0

    for target, new_text in modifications.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists() or target.read_text(encoding="utf-8") != new_text:
            target.write_text(new_text, encoding="utf-8")
            print(f"APPLIED {target.relative_to(lineage_root)}")
        else:
            print(f"UNCHANGED {target.relative_to(lineage_root)}")
    print("Source integration complete; full Q25 ROM compile and device testing remain required.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
