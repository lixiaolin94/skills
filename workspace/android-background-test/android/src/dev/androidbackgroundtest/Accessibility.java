package dev.androidbackgroundtest;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A short-lived UiAutomation connection scoped to one display. Apps can notice accessibility while it is open,
 * so callers open it per request and close it right away.
 */
final class Accessibility implements AutoCloseable {
    static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_LABEL = 80;
    /** UiAutomation allows one connection per process. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    private final UiAutomation automation;
    private final int displayId;
    /** Windows seen by the last roots() call; zero means the display exposes no tree at all. */
    int windowCount;

    private Accessibility(UiAutomation automation, int displayId) {
        this.automation = automation;
        this.displayId = displayId;
    }

    static Accessibility open(int displayId) throws Exception {
        LOCK.lock();
        try {
            return new Accessibility(connect(), displayId);
        } catch (Exception error) {
            LOCK.unlock();
            throw error;
        }
    }

    @Override
    public void close() {
        try {
            Method disconnect = UiAutomation.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(automation);
        } catch (Exception error) {
            Coordinator.log("uiautomation disconnect failed: " + error);
        } finally {
            LOCK.unlock();
        }
    }

    private static UiAutomation connect() throws Exception {
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
        if (automation == null) throw new Session.Failure("text_unsupported", "no usable UiAutomation constructor");
        Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        try {
            // Without this flag the user's own accessibility services (screen readers, password managers) pause.
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new Session.Failure("uiautomation_busy", String.valueOf(error.getCause()));
        }
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        return automation;
    }

    /** Window roots on this display; waits briefly because windows appear shortly after connecting. */
    List<AccessibilityNodeInfo> roots() throws InterruptedException {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        for (int attempt = 0; attempt < 20 && roots.isEmpty(); attempt++) {
            SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
            List<AccessibilityWindowInfo> windows = all.get(displayId);
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) roots.add(root);
                }
            }
            if (roots.isEmpty()) Thread.sleep(100);
        }
        windowCount = roots.size();
        return roots;
    }

    AccessibilityNodeInfo focusedEditable() throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            List<AccessibilityNodeInfo> roots = roots();
            if (roots.isEmpty()) return null;
            for (AccessibilityNodeInfo root : roots) {
                AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focus != null && focus.isEditable()) return focus;
            }
            Thread.sleep(100);
        }
        return null;
    }

    static String setText(AccessibilityNodeInfo target, String value) throws Session.Failure {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            throw new Session.Failure("set_text_failed", "the field rejected ACTION_SET_TEXT");
        }
        target.refresh();
        CharSequence now = target.getText();
        return now == null ? null : now.toString();
    }

    /** One element of the display's accessibility tree, in display pixels. */
    static final class Node {
        String ref;
        final AccessibilityNodeInfo info;
        final String className;
        final String text;
        final String desc;
        final String id;
        final String hint;
        String label;
        final Rect bounds = new Rect();
        final boolean visible;
        final boolean clickable;
        final boolean longClickable;
        final boolean scrollable;
        final boolean editable;
        final boolean checkable;

        Node(AccessibilityNodeInfo info) {
            this.info = info;
            CharSequence cls = info.getClassName();
            String name = cls == null ? "" : cls.toString();
            this.className = name.substring(name.lastIndexOf('.') + 1);
            this.text = clean(info.getText());
            this.desc = clean(info.getContentDescription());
            String view = info.getViewIdResourceName();
            this.id = view == null ? null : view.substring(view.indexOf(":id/") >= 0 ? view.indexOf(":id/") + 4 : 0);
            this.hint = clean(info.getHintText());
            info.getBoundsInScreen(bounds);
            this.visible = info.isVisibleToUser() && bounds.width() > 0 && bounds.height() > 0;
            this.clickable = info.isClickable();
            this.longClickable = info.isLongClickable();
            this.scrollable = info.isScrollable();
            this.editable = info.isEditable();
            this.checkable = info.isCheckable();
        }

        boolean interactive() {
            return clickable || longClickable || scrollable || editable || checkable;
        }

        boolean hasContent() {
            return text != null || desc != null || hint != null || label != null;
        }

        /** Stable identity used for settle comparison and change lists. */
        String key() {
            return className + "|" + text + "|" + desc + "|" + id + "|" + label + "|" + bounds.toShortString();
        }

        /** Identity without position, so scrolled-but-unchanged items do not count as changes. */
        String identity() {
            return className + "|" + text + "|" + desc + "|" + id + "|" + label;
        }

        /** Short human description for candidate lists and removed items. */
        String describe() {
            String main = text != null ? text : desc != null ? desc : label != null ? label : hint != null ? hint
                    : id != null ? "#" + id : className;
            return main;
        }

        boolean matches(String key, String value, boolean exact) {
            switch (key) {
                case "text": return same(text, value, exact) || same(hint, value, exact) || same(label, value, exact);
                case "desc": return same(desc, value, exact);
                case "id": return same(id, value, exact);
                case "class": return same(className, value, exact);
                default: return false;
            }
        }

        private static boolean same(String actual, String wanted, boolean exact) {
            if (actual == null) return false;
            if (exact) return actual.equals(wanted);
            return actual.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
        }
    }

    private static String clean(CharSequence value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** Flattens the display's tree. Default mode keeps visible nodes with content or an interaction. */
    List<Node> snapshot(boolean full) throws InterruptedException {
        List<Node> nodes = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots()) collect(root, 0, full, nodes);
        int index = 0;
        for (Node node : nodes) node.ref = "e" + (++index);
        return nodes;
    }

    /** Returns the text gathered below this node, for labelling clickable containers. */
    private StringBuilder collect(AccessibilityNodeInfo info, int depth, boolean full, List<Node> out) {
        StringBuilder below = new StringBuilder();
        if (info == null || depth > MAX_DEPTH || out.size() >= MAX_NODES) return below;
        Node node = new Node(info);
        boolean keep = full || (node.visible && (node.interactive() || node.text != null || node.desc != null));
        if (keep) out.add(node);
        for (int i = 0; i < info.getChildCount(); i++) {
            StringBuilder child = collect(info.getChild(i), depth + 1, full, out);
            if (child.length() > 0 && below.length() < MAX_LABEL) {
                if (below.length() > 0) below.append(' ');
                below.append(child);
            }
        }
        if (keep && node.text == null && node.desc == null && node.interactive() && below.length() > 0) {
            String label = below.toString();
            node.label = label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label;
        }
        StringBuilder own = new StringBuilder();
        String self = node.text != null ? node.text : node.desc;
        if (self != null) own.append(self);
        if (below.length() > 0 && own.length() < MAX_LABEL) {
            if (own.length() > 0) own.append(' ');
            own.append(below);
        }
        return own;
    }

    /** Nodes matching every key of the selector; exact matches win over substring matches. */
    static List<Node> find(List<Node> nodes, java.util.Map<String, String> selector, boolean editableOnly) {
        List<Node> exact = new ArrayList<>();
        List<Node> partial = new ArrayList<>();
        for (Node node : nodes) {
            if (!node.visible || (editableOnly && !node.editable)) continue;
            boolean all = true;
            boolean allExact = true;
            for (java.util.Map.Entry<String, String> entry : selector.entrySet()) {
                if (node.matches(entry.getKey(), entry.getValue(), true)) continue;
                allExact = false;
                if (!node.matches(entry.getKey(), entry.getValue(), false)) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;
            if (allExact) exact.add(node);
            else partial.add(node);
        }
        return exact.isEmpty() ? partial : exact;
    }
}
