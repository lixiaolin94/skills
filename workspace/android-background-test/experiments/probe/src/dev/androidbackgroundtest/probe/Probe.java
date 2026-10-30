package dev.androidbackgroundtest.probe;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Experiment-only probe. Reads one command per line from stdin and prints one result line each. */
public final class Probe {
    private static Context context;
    private static DisplayManager displays;
    private static VirtualDisplay display;
    private static ImageReader idle;
    private static Handler handler;
    private static int width;
    private static int height;
    private static ImageReader full;
    private static Image latest;
    private static int frames;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("client")) {
            client(args[1], args[2]);
            return;
        }
        if (args.length > 0 && args[0].equals("daemon")) {
            serve(args[1]);
            Thread.sleep(Long.parseLong(args[2]) * 1000);
            out("daemon idle exit");
            System.exit(0);
        }
        Looper.prepareMainLooper();
        context = new ShellContext(systemContext());
        HandlerThread thread = new HandlerThread("probe-image");
        thread.start();
        handler = new Handler(thread.getLooper());
        Thread commands = new Thread(Probe::commandLoop, "probe-commands");
        commands.start();
        Looper.loop();
    }

    private static void commandLoop() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            out("ready pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid());
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(" ", 2);
                try {
                    if (p[0].equals("quit")) break;
                    run(p[0], p.length > 1 ? p[1] : "");
                } catch (Throwable t) {
                    out("error cmd=" + p[0] + " " + t.getClass().getName() + ": " + t.getMessage()
                            + (t.getCause() != null ? " cause=" + t.getCause() : ""));
                }
            }
        } catch (Throwable t) {
            out("fatal " + t);
        }
        if (display != null) display.release();
        System.exit(0);
    }

    private static void run(String cmd, String arg) throws Exception {
        switch (cmd) {
            case "flags": flags(); break;
            case "create": create(arg.split(" ")); break;
            case "state": out("state displayId=" + display.getDisplay().getDisplayId() + " state=" + display.getDisplay().getState()); break;
            case "shot": shot(arg); break;
            case "text": text(arg); break;
            case "release": display.release(); display = null; out("released"); break;
            case "serve": serve(arg); break;
            case "close": close(); break;
            case "removetask": removeTask(Integer.parseInt(arg)); break;
            case "sleep": Thread.sleep(Long.parseLong(arg)); out("slept"); break;
            default: out("error unknown " + cmd);
        }
    }

    private static void flags() throws Exception {
        StringBuilder b = new StringBuilder("flags");
        for (Field f : DisplayManager.class.getDeclaredFields()) {
            if (f.getName().startsWith("VIRTUAL_DISPLAY_FLAG_") && Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                f.setAccessible(true);
                b.append(' ').append(f.getName().substring(21)).append("=0x").append(Integer.toHexString(f.getInt(null)));
            }
        }
        out(b.toString());
    }

    private static int flag(String name) throws Exception {
        Field f = DisplayManager.class.getDeclaredField("VIRTUAL_DISPLAY_FLAG_" + name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    /** create WIDTH HEIGHT DPI FLAG_NAME,FLAG_NAME,... */
    private static void create(String[] a) throws Exception {
        width = Integer.parseInt(a[0]);
        height = Integer.parseInt(a[1]);
        int dpi = Integer.parseInt(a[2]);
        int flags = 0;
        for (String n : a[3].split(",")) flags |= flag(n);
        if (displays == null) {
            Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
            c.setAccessible(true);
            displays = c.newInstance(context);
        }
        idle = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2);
        idle.setOnImageAvailableListener(r -> { Image i = r.acquireLatestImage(); if (i != null) i.close(); }, handler);
        Surface surface = idle.getSurface();
        if (a.length > 4 && a[4].equals("full")) {
            full = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            full.setOnImageAvailableListener(r -> {
                Image i = r.acquireLatestImage();
                if (i == null) return;
                synchronized (Probe.class) { if (latest != null) latest.close(); latest = i; frames++; }
            }, handler);
            surface = full.getSurface();
        }
        long t = SystemClock.uptimeMillis();
        display = displays.createVirtualDisplay("android-background-test-probe", width, height, dpi, surface, flags);
        if (display == null) { out("error create returned null"); return; }
        out("created displayId=" + display.getDisplay().getDisplayId() + " flags=0x" + Integer.toHexString(flags)
                + " displayFlags=0x" + Integer.toHexString(display.getDisplay().getFlags()) + " ms=" + (SystemClock.uptimeMillis() - t));
    }

    private static void shot(String path) throws Exception {
        long t = SystemClock.uptimeMillis();
        if (full != null) {
            Bitmap copy;
            int seen;
            synchronized (Probe.class) {
                if (latest == null) { out("error shot no_frame frames=" + frames); return; }
                copy = toBitmap(latest);
                seen = frames;
            }
            long captured = SystemClock.uptimeMillis() - t;
            try (OutputStream o = new FileOutputStream(path)) { copy.compress(Bitmap.CompressFormat.PNG, 100, o); }
            out("shot mode=full path=" + path + " frames=" + seen + " captureMs=" + captured + " totalMs=" + (SystemClock.uptimeMillis() - t));
            return;
        }
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        BlockingQueue<Image> q = new ArrayBlockingQueue<>(1);
        reader.setOnImageAvailableListener(r -> {
            Image i = r.acquireLatestImage();
            if (i != null && !q.offer(i)) i.close();
        }, handler);
        display.setSurface(reader.getSurface());
        Image image = q.poll(3, TimeUnit.SECONDS);
        display.setSurface(idle.getSurface());
        if (image == null) { reader.close(); out("error shot timeout ms=" + (SystemClock.uptimeMillis() - t)); return; }
        Bitmap crop = toBitmap(image);
        image.close();
        reader.close();
        long captured = SystemClock.uptimeMillis() - t;
        try (OutputStream o = new FileOutputStream(path)) { crop.compress(Bitmap.CompressFormat.PNG, 100, o); }
        out("shot path=" + path + " captureMs=" + captured + " totalMs=" + (SystemClock.uptimeMillis() - t));
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        buf.rewind();
        int stride = plane.getRowStride() / plane.getPixelStride();
        Bitmap padded = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        return Bitmap.createBitmap(padded, 0, 0, width, height);
    }

    private static void text(String value) throws Exception {
        long t = SystemClock.uptimeMillis();
        Class<?> connCls = Class.forName("android.app.UiAutomationConnection");
        Constructor<?> cc = connCls.getDeclaredConstructor();
        cc.setAccessible(true);
        Object conn = cc.newInstance();
        Class<?> iface = Class.forName("android.app.IUiAutomationConnection");
        Object ui = null;
        String ctor = null;
        for (Constructor<?> k : android.app.UiAutomation.class.getDeclaredConstructors()) {
            Class<?>[] ps = k.getParameterTypes();
            if (ps.length == 2 && ps[1] == iface) {
                k.setAccessible(true);
                if (ps[0] == Context.class) { ui = k.newInstance(context, conn); ctor = "Context"; break; }
                if (ps[0] == Looper.class) { ui = k.newInstance(handler.getLooper(), conn); ctor = "Looper"; }
            }
        }
        android.app.UiAutomation ua = (android.app.UiAutomation) ui;
        Method connect = android.app.UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        connect.invoke(ua, android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info = ua.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ua.setServiceInfo(info);
        long connected = SystemClock.uptimeMillis() - t;
        String result = "no_focused_editable";
        String displaysSeen = "";
        try {
            int id = display.getDisplay().getDisplayId();
            AccessibilityNodeInfo target = null;
            int windows = 0;
            for (int attempt = 0; attempt < 20 && target == null; attempt++) {
                android.util.SparseArray<List<AccessibilityWindowInfo>> all = ua.getWindowsOnAllDisplays();
                StringBuilder keys = new StringBuilder();
                for (int k = 0; k < all.size(); k++) keys.append(all.keyAt(k)).append(':').append(all.valueAt(k).size()).append(',');
                displaysSeen = keys.toString();
                List<AccessibilityWindowInfo> list = all.get(id);
                windows = list == null ? 0 : list.size();
                if (list != null) for (AccessibilityWindowInfo w : list) {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (f != null && f.isEditable()) { target = f; break; }
                }
                if (target == null) Thread.sleep(100);
            }
            if (target != null) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                target.refresh();
                result = (ok ? "set" : "set_text_failed") + " now=" + target.getText();
            } else {
                result += " windowsOnDisplay=" + windows + " displays=" + displaysSeen;
            }
        } finally {
            Method disconnect = android.app.UiAutomation.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(ua);
        }
        out("text ctor=" + ctor + " connectMs=" + connected + " totalMs=" + (SystemClock.uptimeMillis() - t) + " result=" + result);
    }

    /** Removes every task on the probe display, then releases it. */
    private static void close() throws Exception {
        Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Method infos = service.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class);
        Method remove = service.getClass().getMethod("removeTask", int.class);
        StringBuilder b = new StringBuilder("close");
        for (Object info : (List<?>) infos.invoke(service, display.getDisplay().getDisplayId())) {
            int[] children = (int[]) info.getClass().getField("childTaskIds").get(info);
            int root = info.getClass().getField("taskId").getInt(info);
            for (int id : children) b.append(" child=").append(id).append(':').append(remove.invoke(service, id));
            b.append(" root=").append(root).append(':').append(remove.invoke(service, root));
        }
        display.release();
        display = null;
        out(b.toString());
    }

    private static void removeTask(int id) throws Exception {
        Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Method m = service.getClass().getMethod("removeTask", int.class);
        out("removetask id=" + id + " result=" + m.invoke(service, id));
    }

    private static void serve(String name) throws Exception {
        LocalServerSocket server = new LocalServerSocket(name);
        Thread th = new Thread(() -> {
            while (true) {
                try (LocalSocket s = server.accept()) {
                    int uid = s.getPeerCredentials().getUid();
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String msg = r.readLine();
                    s.getOutputStream().write(("peerUid=" + uid + " echo=" + msg + "\n").getBytes(StandardCharsets.UTF_8));
                    out("accepted peerUid=" + uid);
                } catch (Throwable t) {
                    out("error serve " + t);
                }
            }
        }, "probe-serve");
        th.setDaemon(true);
        th.start();
        out("serving " + name);
    }

    private static void client(String name, String msg) throws Exception {
        long t = SystemClock.uptimeMillis();
        LocalSocket s = new LocalSocket();
        s.connect(new LocalSocketAddress(name));
        s.getOutputStream().write((msg + "\n").getBytes(StandardCharsets.UTF_8));
        String reply = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        s.close();
        out("client reply=" + reply + " connectMs=" + (SystemClock.uptimeMillis() - t)
                + " sinceProcessStartMs=" + (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()));
    }

    private static synchronized void out(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field f = at.getDeclaredField("sCurrentActivityThread");
        f.setAccessible(true);
        f.set(null, thread);
        Method m = at.getDeclaredMethod("getSystemContext");
        m.setAccessible(true);
        return (Context) m.invoke(thread);
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public Context getApplicationContext() { return this; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(android.os.Process.SHELL_UID).setPackageName("com.android.shell").build();
        }
    }
}
