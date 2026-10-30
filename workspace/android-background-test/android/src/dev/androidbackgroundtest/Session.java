package dev.androidbackgroundtest;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** One background test session: a private virtual display, the tasks launched on it, and its interruption state. */
final class Session {
    static final int MAIN_DISPLAY = 0;
    private static final int FLAG_ACTIVITY_NEW_DOCUMENT_MULTIPLE_TASK = 0x18080000;
    private static final Object TEXT_LOCK = new Object();

    final String id;
    final String token;
    final ComponentName component;
    final int user;
    final int width;
    final int height;
    private final Handler handler;
    private VirtualDisplay display;
    private ImageReader idle;
    private final Set<Integer> ownTasks = new LinkedHashSet<>();
    /** Every task that ever appeared on this display, so finished ones do not linger in recents. */
    private final Set<Integer> everOwned = new LinkedHashSet<>();
    private final Set<Integer> userTaken = new LinkedHashSet<>();

    String state = "running";
    String endReason;
    Interruption interruption;
    long lastUsed = SystemClock.elapsedRealtime();
    private int conflictCounter;
    private String label;
    private boolean labelLoaded;

    static final class Interruption {
        final String id;
        final String reason;
        final String detail;
        final String askUser;
        final boolean grantable;
        final int takenTaskId;
        long reportedAt = -1;

        Interruption(String id, String reason, String detail, String askUser, boolean grantable, int takenTaskId) {
            this.id = id;
            this.reason = reason;
            this.detail = detail;
            this.askUser = askUser;
            this.grantable = grantable;
            this.takenTaskId = takenTaskId;
        }
    }

    Session(String id, String token, ComponentName component, int user, int width, int height, Handler handler) {
        this.id = id;
        this.token = token;
        this.component = component;
        this.user = user;
        this.width = width;
        this.height = height;
        this.handler = handler;
    }

    String packageName() {
        return component.getPackageName();
    }

    /** App name for prompts shown to the user, such as 天气（com.coloros.weather2）. */
    String appName() {
        String label = label();
        return label == null ? packageName() : label + "（" + packageName() + "）";
    }

    /** Space around the name in Chinese sentences: only a bare package name needs it, full-width brackets do not. */
    String nameGap() {
        return label() == null ? " " : "";
    }

    private String label() {
        if (!labelLoaded) {
            label = Platform.appLabel(packageName());
            labelLoaded = true;
        }
        return label;
    }

    int displayId() {
        return display == null ? -1 : display.getDisplay().getDisplayId();
    }

    boolean live() {
        return !"ended".equals(state);
    }

