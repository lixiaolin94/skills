package dev.androidbackgroundtest;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** One background test session: a private virtual display, the tasks launched on it, and its interruption state. */
final class Session {
    static final int MAIN_DISPLAY = 0;
    private static final int FLAG_ACTIVITY_NEW_DOCUMENT_MULTIPLE_TASK = 0x18080000;

    final String id;
    final String token;
    final ComponentName component;
    final int user;
    /** Screenshot and input coordinate space. */
    final int width;
    final int height;
    /** The display itself, same as the main display. */
    final int displayWidth;
    final int displayHeight;
    final int displayDensity;
    /** False for --no-accessibility sessions: no UiAutomation connection is ever made. */
    final boolean accessibilityAllowed;
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
    private int observationSeq;
    private String observation;
    private boolean observationFresh;
    /** Ref centers of the current observation, in screenshot coordinates. */
    private final Map<String, float[]> refs = new HashMap<>();
    /** Visible nodes of the latest tree by identity, for change lists. */
    private Map<String, JSONObject> lastTree;

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

    Session(String id, String token, ComponentName component, int user, int width, int height, int[] displayMetrics,
            boolean accessibilityAllowed, Handler handler) {
        this.id = id;
        this.token = token;
        this.component = component;
        this.user = user;
        this.width = width;
        this.height = height;
        this.displayWidth = displayMetrics[0];
        this.displayHeight = displayMetrics[1];
        this.displayDensity = displayMetrics[2];
        this.accessibilityAllowed = accessibilityAllowed;
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

    void createDisplay() throws Exception {
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
            display = displays.createVirtualDisplay("android-background-test-" + id, displayWidth, displayHeight, displayDensity,
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
        inputDone();
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
        inputDone();
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

    /** Screenshot request options; coordinates are in screenshot space. */
    static final class ShotOptions {
        boolean annotate;
        boolean jpeg;
        int quality = 85;
        int[] clip;
    }

    /** A captured frame plus what the caller needs to interpret it. */
    static final class Shot {
        byte[] data;
        boolean blank;
        boolean dark;
        String observation;
        JSONArray nodes;
    }

    Shot screenshot(ShotOptions options) throws Exception {
        trackTasks(Platform.allTasks());
        if (ownTasks.isEmpty()) {
            throw new Failure("no_task", "no app task on the session display; the app exited or was closed, use launch");
        }
        Shot shot = new Shot();
        List<Accessibility.Node> marks = null;
        if (options.annotate) {
            requireAccessibility();
            try (Accessibility tree = Accessibility.open(displayId())) {
                List<Accessibility.Node> nodes = tree.snapshot(false);
                JSONObject result = treeResult(nodes, tree.windowCount);
                shot.nodes = result.getJSONArray("nodes");
                marks = nodes;
            }
        } else if (!observationFresh) {
            observe();
        }
        shot.observation = observation;
        Bitmap bitmap = capture();
        int[] stats = luminance(bitmap);
        shot.blank = stats[1] < 2;
        shot.dark = shot.blank && stats[0] < 16;
        if (marks != null) bitmap = annotate(bitmap, marks);
        if (options.clip != null) {
            int[] c = options.clip;
            if (c[0] < 0 || c[1] < 0 || c[2] <= 0 || c[3] <= 0 || c[0] + c[2] > width || c[1] + c[3] > height) {
                throw new Failure("out_of_bounds", "clip must lie inside " + width + "x" + height);
            }
            bitmap = Bitmap.createBitmap(bitmap, c[0], c[1], c[2], c[3]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (options.jpeg) bitmap.compress(Bitmap.CompressFormat.JPEG, Math.max(1, Math.min(100, options.quality)), out);
        else bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        shot.data = out.toByteArray();
        return shot;
    }

    /** One frame of the display, scaled to screenshot space. */
    private Bitmap capture() throws Exception {
        ImageReader reader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2);
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
            Bitmap padded = Bitmap.createBitmap(stride, displayHeight, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap full = Bitmap.createBitmap(padded, 0, 0, displayWidth, displayHeight);
            return Bitmap.createScaledBitmap(full, width, height, true);
        } finally {
            if (image != null) image.close();
            reader.close();
        }
    }

    /** Mean and standard deviation of luminance over a 32x32 sample grid. */
    private static int[] luminance(Bitmap bitmap) {
        double sum = 0;
        double squares = 0;
        int count = 0;
        for (int gy = 0; gy < 32; gy++) {
            for (int gx = 0; gx < 32; gx++) {
                int pixel = bitmap.getPixel(gx * (bitmap.getWidth() - 1) / 31, gy * (bitmap.getHeight() - 1) / 31);
                double y = 0.299 * ((pixel >> 16) & 0xff) + 0.587 * ((pixel >> 8) & 0xff) + 0.114 * (pixel & 0xff);
                sum += y;
                squares += y * y;
                count++;
            }
        }
        double mean = sum / count;
        return new int[] {(int) Math.round(mean), (int) Math.round(Math.sqrt(Math.max(0, squares / count - mean * mean)))};
    }

    /** 3x5 pixel glyphs for 0-9, rows top to bottom, 3 bits per row. */
    private static final int[] DIGITS = {
        0b111101101101111, 0b010110010010111, 0b111001111100111, 0b111001111001111, 0b101101111001001,
        0b111100111001111, 0b111100111101111, 0b111001001001001, 0b111101111101111, 0b111101111001111,
    };

    /** Draws a box and its ref number on every visible interactive element. */
    private Bitmap annotate(Bitmap source, List<Accessibility.Node> nodes) {
        Bitmap bitmap = source.copy(Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        float scale = width / 720f;
        android.graphics.Paint box = new android.graphics.Paint();
        box.setStyle(android.graphics.Paint.Style.STROKE);
        box.setStrokeWidth(2 * scale);
        box.setColor(0xFFFF2D7A);
        android.graphics.Paint tag = new android.graphics.Paint();
        tag.setColor(0xE6FF2D7A);
        // No text drawing: app_process has no default Typeface and Paint text aborts the process.
        // The tag shows the ref number (12 for e12) in a built-in 3x5 pixel font.
        android.graphics.Paint ink = new android.graphics.Paint();
        ink.setColor(0xFFFFFFFF);
        float dot = Math.max(2, 3 * scale);
        for (Accessibility.Node node : nodes) {
            if (!node.visible || !node.interactive()) continue;
            float l = sx(node.bounds.left), t = sy(node.bounds.top), r = sx(node.bounds.right), b = sy(node.bounds.bottom);
            canvas.drawRect(l, t, r, b, box);
            String number = node.ref.substring(1);
            float w = number.length() * 4 * dot + dot, h = 7 * dot;
            canvas.drawRect(l, t, l + w, t + h, tag);
            for (int i = 0; i < number.length(); i++) {
                int glyph = DIGITS[number.charAt(i) - '0'];
                for (int row = 0; row < 5; row++) {
                    for (int col = 0; col < 3; col++) {
                        if ((glyph >> (14 - row * 3 - col) & 1) == 0) continue;
                        float x = l + dot + (i * 4 + col) * dot, y = t + dot + row * dot;
                        canvas.drawRect(x, y, x + dot, y + dot, ink);
                    }
                }
            }
        }
        return bitmap;
    }
    void tap(float x, float y) throws Exception {
        long down = SystemClock.uptimeMillis();
        touch(down, down, MotionEvent.ACTION_DOWN, x, y);
        Thread.sleep(50);
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
        inputDone();
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
        inputDone();
    }

    private void touch(long downTime, long eventTime, int action, float x, float y) throws Exception {
        if (x < 0 || y < 0 || x >= width || y >= height) throw new Failure("out_of_bounds", x + "," + y);
        MotionEvent.PointerProperties[] properties = { new MotionEvent.PointerProperties() };
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = x * displayWidth / width;
        coords[0].y = y * displayHeight / height;
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
        inputDone();
    }

    // --- accessibility-backed operations -------------------------------------------------------------

    private void requireAccessibility() throws Failure {
        if (!accessibilityAllowed) {
            throw new Failure("accessibility_disabled", "this session was opened with --no-accessibility");
        }
    }

    /** Starts a new observation; refs from older observations stop working. */
    private String observe() {
        observation = "o" + (++observationSeq);
        observationFresh = true;
        refs.clear();
        return observation;
    }

    /** Any input may change the screen, so the current observation no longer describes it. */
    private void inputDone() {
        observationFresh = false;
        refs.clear();
    }

    void checkObservation(String given) throws Failure {
        if (given == null || given.isEmpty()) return;
        if (!given.equals(observation) || !observationFresh) {
            throw new Failure("stale_observation", "observation " + given + " is outdated (latest "
                    + observation + (observationFresh ? "" : ", already used by an input") + "); look again first");
        }
    }

    float[] refCenter(String ref) throws Failure {
        if (!observationFresh) {
            throw new Failure("stale_observation", "refs belong to observation " + observation
                    + ", which an input already invalidated; run layout again");
        }
        float[] center = refs.get(ref);
        if (center == null) throw new Failure("unknown_ref", ref + " is not in observation " + observation);
        return center;
    }

    private float sx(int x) {
        return x * (float) width / displayWidth;
    }

    private float sy(int y) {
        return y * (float) height / displayHeight;
    }

    private JSONObject nodeJson(Accessibility.Node node) throws Exception {
        JSONObject json = new JSONObject();
        json.put("ref", node.ref);
        json.put("class", node.className);
        if (node.text != null) json.put("text", node.text);
        if (node.desc != null) json.put("desc", node.desc);
        if (node.id != null) json.put("id", node.id);
        if (node.hint != null) json.put("hint", node.hint);
        if (node.label != null) json.put("label", node.label);
        int l = Math.round(sx(node.bounds.left)), t = Math.round(sy(node.bounds.top));
        int r = Math.round(sx(node.bounds.right)), b = Math.round(sy(node.bounds.bottom));
        json.put("bounds", new JSONArray().put(l).put(t).put(r).put(b));
        json.put("center", new JSONArray().put((l + r) / 2).put((t + b) / 2));
        JSONArray actions = new JSONArray();
        if (node.clickable) actions.put("click");
        if (node.longClickable) actions.put("longClick");
        if (node.scrollable) actions.put("scroll");
        if (node.editable) actions.put("edit");
        if (node.checkable) actions.put("check");
        if (actions.length() > 0) json.put("actions", actions);
        JSONArray state = new JSONArray();
        if (node.info.isChecked()) state.put("checked");
        if (node.info.isFocused()) state.put("focused");
        if (node.info.isSelected()) state.put("selected");
        if (!node.info.isEnabled()) state.put("disabled");
        if (node.info.isPassword()) state.put("password");
        if (state.length() > 0) json.put("state", state);
        if (!node.visible) json.put("offscreen", true);
        return json;
    }

    private static String describe(JSONObject node) {
        for (String key : new String[] {"text", "desc", "label", "hint"}) {
            if (node.has(key)) return node.optString(key);
        }
        return node.has("id") ? "#" + node.optString("id") : node.optString("class");
    }

    /** Records a fresh observation for these nodes and returns the layout result. */
    private JSONObject treeResult(List<Accessibility.Node> nodes, int windows) throws Exception {
        if (windows == 0) {
            throw new Failure("layout_unavailable", "no accessibility windows on the session display");
        }
        observe();
        JSONArray array = new JSONArray();
        Map<String, JSONObject> tree = new LinkedHashMap<>();
        for (Accessibility.Node node : nodes) {
            JSONObject json = nodeJson(node);
            array.put(json);
            if (node.visible) {
                JSONArray center = json.getJSONArray("center");
                refs.put(node.ref, new float[] {(float) center.getDouble(0), (float) center.getDouble(1)});
                tree.put(node.identity(), json);
            }
        }
        lastTree = tree;
        JSONObject result = new JSONObject();
        result.put("observation", observation);
        result.put("nodes", array);
        result.put("nodeCount", array.length());
        if (nodes.size() >= Accessibility.MAX_NODES) result.put("truncated", true);
        return result;
    }

    JSONObject layout(boolean full) throws Exception {
        requireAccessibility();
        try (Accessibility tree = Accessibility.open(displayId())) {
            return treeResult(tree.snapshot(full), tree.windowCount);
        }
    }

    /** Exactly one visible match, or a failure that lists what is on screen. */
    private Accessibility.Node single(List<Accessibility.Node> hits, List<Accessibility.Node> all,
            Map<String, String> selector) throws Exception {
        if (hits.size() == 1) return hits.get(0);
        JSONObject extra = new JSONObject();
        extra.put("observation", observation);
        if (hits.isEmpty()) {
            JSONArray seen = new JSONArray();
            for (Accessibility.Node node : all) {
                if (node.visible && node.hasContent() && seen.length() < 15) seen.put(node.describe());
            }
            extra.put("visibleTexts", seen);
            throw new Failure("element_not_found", "nothing on screen matches " + selector, extra);
        }
        JSONArray candidates = new JSONArray();
        for (Accessibility.Node node : hits) if (candidates.length() < 15) candidates.put(nodeJson(node));
        extra.put("candidates", candidates);
        throw new Failure("element_ambiguous", hits.size() + " elements match " + selector + "; use --ref", extra);
    }

    JSONObject tapTarget(Map<String, String> selector) throws Exception {
        requireAccessibility();
        JSONObject target;
        float[] center;
        try (Accessibility tree = Accessibility.open(displayId())) {
            List<Accessibility.Node> nodes = tree.snapshot(false);
            treeResult(nodes, tree.windowCount);
            Accessibility.Node hit = single(Accessibility.find(nodes, selector, false), nodes, selector);
            target = nodeJson(hit);
            center = refs.get(hit.ref);
        }
        tap(center[0], center[1]);
        JSONObject result = new JSONObject();
        result.put("target", target);
        return result;
    }

    /** Replaces the focused editable field's text. */
    String text(String value) throws Exception {
        requireAccessibility();
        try (Accessibility tree = Accessibility.open(displayId())) {
            AccessibilityNodeInfo target = tree.focusedEditable();
            if (target == null) throw new Failure("no_focused_editable", "tap an input field first");
            String now = Accessibility.setText(target, value);
            inputDone();
            return now;
        }
    }

    /** Focuses the matching editable field and replaces its text. */
    JSONObject textTarget(Map<String, String> selector, String value) throws Exception {
        requireAccessibility();
        try (Accessibility tree = Accessibility.open(displayId())) {
            List<Accessibility.Node> nodes = tree.snapshot(false);
            treeResult(nodes, tree.windowCount);
            Accessibility.Node hit = single(Accessibility.find(nodes, selector, true), nodes, selector);
            hit.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            JSONObject result = new JSONObject();
            result.put("target", nodeJson(hit));
            String now = Accessibility.setText(hit.info, value);
            inputDone();
            if (now != null) result.put("text", now);
            return result;
        }
    }

    /** Scrolls slowly in the middle of the display; with a selector, until it shows up. */
    JSONObject scroll(String direction, Map<String, String> until, int maxSwipes) throws Exception {
        float cx = width / 2f, cy = height / 2f, dx = 0, dy = 0;
        switch (direction) {
            case "down": dy = height * 0.25f; break;
            case "up": dy = -height * 0.25f; break;
            case "right": dx = width * 0.25f; break;
            case "left": dx = -width * 0.25f; break;
            default: throw new Failure("bad_request", "direction must be up, down, left or right");
        }
        JSONObject result = new JSONObject();
        if (until == null) {
            // Scrolling down reveals content below, so the finger moves up.
            swipe(cx + dx, cy + dy, cx - dx, cy - dy, 500);
            result.put("swipes", 1);
            return result;
        }
        requireAccessibility();
        try (Accessibility tree = Accessibility.open(displayId())) {
            for (int swipes = 0; ; swipes++) {
                List<Accessibility.Node> nodes = tree.snapshot(false);
                List<Accessibility.Node> hits = Accessibility.find(nodes, until, false);
                if (!hits.isEmpty() || swipes >= maxSwipes) {
                    treeResult(nodes, tree.windowCount);
                    result.put("swipes", swipes);
                    if (hits.isEmpty()) throw new Failure("element_not_found",
                            "not found after " + swipes + " swipes: " + until, result);
                    result.put("observation", observation);
                    result.put("target", nodeJson(single(hits, nodes, until)));
                    return result;
                }
                swipe(cx + dx, cy + dy, cx - dx, cy - dy, 500);
                Thread.sleep(400);
            }
        }
    }

    /** Waits until the accessibility tree stops changing, then reports what changed since the last observation. */
    JSONObject settle() throws Exception {
        requireAccessibility();
        Map<String, JSONObject> before = lastTree;
        JSONObject result = new JSONObject();
        try (Accessibility tree = Accessibility.open(displayId())) {
            long deadline = SystemClock.elapsedRealtime() + 3000;
            List<Accessibility.Node> nodes;
            String signature = null;
            boolean settled = false;
            while (true) {
                Thread.sleep(300);
                nodes = tree.snapshot(false);
                StringBuilder next = new StringBuilder();
                for (Accessibility.Node node : nodes) next.append(node.key()).append('\n');
                if (next.toString().equals(signature)) {
                    settled = true;
                    break;
                }
                signature = next.toString();
                if (SystemClock.elapsedRealtime() > deadline) break;
            }
            JSONObject layout = treeResult(nodes, tree.windowCount);
            result.put("settled", settled);
            result.put("observation", observation);
            if (before == null) {
                result.put("nodes", layout.getJSONArray("nodes"));
                return result;
            }
        }
        JSONArray added = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : lastTree.entrySet()) {
            if (!before.containsKey(entry.getKey()) && added.length() < 40) added.put(entry.getValue());
        }
        JSONArray removed = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : before.entrySet()) {
            if (!lastTree.containsKey(entry.getKey()) && removed.length() < 40) removed.put(describe(entry.getValue()));
        }
        JSONObject changes = new JSONObject();
        changes.put("added", added);
        changes.put("removed", removed);
        result.put("changes", changes);
        return result;
    }

    JSONObject summary() throws Exception {
        JSONObject json = new JSONObject();
        json.put("sessionId", id);
        json.put("package", packageName());
        if (label() != null) json.put("appLabel", label());
        json.put("state", state);
        if (live()) json.put("displayId", displayId());
        if (endReason != null) json.put("endReason", endReason);
        if (!accessibilityAllowed) json.put("accessibility", false);
        return json;
    }

    /** An expected, reportable error with a stable code. */
    static final class Failure extends Exception {
        final String code;
        /** Extra response fields, such as candidates or the prompt for the user. */
        final JSONObject extra;

        Failure(String code, String detail) {
            this(code, detail, null);
        }

        Failure(String code, String detail, JSONObject extra) {
            super(detail);
            this.code = code;
            this.extra = extra;
        }
    }
}
