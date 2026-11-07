package dev.q25.bb7pulldown;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * Restricted SystemUI-only action for the foreground application's task.
 * Never force-stops a package or removes a task inferred from Recents history.
 */
final class ForegroundTaskActions {
    private static final String TAG = "BB7-AppMenu";
    private static final String REMOVE_TASKS = "android.permission.REMOVE_TASKS";

    static final class Target {
        final int taskId;
        final String packageName;

        Target(int taskId, String packageName) {
            this.taskId = taskId;
            this.packageName = packageName;
        }
    }

    private ForegroundTaskActions() {}

    /** Capture the actual top task BEFORE BB UI's own popup takes input focus. */
    static Target currentApplication(Context context) {
        if (context.checkSelfPermission(REMOVE_TASKS)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return null;
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            ActivityManager.RunningTaskInfo task = tasks.get(0);
            if (task == null || task.topActivity == null || task.id <= 0) return null;
            String pkg = task.topActivity.getPackageName();
            if (pkg == null || "com.android.systemui".equals(pkg)
                    || "com.android.launcher3".equals(pkg)
                    || "com.google.android.apps.nexuslauncher".equals(pkg)) {
                return null;
            }

            // Do not offer Close Application on the HOME task, even when
            // a third-party launcher uses a package name unknown to BB UI.
            Intent base = task.baseIntent;
            if (base != null && base.hasCategory(Intent.CATEGORY_HOME)) return null;
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(home,
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null
                    && pkg.equals(resolved.activityInfo.packageName)) return null;

            return new Target(task.id, pkg);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot identify privileged foreground task; hiding Close", error);
            return null;
        }
    }

    static boolean closeCapturedApplication(Context context, Target target) {
        if (target == null) return false;
        Target current = currentApplication(context);
        if (current == null || current.taskId != target.taskId
                || !current.packageName.equals(target.packageName)) {
            Log.w(TAG, "Close rejected: original application no longer foreground");
            return false;
        }
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object service = XposedHelpers.callStaticMethod(atm, "getService");
            Object removed = XposedHelpers.callMethod(service, "removeTask", target.taskId);
            boolean ok = Boolean.TRUE.equals(removed);
            if (ok) Log.i(TAG, "Removed foreground application task: " + target.packageName);
            else Log.w(TAG, "System task manager rejected close: " + target.packageName);
            return ok;
        } catch (Throwable error) {
            Log.e(TAG, "Native task removal unavailable; no app force-stop attempted", error);
            return false;
        }
    }
}
