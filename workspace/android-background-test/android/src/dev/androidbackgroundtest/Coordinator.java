package dev.androidbackgroundtest;

import android.content.ComponentName;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One process per device. Owns every session, the package locks and the task/lock watcher; exits when idle. */
final class Coordinator {
    static final String SOCKET = "android-background-test";
    private static final String STATE_FILE = "/data/local/tmp/android-background-test/owned-tasks";
    private static final String TAG = "android-background-test";
    private static final int SHELL_UID = 2000;
    private static final long ASK_TIMEOUT_MS = 60_000;
    private static final long LEASE_MS = 600_000;
    private static final long IDLE_EXIT_MS = 30_000;
    private static final long WATCH_INTERVAL_MS = 500;
    private static final int MAX_SESSIONS = 32;
    private static final int MAX_REQUEST_BYTES = 1 << 20;
    private static final int DEFAULT_WIDTH = 720;

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Handler handler;
    private long idleSince = SystemClock.elapsedRealtime();
    private String savedTasks = "";

    private Coordinator(Handler handler) {
        this.handler = handler;
    }

    static void log(String message) {
        Log.i(TAG, message);
    }

    static void run() throws Exception {
        Looper.prepareMainLooper();
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET);
        } catch (IOException taken) {
            System.err.println("coordinator already running");
            return;
        }
        Platform.context();
        recoverLeftovers();
        HandlerThread images = new HandlerThread("abt-images");
        images.start();
        Coordinator coordinator = new Coordinator(new Handler(images.getLooper()));
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log("fatal in " + thread.getName() + ": " + Log.getStackTraceString(error));
            coordinator.endAll("coordinator_crashed");
            System.exit(1);
        });
        Thread accept = new Thread(() -> coordinator.acceptLoop(server), "abt-accept");
        accept.setDaemon(true);
        accept.start();
        Thread watcher = new Thread(coordinator::watchLoop, "abt-watch");
        watcher.setDaemon(true);
        watcher.start();
        log("coordinator started pid=" + android.os.Process.myPid());
        Looper.loop();
    }

    /** Removes recents leftovers of tasks a previous coordinator created but could not clean up. */
    private static void recoverLeftovers() {
        java.io.File file = new java.io.File(STATE_FILE);
        if (!file.exists()) return;
        try {
            java.util.Set<Integer> live = new java.util.HashSet<>();
            for (Platform.Task task : Platform.allTasks()) live.add(task.id);
            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (line.trim().isEmpty()) continue;
                int task = Integer.parseInt(line.trim());
                // A task that still exists may now belong to the user; only dead leftovers are removed.
                if (!live.contains(task)) Platform.removeTask(task);
            }
        } catch (Exception error) {
            log("recover leftovers failed: " + error);
        }
        file.delete();
    }

    private void saveOwnedTasks() {
        StringBuilder content = new StringBuilder();
        synchronized (sessions) {
            for (Session session : sessions.values()) {
                if (!session.live()) continue;
                for (int task : session.recoverableTasks()) content.append(task).append('\n');
            }
        }
        if (content.toString().equals(savedTasks)) return;
        savedTasks = content.toString();
        try {
            java.io.File file = new java.io.File(STATE_FILE);
            java.io.File staged = new java.io.File(STATE_FILE + ".tmp");
            java.nio.file.Files.write(staged.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
            staged.setReadable(false, false);
            staged.setWritable(false, false);
            staged.setReadable(true, true);
            staged.setWritable(true, true);
            if (!staged.renameTo(file)) log("could not save owned tasks");
        } catch (IOException error) {
            log("save owned tasks failed: " + error);
        }
    }

    private void endAll(String reason) {
        List<Session> all = new ArrayList<>();
        synchronized (sessions) {
            all.addAll(sessions.values());
        }
        for (Session session : all) {
            try {
                session.end(reason);
            } catch (RuntimeException ignored) {
                // Best effort while crashing.
            }
        }
    }

    private void acceptLoop(LocalServerSocket server) {
        while (true) {
            try {
                LocalSocket socket = server.accept();
                Thread worker = new Thread(() -> serve(socket), "abt-client");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException error) {
                log("accept failed: " + error);
            }
        }
    }

    private void serve(LocalSocket socket) {
        try (LocalSocket closing = socket) {
            OutputStream out = socket.getOutputStream();
            if (socket.getPeerCredentials().getUid() != SHELL_UID) {
                write(out, error("forbidden", "only the shell user may connect"), null);
                return;
            }
            String line = readLine(new BufferedInputStream(socket.getInputStream()));
            if (line == null) return;
            JSONObject response;
            byte[] payload = null;
            try {
                JSONObject request = new JSONObject(line);
                Object[] result = handle(request);
                response = (JSONObject) result[0];
                payload = (byte[]) result[1];
            } catch (Session.Failure failure) {
                response = error(failure.code, failure.getMessage());
                if (failure.extra != null) {
                    for (java.util.Iterator<String> keys = failure.extra.keys(); keys.hasNext(); ) {
                        String key = keys.next();
                        if (!response.has(key)) response.put(key, failure.extra.get(key));
                    }
                }
            } catch (Exception unexpected) {
                log("request failed: " + Log.getStackTraceString(unexpected));
                response = error("internal", String.valueOf(unexpected));
            }
            write(out, response, payload);
        } catch (Exception error) {
            log("connection failed: " + error);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (buffer.size() >= MAX_REQUEST_BYTES) throw new IOException("request too large");
            buffer.write(b);
        }
        if (b == -1 && buffer.size() == 0) return null;
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(OutputStream out, JSONObject response, byte[] payload) throws Exception {
        if (payload != null) response.put("payloadBytes", payload.length);
        out.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        if (payload != null) out.write(payload);
        out.flush();
    }

    private static JSONObject error(String code, String detail) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", code);
            if (detail != null) json.put("detail", detail);
        } catch (Exception ignored) {
            // JSONObject.put only fails on non-finite numbers.
        }
        return json;
    }

    private Object[] handle(JSONObject request) throws Exception {
        String op = request.getString("op");
        switch (op) {
            case "hello": return reply(hello(), null);
            case "list": return reply(list(), null);
            case "shutdown": return reply(shutdown(), null);
            case "open": return reply(open(request), null);
            default: break;
        }
        Session session = authorize(request);
        synchronized (session) {
            session.lastUsed = SystemClock.elapsedRealtime();
            if (!session.live()) {
                if (op.equals("close") || op.equals("status")) return reply(sessionState(session), null);
                throw new Session.Failure("session_ended", session.endReason);
            }
            switch (op) {
                case "status": return reply(sessionState(session), null);
                case "close":
                    session.end(closeReason(request.optString("reason", "closed")));
                    return reply(sessionState(session), null);
                case "screenshot": return screenshot(session, request);
                case "layout": {
                    JSONObject json = merge(sessionState(session), session.layout(request.optBoolean("full")));
                    return reply(json, null);
                }
                case "resolve": return reply(resolve(session, request), null);
                default: break;
            }
            if (session.interruption != null) {
                JSONObject json = sessionState(session);
                json.put("ok", false);
                json.put("error", "interrupted");
                return reply(json, null);
            }
            session.checkObservation(request.optString("observation", null));
            JSONObject result = new JSONObject();
            Map<String, String> target = selector(request.optJSONObject("target"));
            switch (op) {
                case "tap":
                    if (target != null) {
                        result = session.tapTarget(target);
                    } else if (request.has("ref")) {
                        float[] center = session.refCenter(request.getString("ref"));
                        session.tap(center[0], center[1]);
                    } else {
                        session.tap((float) request.getDouble("x"), (float) request.getDouble("y"));
                    }
                    break;
                case "swipe":
                    session.swipe((float) request.getDouble("x1"), (float) request.getDouble("y1"),
                            (float) request.getDouble("x2"), (float) request.getDouble("y2"),
                            request.optLong("durationMs", 300));
                    break;
                case "scroll":
                    result = session.scroll(request.getString("direction"), selector(request.optJSONObject("until")),
                            Math.max(1, Math.min(30, request.optInt("max", 8))));
                    break;
                case "key":
                    session.key(request.getString("key"));
                    break;
                case "text": {
                    String value = request.getString("value");
                    if (target != null) {
                        result = session.textTarget(target, value);
                    } else {
                        if (request.has("ref")) {
                            float[] center = session.refCenter(request.getString("ref"));
                            session.tap(center[0], center[1]);
                            Thread.sleep(300);
                        }
                        String now = session.text(value);
                        if (now != null) result.put("text", now);
                    }
                    break;
                }
                case "launch":
                    launchOrConflict(session);
                    break;
                default:
                    throw new Session.Failure("bad_request", "unknown op " + op);
            }
            if (request.optBoolean("settle") && session.interruption == null) {
                result.put("settle", session.settle());
            }
            return reply(merge(sessionState(session), result), null);
        }
    }

    private static final java.util.Set<String> CLOSE_REASONS = new java.util.HashSet<>(
            java.util.Arrays.asList("closed", "done", "app_blocked", "sensitive_step", "user_stopped"));

    private static String closeReason(String reason) throws Session.Failure {
        if (!CLOSE_REASONS.contains(reason)) throw new Session.Failure("bad_request", "unknown close reason " + reason);
        return reason;
    }

    private static Map<String, String> selector(JSONObject json) throws Exception {
        if (json == null || json.length() == 0) return null;
        Map<String, String> selector = new LinkedHashMap<>();
        for (java.util.Iterator<String> keys = json.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!key.equals("text") && !key.equals("id") && !key.equals("desc") && !key.equals("class")) {
                throw new Session.Failure("bad_request", "selector keys are text, id, desc, class");
            }
            selector.put(key, json.getString(key));
        }
        return selector;
    }

    private static JSONObject merge(JSONObject base, JSONObject extra) throws Exception {
        for (java.util.Iterator<String> keys = extra.keys(); keys.hasNext(); ) {
            String key = keys.next();
            base.put(key, extra.get(key));
        }
        return base;
    }

    private Object[] screenshot(Session session, JSONObject request) throws Exception {
        Session.ShotOptions options = new Session.ShotOptions();
        options.annotate = request.optBoolean("annotate");
        options.jpeg = "jpeg".equals(request.optString("format", "png"));
        options.quality = request.optInt("quality", 85);
        JSONArray clip = request.optJSONArray("clip");
        if (clip != null) {
            if (clip.length() != 4) throw new Session.Failure("bad_request", "clip is x,y,w,h");
            options.clip = new int[] {clip.getInt(0), clip.getInt(1), clip.getInt(2), clip.getInt(3)};
        }
        Session.Shot shot = session.screenshot(options);
        JSONObject json = sessionState(session);
        json.put("width", session.width);
        json.put("height", session.height);
        json.put("format", options.jpeg ? "jpeg" : "png");
        json.put("observation", shot.observation);
        if (clip != null) json.put("clip", clip);
        if (shot.blank) json.put("blankFrame", shot.dark ? "dark" : "uniform");
        if (shot.nodes != null) json.put("nodes", shot.nodes);
        return reply(json, shot.data);
    }

    private static Object[] reply(JSONObject json, byte[] payload) throws Exception {
        if (!json.has("ok")) json.put("ok", true);
        return new Object[] { json, payload };
    }

    private JSONObject hello() throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", "android-background-test");
        json.put("version", BuildInfo.VERSION);
        json.put("protocol", BuildInfo.PROTOCOL);
        json.put("sourceSha256", BuildInfo.SOURCE_SHA256);
        json.put("sdk", Build.VERSION.SDK_INT);
        json.put("liveSessions", liveCount());
        return json;
    }

    private JSONObject list() throws Exception {
        JSONArray array = new JSONArray();
        synchronized (sessions) {
            for (Session session : sessions.values()) {
                synchronized (session) {
                    array.put(session.summary());
                }
            }
        }
        JSONObject json = new JSONObject();
        json.put("sessions", array);
        return json;
    }

    private JSONObject shutdown() throws Exception {
        if (liveCount() > 0) throw new Session.Failure("busy", "sessions are still running");
        handler.postDelayed(() -> System.exit(0), 200);
        return new JSONObject();
    }

    private int liveCount() {
        int live = 0;
        synchronized (sessions) {
            for (Session session : sessions.values()) if (session.live()) live++;
        }
        return live;
    }

    private JSONObject open(JSONObject request) throws Exception {
        ComponentName component = ComponentName.unflattenFromString(request.getString("component"));
        if (component == null) throw new Session.Failure("bad_request", "component must be package/activity");
        int user = Platform.currentUser();
        if (request.has("user") && request.getInt("user") != user) {
            throw new Session.Failure("user_unsupported", "only the current foreground user " + user + " is supported");
        }
        if (Platform.deviceLocked()) throw new Session.Failure("device_locked", "unlock the phone and keep the screen on");
        checkSensitive(component.getPackageName(), request);
        // The session display copies the main display's size and density: apps that read density from their
        // Application resources get the main display's value, so any other density renders them scaled up.
        // Screenshots and coordinates use a smaller space with the same aspect ratio.
        int[] main = Platform.mainDisplayMetrics();
        int width = request.optInt("width", DEFAULT_WIDTH);
        if (width < 100 || width > main[0]) throw new Session.Failure("bad_request", "width must be 100.." + main[0]);
        int height = Math.round(width * (float) main[1] / main[0]);
        Session session;
        synchronized (sessions) {
            int live = 0;
            for (Session other : sessions.values()) {
                if (!other.live()) continue;
                live++;
                if (other.packageName().equals(component.getPackageName())) {
                    throw new Session.Failure("package_busy", "another session is testing " + component.getPackageName());
                }
            }
            if (live >= MAX_SESSIONS) throw new Session.Failure("display_limit", "at most " + MAX_SESSIONS + " sessions");
            session = new Session(randomHex(8), randomHex(16), component, user, width, height, main,
                    !request.optBoolean("noAccessibility"), handler);
            sessions.put(session.id, session);
        }
        synchronized (session) {
            try {
                session.createDisplay();
                launchOrConflict(session);
            } catch (Exception failure) {
                session.end("open_failed");
                synchronized (sessions) {
                    sessions.remove(session.id);
                }
                throw failure;
            }
            JSONObject json = sessionState(session);
            json.put("token", session.token);
            return json;
        }
    }

    /** Label words that mark finance apps; the client adds its own package list as {@code sensitive}. */
    private static final String[][] SENSITIVE_LABELS = {
        {"银行", "银行"}, {"证券", "证券"}, {"支付", "支付"}, {"钱包", "支付"}, {"保险", "保险"},
        {"基金", "理财"}, {"理财", "理财"}, {"信用卡", "银行"},
    };

    /** Refuses apps known to police automation until the user has accepted the account risk. */
    private static void checkSensitive(String packageName, JSONObject request) throws Exception {
        if (request.optBoolean("acceptRisk")) return;
        String category = request.optString("sensitive", "");
        String label = Platform.appLabel(packageName);
        if (category.isEmpty() && label != null) {
            for (String[] word : SENSITIVE_LABELS) {
                if (label.contains(word[0])) {
                    category = word[1];
                    break;
                }
            }
        }
        if (category.isEmpty()) return;
        String name = label == null ? packageName : label + "（" + packageName + "）";
        JSONObject extra = new JSONObject();
        extra.put("category", category);
        if (label != null) extra.put("appLabel", label);
        extra.put("askUser", name + "属于" + category + "类应用。这类应用常会识别自动化操作（无障碍、模拟点击、后台虚拟屏），"
                + "轻则拒绝运行或弹出安全提示，重则对你的账号做风控（强制下线、限制功能甚至封号），后果落在你的真实账号上，"
                + "Agent 无法撤销。建议改用测试账号或测试机。仍要继续测试吗？");
        throw new Session.Failure("sensitive_app", packageName + " is a " + category + " app; ask the user first", extra);
    }

    /** Launches unless that would move a task the user already has; then asks first. */
    private void launchOrConflict(Session session) throws Exception {
        boolean reuses = Platform.reusesExistingTask(session.component, session.user);
        if (reuses && userHasTask(session)) {
            session.interrupt("existing_task",
                    session.appName() + session.nameGap() + "已有用户任务，启动会把它移进后台测试屏",
                    "是否允许 Agent 接管" + session.nameGap() + session.appName() + session.nameGap() + "的现有任务？接管后它会从你的手机界面移到后台测试屏，测试结束时会被关闭。",
                    true, -1);
            return;
        }
        session.launch(!reuses);
    }

    private static boolean userHasTask(Session session) throws Exception {
        for (Platform.Task task : Platform.allTasks()) {
            if (session.packageName().equals(task.packageName) && task.displayId != session.displayId()) return true;
        }
        return Platform.hasRecentTask(session.packageName(), session.user, session.displayId());
    }

    private JSONObject resolve(Session session, JSONObject request) throws Exception {
        Session.Interruption interruption = session.interruption;
        if (interruption == null || !interruption.id.equals(request.getString("conflictId"))) {
            throw new Session.Failure("stale_conflict", "no such pending conflict");
        }
        if (!request.getBoolean("grant")) {
            session.end("user_denied");
            return sessionState(session);
        }
        if (!interruption.grantable) throw new Session.Failure("not_supported", "this interruption cannot be granted");
        if (interruption.reason.equals("existing_task")) {
            session.resume();
            session.launch(false);
        } else if (interruption.reason.equals("user_opened_app")) {
            Platform.Task taken = null;
            for (Platform.Task task : Platform.allTasks()) if (task.id == interruption.takenTaskId) taken = task;
            if (taken != null && taken.displayId == Session.MAIN_DISPLAY && taken.visible) {
                session.end("user_active");
                throw new Session.Failure("user_active", "the user is still using the app on the phone");
            }
            if (taken != null) Platform.moveRootTaskToDisplay(taken.rootId, session.displayId());
            session.resume();
            session.trackTasks(Platform.allTasks());
        }
        return sessionState(session);
    }

    private Session authorize(JSONObject request) throws Exception {
        Session session;
        synchronized (sessions) {
            session = sessions.get(request.optString("sessionId"));
        }
        if (session == null) throw new Session.Failure("session_lost", "unknown session");
        byte[] expected = session.token.getBytes(StandardCharsets.UTF_8);
        byte[] given = request.optString("token").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) throw new Session.Failure("forbidden", "bad session token");
        return session;
    }

    /** Session state for a response; reporting an interruption starts its consent timer. */
    private JSONObject sessionState(Session session) throws Exception {
        JSONObject json = session.summary();
        json.put("tasks", session.ownTaskIds());
        Session.Interruption interruption = session.interruption;
        if (interruption != null) {
            long now = SystemClock.elapsedRealtime();
            if (interruption.reportedAt < 0) interruption.reportedAt = now;
            JSONObject info = new JSONObject();
            info.put("conflictId", interruption.id);
            info.put("reason", interruption.reason);
            info.put("detail", interruption.detail);
            info.put("askUser", interruption.askUser);
            info.put("grantable", interruption.grantable);
            info.put("expiresInSeconds", Math.max(0, (ASK_TIMEOUT_MS - (now - interruption.reportedAt)) / 1000));
            json.put("interruption", info);
        }
        return json;
    }

    private void watchLoop() {
        while (true) {
            try {
                Thread.sleep(WATCH_INTERVAL_MS);
                watch();
            } catch (Exception error) {
                log("watch failed: " + Log.getStackTraceString(error));
            }
        }
    }

    private void watch() throws Exception {
        List<Session> live = new ArrayList<>();
        synchronized (sessions) {
            for (Session session : sessions.values()) if (session.live()) live.add(session);
        }
        long now = SystemClock.elapsedRealtime();
        saveOwnedTasks();
        if (live.isEmpty()) {
            if (now - idleSince > IDLE_EXIT_MS) {
                log("idle, exiting");
                System.exit(0);
            }
            return;
        }
        idleSince = now;
        boolean locked = Platform.deviceLocked();
        List<Platform.Task> tasks = Platform.allTasks();
        for (Session session : live) {
            synchronized (session) {
                if (!session.live()) continue;
                Session.Interruption pending = session.interruption;
                if (now - session.lastUsed > LEASE_MS) {
                    session.end("lease_expired");
                    continue;
                }
                if (pending != null && pending.reportedAt >= 0 && now - pending.reportedAt > ASK_TIMEOUT_MS) {
                    session.end(pending.reason.equals("device_locked") ? "device_locked" : "consent_timeout");
                    continue;
                }
                Platform.Task moved = session.trackTasks(tasks);
                if (moved != null && (pending == null || !pending.reason.equals("user_opened_app"))) {
                    session.interrupt("user_opened_app",
                            "用户在手机上打开了" + session.nameGap() + session.appName() + "，系统已把测试中的任务交给用户",
                            "是否允许 Agent 接管" + session.nameGap() + session.appName() + "？只有你已离开该应用时才能接管。",
                            true, moved.id);
                } else if (locked && session.interruption == null) {
                    session.interrupt("device_locked", "手机已锁屏或息屏，后台测试屏停止渲染",
                            "手机已锁屏，请在 60 秒内解锁并保持亮屏，否则会话结束。", false, -1);
                } else if (!locked && pending != null && pending.reason.equals("device_locked")) {
                    session.resume();
                }
            }
        }
    }

    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder hex = new StringBuilder();
        for (byte b : data) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
