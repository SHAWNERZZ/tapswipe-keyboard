import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays a touch script into the device's input system, with real timing and up to ten pointers.
 *
 * Runs as the shell user through app_process, the same way scrcpy injects input:
 *
 *   CLASSPATH=/data/local/tmp/touchplayer.dex app_process / TouchPlayer /data/local/tmp/script.txt
 *
 * The shell user holds INJECT_EVENTS, so the events enter the system input pipeline and reach the
 * keyboard window exactly as a finger's would, through PointerTracker.
 *
 * Script format, one event per line, screen pixels:
 *
 *   <ms from start> <action> <action index> <pointer count> (<id> <x> <y>)...
 *
 * action: 0 down, 1 up, 2 move, 5 pointer down, 6 pointer up. Lines starting with '#' are skipped.
 * A line "wait <ms>" pauses without an event.
 */
public class TouchPlayer {
    private static Method sInject;
    private static Object sTarget;

    private static void initInjector() throws Exception {
        try {
            Class<?> c = Class.forName("android.hardware.input.InputManagerGlobal");
            sTarget = c.getMethod("getInstance").invoke(null);
            sInject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException e) {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            sTarget = c.getMethod("getInstance").invoke(null);
            sInject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
    }

    private static boolean inject(MotionEvent e) throws Exception {
        // Mode 0: asynchronous. Waiting for each event to finish would distort the timing.
        return (Boolean) sInject.invoke(sTarget, e, 0);
    }

    private static int touchDeviceId() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN)
                    == InputDevice.SOURCE_TOUCHSCREEN) return id;
        }
        return 0;
    }

    private static final class Ev {
        long t; int action; int index; int[] ids; float[] xs; float[] ys; boolean waitOnly;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: TouchPlayer <script> | TouchPlayer -");
            System.exit(2);
        }
        initInjector();
        int device = touchDeviceId();
        if (args[0].equals("-")) {
            // Server mode: read batches from stdin. A line "go" plays the batch read so far, then
            // prints "done ..." and waits for the next one. One process for a whole run saves the
            // second of app_process start-up on every word.
            BufferedReader in = new BufferedReader(new java.io.InputStreamReader(System.in));
            List<String> batch = new ArrayList<>();
            String line;
            System.out.println("ready");
            System.out.flush();
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.equals("quit")) break;
                if (line.equals("go")) {
                    String result = play(parse(batch), device);
                    batch.clear();
                    System.out.println("done " + result);
                    System.out.flush();
                } else if (!line.isEmpty()) {
                    batch.add(line);
                }
            }
            return;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        System.out.println("TouchPlayer " + play(parse(lines), device));
    }

    private static List<Ev> parse(List<String> lines) {
        List<Ev> events = new ArrayList<>();
        {
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                Ev e = new Ev();
                if (p[0].equals("wait")) {
                    e.waitOnly = true;
                    e.t = Long.parseLong(p[1]);
                    events.add(e);
                    continue;
                }
                e.t = Long.parseLong(p[0]);
                e.action = Integer.parseInt(p[1]);
                e.index = Integer.parseInt(p[2]);
                int n = Integer.parseInt(p[3]);
                e.ids = new int[n]; e.xs = new float[n]; e.ys = new float[n];
                for (int i = 0; i < n; i++) {
                    e.ids[i] = Integer.parseInt(p[4 + i * 3]);
                    e.xs[i] = Float.parseFloat(p[5 + i * 3]);
                    e.ys[i] = Float.parseFloat(p[6 + i * 3]);
                }
                events.add(e);
            }
        }
        return events;
    }

    private static String play(List<Ev> events, int device) throws Exception {
        long base = SystemClock.uptimeMillis() + 50;
        long downTime = base;
        int sent = 0, failed = 0;

        for (Ev e : events) {
            long due = base + e.t;
            long now;
            while ((now = SystemClock.uptimeMillis()) < due) {
                long gap = due - now;
                if (gap > 2) Thread.sleep(gap - 1); else Thread.yield();
            }
            if (e.waitOnly) continue;

            int n = e.ids.length;
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
            for (int i = 0; i < n; i++) {
                props[i] = new MotionEvent.PointerProperties();
                props[i].id = e.ids[i];
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = e.xs[i];
                coords[i].y = e.ys[i];
                coords[i].pressure = 1f;
                coords[i].size = 0.05f;
                coords[i].touchMajor = 20f;
                coords[i].touchMinor = 20f;
            }
            if (e.action == MotionEvent.ACTION_DOWN) downTime = due;
            int action = e.action;
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                action |= e.index << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            }
            MotionEvent me = MotionEvent.obtain(downTime, due, action, n, props, coords,
                    0, 0, 1f, 1f, device, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            if (inject(me)) sent++; else failed++;
            me.recycle();
        }
        return "sent=" + sent + " failed=" + failed;
    }
}