    void createDisplay(int density) throws Exception {
        int flags = flag("OWN_CONTENT_ONLY") | flag("SUPPORTS_TOUCH") | flag("DESTROY_CONTENT_ON_REMOVAL")
                | flag("TRUSTED") | flag("OWN_FOCUS") | flag("STEAL_TOP_FOCUS_DISABLED") | flag("TOUCH_FEEDBACK_DISABLED");
        Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        DisplayManager displays = constructor.newInstance(Platform.context());
        idle = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2);
        idle.setOnImageAvailableListener(reader -> {
            try {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
            } catch (RuntimeException error) {
                Coordinator.log("idle frame drop failed: " + error);
            }
        }, handler);
        try {
            display = displays.createVirtualDisplay("android-background-test-" + id, width, height, density,
                    idle.getSurface(), flags);
        } catch (SecurityException denied) {
            idle.close();
            throw new Failure("display_permission_denied", String.valueOf(denied.getMessage()));
        }
        if (display == null) {
            idle.close();
            throw new Failure("display_create_failed", "createVirtualDisplay returned null");
        }
    }

    private static int flag(String name) throws Exception {
        Field field = DisplayManager.class.getDeclaredField("VIRTUAL_DISPLAY_FLAG_" + name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    /** Starts the component on this display. Standard activities get their own task so user tasks stay put. */
    void launch(boolean separateTask) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("am");
        command.add("start");
        command.add("-W");
        command.add("--display");
        command.add(String.valueOf(displayId()));
        command.add("--user");
        command.add(String.valueOf(user));
        if (separateTask) {
            command.add("-f");
            command.add(String.valueOf(FLAG_ACTIVITY_NEW_DOCUMENT_MULTIPLE_TASK));
        }
        command.add("-n");
        command.add(component.flattenToShortString());
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), "UTF-8");
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroy();
            throw new Failure("launch_failed", "am start timed out");
        }
        if (process.exitValue() != 0 || output.contains("Error")) {
            throw new Failure("launch_failed", output.trim());
        }
        trackTasks(Platform.allTasks());
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** Records tasks on this display and reports the first owned task the system moved to another display. */
    Platform.Task trackTasks(List<Platform.Task> tasks) {
        Platform.Task moved = null;
        Set<Integer> seen = new LinkedHashSet<>();
        for (Platform.Task task : tasks) {
            seen.add(task.id);
            if (task.displayId == displayId()) {
                ownTasks.add(task.id);
                everOwned.add(task.id);
                userTaken.remove(task.id);
            } else if (ownTasks.contains(task.id)) {
                ownTasks.remove(task.id);
                userTaken.add(task.id);
                if (moved == null) moved = task;
            }
        }
        ownTasks.retainAll(seen);
        return moved;
    }

    /** Tasks this session created and still owns, for crash recovery. */
    Set<Integer> recoverableTasks() {
        Set<Integer> tasks = new LinkedHashSet<>(everOwned);
        tasks.removeAll(userTaken);
        return tasks;
    }

    JSONArray ownTaskIds() {
        JSONArray array = new JSONArray();
        for (int task : ownTasks) array.put(task);
        return array;
    }

    Interruption interrupt(String reason, String detail, String askUser, boolean grantable, int takenTaskId) {
        state = "interrupted";
        interruption = new Interruption("c-" + id.substring(0, 6) + "-" + (++conflictCounter), reason, detail,
                askUser, grantable, takenTaskId);
        return interruption;
    }

    void resume() {
        state = "running";
        interruption = null;
    }

    /** Removes only tasks still on this display, then releases it. Tasks the user took stay with the user. */
    void end(String reason) {
        if (!live()) return;
        state = "ended";
        endReason = reason;
        interruption = null;
        try {
            Set<Integer> remove = new LinkedHashSet<>(everOwned);
            remove.removeAll(userTaken);
            for (Platform.Task task : Platform.allTasks()) {
                if (task.displayId == displayId()) remove.add(task.id);
                else remove.remove(task.id);
            }
            for (int task : remove) Platform.removeTask(task);
        } catch (Exception error) {
            Coordinator.log("remove tasks failed for " + id + ": " + error);
        }
        if (display != null) display.release();
        if (idle != null) idle.close();
        display = null;
        ownTasks.clear();
    }

    byte[] screenshot() throws Exception {
        trackTasks(Platform.allTasks());
        if (ownTasks.isEmpty()) {
            throw new Failure("no_task", "no app task on the session display; the app exited or was closed, use launch");
        }
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        BlockingQueue<Image> frames = new ArrayBlockingQueue<>(1);
        java.util.concurrent.atomic.AtomicBoolean captured = new java.util.concurrent.atomic.AtomicBoolean();
        reader.setOnImageAvailableListener(r -> {
            // Take exactly one frame; later frames wait in the queue until the surface is swapped back.
            if (captured.get()) return;
            try {
                Image image = r.acquireLatestImage();
                if (image == null) return;
                captured.set(true);
                if (!frames.offer(image)) image.close();
            } catch (RuntimeException error) {
                Coordinator.log("frame acquire failed: " + error);
            }
        }, handler);
        Image image;
        try {
            display.setSurface(reader.getSurface());
            image = frames.poll(3, TimeUnit.SECONDS);
        } finally {
            display.setSurface(idle.getSurface());
        }
        try {
            if (image == null) throw new Failure("no_frame", "display produced no frame within 3s");
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int stride = plane.getRowStride() / plane.getPixelStride();
            Bitmap padded = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, width, height);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            return png.toByteArray();
        } finally {
            if (image != null) image.close();
            reader.close();
        }
    }

    void tap(float x, float y) throws Exception {
        long down = SystemClock.uptimeMillis();
        touch(down, down, MotionEvent.ACTION_DOWN, x, y);
        Thread.sleep(50);
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
    }

    void swipe(float x1, float y1, float x2, float y2, long duration) throws Exception {
        long down = SystemClock.uptimeMillis();
        touch(down, down, MotionEvent.ACTION_DOWN, x1, y1);
        int steps = (int) Math.max(1, duration / 16);
        for (int i = 1; i <= steps; i++) {
            Thread.sleep(duration / steps);
            float f = (float) i / steps;
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f);
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2);
    }

    private void touch(long downTime, long eventTime, int action, float x, float y) throws Exception {
        if (x < 0 || y < 0 || x >= width || y >= height) throw new Failure("out_of_bounds", x + "," + y);
        MotionEvent.PointerProperties[] properties = { new MotionEvent.PointerProperties() };
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1, properties, coords, 0, 0, 1f, 1f,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (!Platform.inject(event, displayId())) throw new Failure("input_rejected", "injectInputEvent returned false");
        } finally {
            event.recycle();
        }
    }

    void key(String name) throws Exception {
        String full = name.startsWith("KEYCODE_") ? name : "KEYCODE_" + name;
        int code = KeyEvent.keyCodeFromString(full);
        if (code == KeyEvent.KEYCODE_UNKNOWN) throw new Failure("bad_request", "unknown key " + name);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
            KeyEvent event = new KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            if (!Platform.inject(event, displayId())) throw new Failure("input_rejected", "key " + name);
        }
    }

    /** Replaces the focused editable field's text through a short-lived UiAutomation connection. */
    String text(String value) throws Exception {
        synchronized (TEXT_LOCK) {
            UiAutomation automation = connectAutomation();
            try {
                AccessibilityNodeInfo target = null;
                for (int attempt = 0; attempt < 20 && target == null; attempt++) {
                    SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
                    List<AccessibilityWindowInfo> windows = all.get(displayId());
                    if (windows != null) {
                        for (AccessibilityWindowInfo window : windows) {
                            AccessibilityNodeInfo root = window.getRoot();
                            AccessibilityNodeInfo focus = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                            if (focus != null && focus.isEditable()) {
                                target = focus;
                                break;
                            }
                        }
                    }
                    if (target == null) Thread.sleep(100);
                }
                if (target == null) throw new Failure("no_focused_editable", "tap an input field first");
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    throw new Failure("set_text_failed", "the field rejected ACTION_SET_TEXT");
                }
                target.refresh();
                CharSequence now = target.getText();
                return now == null ? null : now.toString();
            } finally {
                Method disconnect = UiAutomation.class.getDeclaredMethod("disconnect");
                disconnect.setAccessible(true);
                disconnect.invoke(automation);
            }
        }
    }

    private static UiAutomation connectAutomation() throws Exception {
        Class<?> connectionType = Class.forName("android.app.UiAutomationConnection");
        Constructor<?> connectionConstructor = connectionType.getDeclaredConstructor();
        connectionConstructor.setAccessible(true);
        Object connection = connectionConstructor.newInstance();
        Class<?> iface = Class.forName("android.app.IUiAutomationConnection");
        UiAutomation automation = null;
        for (Constructor<?> constructor : UiAutomation.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != 2 || types[1] != iface) continue;
            constructor.setAccessible(true);
            if (types[0] == android.content.Context.class) {
                automation = (UiAutomation) constructor.newInstance(Platform.context(), connection);
                break;
            }
            if (types[0] == Looper.class) {
                automation = (UiAutomation) constructor.newInstance(Looper.getMainLooper(), connection);
            }
        }
        if (automation == null) throw new Failure("text_unsupported", "no usable UiAutomation constructor");
        Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        try {
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new Failure("uiautomation_busy", String.valueOf(error.getCause()));
        }
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        return automation;
    }

    JSONObject summary() throws Exception {
        JSONObject json = new JSONObject();
        json.put("sessionId", id);
        json.put("package", packageName());
        if (label() != null) json.put("appLabel", label());
        json.put("state", state);
        if (live()) json.put("displayId", displayId());
        if (endReason != null) json.put("endReason", endReason);
        return json;
    }

    /** An expected, reportable error with a stable code. */
    static final class Failure extends Exception {
        final String code;

        Failure(String code, String detail) {
            super(detail);
            this.code = code;
        }
    }
}
