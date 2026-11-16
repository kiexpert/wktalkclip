import app.wktalkclip.StartPlan;

public class StartPlanTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println("RESULT " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) {
            fails++;
        }
    }

    static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(s)) {
                return i;
            }
        }
        return -1;
    }

    public static void main(String[] x) {
        String[] normal = StartPlan.steps(false);
        String[] simple = StartPlan.steps(true);
        check("the recognizer starts first in the normal start", normal.length > 0 && "recognizer".equals(normal[0]));
        check("simple mode does only the recognizer", simple.length == 1 && "recognizer".equals(simple[0]));
        check("normal start still has the day clipboard read", indexOf(normal, "read-clip") > 0);
        check("normal start still starts the recording service", indexOf(normal, "service") > 0);
        check("normal start still watches calls", indexOf(normal, "call-watch") > 0);
        check("the notification permission dialog comes last", "notification-permission".equals(normal[normal.length - 1]));
        check("nothing that could open a dialog comes before the recognizer", indexOf(normal, "notification-permission") > indexOf(normal, "recognizer"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
