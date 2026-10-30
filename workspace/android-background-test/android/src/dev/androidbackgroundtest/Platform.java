package dev.androidbackgroundtest;

import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.Process;
import android.view.InputEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Shell-identity access to the system services the coordinator needs. Hidden APIs are reached by reflection. */
final class Platform {
    static final String SHELL_PACKAGE = "com.android.shell";

    private static Context context;
    private static Object activityTasks;
    private static Object windowManager;
    private static Object powerManager;
    private static Object inputManager;
    private static Method injectInputEvent;
    private static Method setDisplayId;

    private Platform() {}

    static synchronized Context context() throws Exception {
        if (context == null) {
            Class<?> type = Class.forName("android.app.ActivityThread");
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object thread = constructor.newInstance();
            Field current = type.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, thread);
            Method system = type.getDeclaredMethod("getSystemContext");
            system.setAccessible(true);
            context = new ShellContext((Context) system.invoke(thread));
        }
        return context;
    }

    private static Object service(String name, String iface) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        return Class.forName(iface + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static synchronized Object tasks() throws Exception {
        if (activityTasks == null) {
            activityTasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        }
        return activityTasks;
    }

    static boolean deviceLocked() throws Exception {
        synchronized (Platform.class) {
            if (windowManager == null) windowManager = service("window", "android.view.IWindowManager");
            if (powerManager == null) powerManager = service("power", "android.os.IPowerManager");
        }
        boolean interactive = (Boolean) powerManager.getClass().getMethod("isInteractive").invoke(powerManager);
        boolean keyguard = (Boolean) windowManager.getClass().getMethod("isKeyguardLocked").invoke(windowManager);
        return !interactive || keyguard;
    }

    static int currentUser() throws Exception {
        return (Integer) ActivityManager.class.getMethod("getCurrentUser").invoke(null);
    }

    /** One leaf task as seen by the activity task manager. */
    static final class Task {
        final int id;
        final int rootId;
        final int displayId;
        final boolean visible;
        final String packageName;

        Task(int id, int rootId, int displayId, boolean visible, String packageName) {
            this.id = id;
            this.rootId = rootId;
            this.displayId = displayId;
            this.visible = visible;
            this.packageName = packageName;
        }
    }

    static List<Task> allTasks() throws Exception {
        Object service = tasks();
        List<?> roots = (List<?>) service.getClass().getMethod("getAllRootTaskInfos").invoke(service);
        List<Task> result = new ArrayList<>();
        for (Object root : roots) {
            Class<?> type = root.getClass();
            int rootId = type.getField("taskId").getInt(root);
            int displayId = type.getField("displayId").getInt(root);
            boolean visible = type.getField("visible").getBoolean(root);
            int[] children = (int[]) type.getField("childTaskIds").get(root);
            String[] names = (String[]) type.getField("childTaskNames").get(root);
            if (children == null || children.length == 0) {
                ComponentName top = (ComponentName) type.getField("topActivity").get(root);
                result.add(new Task(rootId, rootId, displayId, visible, top == null ? null : top.getPackageName()));
                continue;
            }
            for (int i = 0; i < children.length; i++) {
                String name = names != null && i < names.length ? names[i] : null;
                ComponentName component = name == null ? null : ComponentName.unflattenFromString(name);
                result.add(new Task(children[i], rootId, displayId, visible,
                        component == null ? null : component.getPackageName()));
            }
        }
        return result;
    }

    /** True when the user has a task for the package outside the given display, including recents history. */
    static boolean hasRecentTask(String packageName, int user, int excludedDisplay) throws Exception {
        Object service = tasks();
        Object slice = service.getClass().getMethod("getRecentTasks", int.class, int.class, int.class)
                .invoke(service, 100, 0, user);
        List<?> list = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
        for (Object info : list) {
            Intent intent = (Intent) info.getClass().getField("baseIntent").get(info);
            ComponentName component = intent == null ? null : intent.getComponent();
            int display = info.getClass().getField("displayId").getInt(info);
            if (display != excludedDisplay && component != null && packageName.equals(component.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    static boolean removeTask(int taskId) throws Exception {
        Object service = tasks();
        return (Boolean) service.getClass().getMethod("removeTask", int.class).invoke(service, taskId);
    }

    static void moveRootTaskToDisplay(int rootTaskId, int displayId) throws Exception {
        Object service = tasks();
        service.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(service, rootTaskId, displayId);
    }

    /** The app's display name in the device language, or null when the package manager cannot provide one. */
    static String appLabel(String packageName) {
        try {
            PackageManager packages = context().getPackageManager();
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            CharSequence label = info.nonLocalizedLabel;
            // The shell context cannot load other apps' resources (no ConfigurationController), and loadLabel()
            // then falls back to the Application class name. Load the APK resources directly with the device config.
            if (label == null && info.labelRes != 0) {
                AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
                try {
                    AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, info.sourceDir);
                    Resources system = Resources.getSystem();
                    label = new Resources(assets, system.getDisplayMetrics(), deviceConfiguration())
                            .getText(info.labelRes);
                } finally {
                    assets.close();
                }
            }
            String text = label == null ? "" : label.toString().trim();
            return text.isEmpty() || text.equals(packageName) || text.equals(info.name) ? null : text;
        } catch (Exception error) {
            Coordinator.log("app label failed for " + packageName + ": " + error);
            return null;
        }
    }

    /** The device's global configuration, so labels follow the phone language rather than the process default. */
    private static Configuration deviceConfiguration() {
        try {
            Object service = ActivityManager.class.getMethod("getService").invoke(null);
            return (Configuration) service.getClass().getMethod("getConfiguration").invoke(service);
        } catch (Exception unavailable) {
            return Resources.getSystem().getConfiguration();
        }
    }

    /** Launch modes that make the system reuse (and move) an existing user task. */
    static boolean reusesExistingTask(ComponentName component, int user) {
        try {
            ActivityInfo info = context().getPackageManager().getActivityInfo(component, 0);
            return info.launchMode != ActivityInfo.LAUNCH_MULTIPLE && info.launchMode != ActivityInfo.LAUNCH_SINGLE_TOP;
        } catch (Exception unknown) {
            return true;
        }
    }

    static synchronized boolean inject(InputEvent event, int displayId) throws Exception {
        if (inputManager == null) {
            try {
                Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
                inputManager = global.getMethod("getInstance").invoke(null);
            } catch (ClassNotFoundException old) {
                Method instance = android.hardware.input.InputManager.class.getDeclaredMethod("getInstance");
                inputManager = instance.invoke(null);
            }
            injectInputEvent = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        }
        setDisplayId.invoke(event, displayId);
        return (Boolean) injectInputEvent.invoke(inputManager, event, 0);
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL_PACKAGE; }
        @Override public String getOpPackageName() { return SHELL_PACKAGE; }
        @Override public Context getApplicationContext() { return this; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PACKAGE).build();
        }
    }
}
