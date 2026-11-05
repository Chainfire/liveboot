import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "liveBootAni2/src/main/java/eu/chainfire/liveboot/Installer.java"
RUNNER = ROOT / "liveBootAni2/src/main/java/eu/chainfire/liveboot/shell/Runner.java"
SETTINGS_FRAGMENT = ROOT / "liveBootAni2/src/main/java/eu/chainfire/liveboot/SettingsFragment.java"


def java_method(source, signature):
    start = source.index("    " + signature)
    end = source.index("\n    }\n", start) + len("\n    }")
    return source[start:end]


def java_constant(source, name):
    return re.search(r"    private static final [^\n]*\b" + name + r"\b[^\n]*;", source).group()


class UpstreamIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build_dir = tempfile.TemporaryDirectory()
        installer = INSTALLER.read_text()
        runner = RUNNER.read_text()
        settings_fragment = SETTINGS_FRAGMENT.read_text()
        constants = "\n".join(
            java_constant(installer, name)
            for name in ("BOOT_SCRIPT_MARKER", "BOOT_SCRIPT_FAST_WAIT_ATTEMPTS", "BOOT_SCRIPT_MAX_WAIT_SECONDS")
        )
        harness = r"""
import java.io.File;
import java.util.*;

public class GeneratedHarness {
    static class Toolbox {
        static String command(String command) { return command; }
    }
    static class SystemProperties {
        static final Map<String, String> values = new HashMap<>();
        static String get(String key, String fallback) {
            return values.containsKey(key) ? values.get(key) : fallback;
        }
    }
    static class Point {
        int x;
        int y;
        Point() { this(0, 0); }
        Point(int x, int y) { this.x = x; this.y = y; }
        void set(int x, int y) { this.x = x; this.y = y; }
    }
    static class Surface {
        static final int ROTATION_0 = 0, ROTATION_90 = 1, ROTATION_180 = 2, ROTATION_270 = 3;
    }
    static class Display {
        static final int DEFAULT_DISPLAY = 0;
        final int width, height, rotation;
        Display(int width, int height, int rotation) {
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
        void getRealSize(Point point) { point.set(width, height); }
        int getRotation() { return rotation; }
    }
    static class DisplayManager {
        final Display defaultDisplay;
        final Display[] displays;
        DisplayManager(Display defaultDisplay, Display... displays) {
            this.defaultDisplay = defaultDisplay;
            this.displays = displays;
        }
        Display getDisplay(int id) { return defaultDisplay; }
        Display[] getDisplays() { return displays; }
    }
    static class Context {
        static final String DISPLAY_SERVICE = "display";
        final DisplayManager manager;
        Context(DisplayManager manager) { this.manager = manager; }
        Object getSystemService(String service) { return manager; }
    }
    static class Settings {
        static class StringSetting { String defaultValue = "0"; }
        StringSetting SUICIDE_DELAY_MS = new StringSetting();
    }
    static class Logger {
        static void ex(Exception error) {}
    }
    private static final int FALLBACK_BOOT_SIGNAL_WAIT = 10000;
    private static final int BOOT_STATE_LOG_INTERVAL = 2000;
    private static final int MAX_SUICIDE_DELAY_MS = 60000;
    private long mLastBootStateLog = -BOOT_STATE_LOG_INTERVAL;
    private String mLastBootStateSummary = null;
    private int logCount = 0;
    private final Settings settings = new Settings();
    private void diag(String tag, String format, Object... args) { logCount++; }
    private String getInterestingProperties() { return "properties"; }
    CONSTANTS
    BOOT_SCRIPT
    AREA
    DIMENSIONS
    NATURAL_SIZE
    DISPLAY_MANAGER
    PROPERTY
    BOOT_SIGNAL
    PID
    BOOT_LOG
    DELAY

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        if (args[0].equals("hooks")) {
            List<String> commands = new ArrayList<>();
            addDelayedBootScriptInstallCommands(commands, "/bin/sh", args[1], args[2], new String[] {args[2], args[3]});
            for (String command : commands) System.out.println(command);
            return;
        }
        GeneratedHarness test = new GeneratedHarness();
        SystemProperties.values.put("sys.boot_completed", "1");
        check(test.getBootCompletionSignal(false, 9999) == null, "fallback wait");
        check(test.getBootCompletionSignal(true, 10000) == null, "running animation");
        check("sys.boot_completed".equals(test.getBootCompletionSignal(false, 10000)), "boot completed");
        SystemProperties.values.clear();
        SystemProperties.values.put("dev.bootcomplete", "1");
        check("dev.bootcomplete".equals(test.getBootCompletionSignal(false, 10000)), "alternate signal");
        SystemProperties.values.put("service.bootanim.exit", "1");
        check("service.bootanim.exit".equals(test.getBootCompletionSignal(true, 1)), "animation signal");
        check(test.getPidFromPsLine("1000 4321 1 8000 bootanim") == 4321, "numeric user is not pid");
        check(test.getPidFromPsLine("root\t123\t1\t8000\tbootanim") == 123, "tab-separated ps");
        check(test.getPidFromPsLine("bad") == null, "malformed ps");
        check(isBetterDimensions(1080, 2400, new Point(720, 1280)), "larger display");
        check(!isBetterDimensions(0, 2400, new Point(720, 1280)), "invalid display");
        check(!isBetterDimensions(720, 1280, new Point(1080, 2400)), "smaller display");
        check(isBetterDimensions(3000, 2000, new Point(2000, 3000)), "equal-area tie");
        for (int rotation = 0; rotation < 4; rotation++) {
            boolean rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
            Display display = new Display(rotated ? 2400 : 1080, rotated ? 1080 : 2400, rotation);
            Point size = getScreenDimensionsFromDisplayManager(new Context(new DisplayManager(display)));
            check(size.x == 1080 && size.y == 2400, "default display rotation " + rotation);
        }
        Display landscape = new Display(1600, 2560, Surface.ROTATION_90);
        Point size = getScreenDimensionsFromDisplayManager(new Context(new DisplayManager(
                new Display(0, 0, Surface.ROTATION_0),
                new Display(720, 1280, Surface.ROTATION_0), landscape)));
        check(size.x == 2560 && size.y == 1600, "fallback display natural landscape");
        size = getScreenDimensionsFromDisplayManager(new Context(new DisplayManager(
                null, new Display(2000, 3000, Surface.ROTATION_0),
                new Display(2000, 3000, Surface.ROTATION_270))));
        check(size.x == 3000 && size.y == 2000, "fallback display natural size before tie break");
        check("0".equals(test.normalizeSuicideDelayValue("")), "empty delay");
        check("0".equals(test.normalizeSuicideDelayValue("-1")), "negative delay");
        check("2500".equals(test.normalizeSuicideDelayValue(" 2500 ")), "valid delay");
        check("60000".equals(test.normalizeSuicideDelayValue("60001")), "maximum delay");
        check("0".equals(test.normalizeSuicideDelayValue("invalid")), "invalid delay");
        List<Integer> pids = Arrays.asList(123);
        test.maybeLogBootState(0, true, false, true, pids, 0, null);
        test.maybeLogBootState(64, true, false, true, pids, 0, null);
        test.maybeLogBootState(1999, true, false, true, pids, 0, null);
        check(test.logCount == 1, "unchanged boot state must be rate limited");
        test.maybeLogBootState(2000, true, false, true, pids, 0, null);
        test.maybeLogBootState(2050, true, true, false, pids, 0, null);
        check(test.logCount == 3, "periodic and changed boot state logging");
    }
}

class DiagHarness {
    static class Toolbox { static void init() {} }
    static class android { static class os { static class Process { static int myPid() { return 1; } } } }
    final java.nio.file.Path log;
    String mDiagLogName = null;
    Object mDiagPrintStream = null;
    boolean opened = false;
    boolean redirected = false;

    DiagHarness(java.nio.file.Path log) { this.log = log; }
    void openDiagLog() {
        try {
            java.nio.file.Files.write(log, new byte[0]);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        opened = true;
        mDiagLogName = log.toString();
        mDiagPrintStream = new Object();
    }
    void redirectStandardStreams() { redirected = true; }
    void diag(String tag, String format, Object... args) {}
    String getInterestingProperties() { return ""; }

    void run(String[] args) {
    DIAG_INIT
    }

    public static void main(String[] args) throws Exception {
        java.nio.file.Path path = java.nio.file.Paths.get(args[0]);
        byte[] previousBoot = "previous boot diagnostics".getBytes("UTF-8");
        for (String[] testArgs : new String[][] {{"test"}, {"fallbackwidth=1080", "test", "transparent"}}) {
            java.nio.file.Files.write(path, previousBoot);
            DiagHarness test = new DiagHarness(path);
            test.run(testArgs);
            GeneratedHarness.check(!test.opened && !test.redirected, "test must not open diagnostics");
            GeneratedHarness.check(Arrays.equals(previousBoot, java.nio.file.Files.readAllBytes(path)),
                    "test must preserve previous boot diagnostics");
        }
        for (String[] bootArgs : new String[][] {{"boot"}, {"boot", "test=0"}}) {
            java.nio.file.Files.write(path, previousBoot);
            DiagHarness boot = new DiagHarness(path);
            boot.run(bootArgs);
            GeneratedHarness.check(boot.opened && boot.redirected, "boot diagnostics still enabled");
            GeneratedHarness.check(java.nio.file.Files.size(path) == 0, "boot starts a fresh diagnostic log");
        }
    }
}
"""
        on_init = java_method(runner, "protected void onInit")
        diagnostic_init = on_init[on_init.index("        Toolbox.init();"):on_init.index("        // parse options")]
        replacements = {
            "CONSTANTS": constants,
            "BOOT_SCRIPT": java_method(installer, "private static void addDelayedBootScriptInstallCommands"),
            "AREA": java_method(installer, "private static long getArea"),
            "DIMENSIONS": java_method(installer, "private static boolean isBetterDimensions"),
            "NATURAL_SIZE": java_method(installer, "private static Point getNaturalDisplaySize"),
            "DISPLAY_MANAGER": java_method(installer, "private static Point getScreenDimensionsFromDisplayManager"),
            "PROPERTY": java_method(runner, "private boolean isPropertyOne"),
            "BOOT_SIGNAL": java_method(runner, "private String getBootCompletionSignal"),
            "PID": java_method(runner, "private Integer getPidFromPsLine"),
            "BOOT_LOG": java_method(runner, "private void maybeLogBootState"),
            "DELAY": java_method(settings_fragment, "private String normalizeSuicideDelayValue"),
            "DIAG_INIT": diagnostic_init,
        }
        for marker, code in replacements.items():
            harness = harness.replace("    " + marker + "\n", code + "\n")
        path = Path(cls.build_dir.name) / "GeneratedHarness.java"
        path.write_text(harness)
        subprocess.run(["javac", str(path)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build_dir.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.files = self.root / "data/user_de/0/eu.chainfire.liveboot/files"
        self.hook = self.root / "post-fs-data"
        self.other_hook = self.root / "service"
        self.other_hook.touch()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "sleep.log"
        self.launched = self.root / "launched"
        self.template = self.root / "launcher"
        self.template.write_text('#!/bin/sh\nprintf launched > "$LAUNCHED"\n')
        (self.bin / "getprop").write_text('#!/bin/sh\nprintf "%s\\n" "$BOOT_COMPLETED"\n')
        (self.bin / "sleep").write_text(
            '#!/bin/sh\nprintf "%s\\n" "$1" >> "$SLEEP_LOG"\n'
            'if [ "$SLEEP_ACTION:$1" = "fast:0.1" ] || [ "$SLEEP_ACTION:$1" = "slow:1" ]; then\n'
            '  mkdir -p "$FILES_DIR"\n  cp "$LAUNCH_TEMPLATE" "$FILES_DIR/liveboot"\n'
            '  chmod 700 "$FILES_DIR/liveboot"\nfi\n'
        )
        for executable in self.bin.iterdir():
            executable.chmod(0o700)

    def generate_hook(self):
        result = subprocess.run(
            ["java", "-cp", self.build_dir.name, "GeneratedHarness", "hooks",
             str(self.files), str(self.hook), str(self.other_hook)],
            check=True, capture_output=True, text=True,
        )
        for command in result.stdout.splitlines():
            if command.startswith("echo "):
                subprocess.run(command, shell=True, check=True)
        subprocess.run(["sh", "-n", str(self.hook)], check=True)
        if shutil.which("shellcheck"):
            subprocess.run(["shellcheck", "-s", "sh", str(self.hook)], check=True)
        script = self.hook.read_text()
        self.assertIn("# liveboot-boot-script-v2", script)
        self.hook.write_text(script.replace("/system/bin/getprop", "getprop"))

    def run_hook(self, boot_completed="0", sleep_action="none"):
        env = os.environ.copy()
        env.update({
            "PATH": str(self.bin) + os.pathsep + env["PATH"],
            "BOOT_COMPLETED": boot_completed,
            "SLEEP_ACTION": sleep_action,
            "SLEEP_LOG": str(self.log),
            "FILES_DIR": str(self.files),
            "LAUNCH_TEMPLATE": str(self.template),
            "LAUNCHED": str(self.launched),
        })
        subprocess.run(["sh", str(self.hook)], env=env, check=True, capture_output=True, timeout=30)

    def sleeps(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def test_java_boot_and_display_logic(self):
        subprocess.run(["java", "-cp", self.build_dir.name, "GeneratedHarness", "checks"], check=True)

    def test_test_mode_preserves_boot_diagnostics(self):
        subprocess.run(["java", "-cp", self.build_dir.name, "DiagHarness", str(self.root / "diagnostics")], check=True)

    def test_present_launcher_runs_immediately(self):
        self.files.mkdir(parents=True)
        shutil.copy(self.template, self.files / "liveboot")
        (self.files / "liveboot").chmod(0o700)
        self.generate_hook()
        self.run_hook()
        self.assertTrue(self.launched.exists())
        self.assertEqual(self.sleeps(), [])

    def test_launcher_appears_during_fast_wait(self):
        self.generate_hook()
        self.run_hook(sleep_action="fast")
        self.assertTrue(self.launched.exists())
        self.assertEqual(self.sleeps(), ["0.1"])

    def test_launcher_appears_during_slow_wait(self):
        self.generate_hook()
        self.run_hook(sleep_action="slow")
        self.assertTrue(self.launched.exists())
        self.assertEqual(self.sleeps(), ["0.1"] * 50 + ["1"])

    def test_stale_hooks_are_removed_after_boot(self):
        self.files.parent.parent.mkdir(parents=True)
        self.generate_hook()
        self.run_hook(boot_completed="1")
        self.assertFalse(self.hook.exists())
        self.assertFalse(self.other_hook.exists())
        self.assertEqual(self.sleeps(), ["0.1"] * 50)

    def test_incomplete_boot_times_out_without_deleting_hooks(self):
        self.files.parent.parent.mkdir(parents=True)
        self.generate_hook()
        self.run_hook()
        self.assertTrue(self.hook.exists())
        self.assertTrue(self.other_hook.exists())
        self.assertEqual(self.sleeps(), ["0.1"] * 50 + ["1"] * 180)

    def test_unavailable_data_is_not_treated_as_uninstall(self):
        self.generate_hook()
        self.run_hook(boot_completed="1")
        self.assertTrue(self.hook.exists())
        self.assertTrue(self.other_hook.exists())
        self.assertEqual(self.sleeps(), ["0.1"] * 50 + ["1"] * 180)

    def test_install_version_matches_package_version(self):
        installer = INSTALLER.read_text()
        gradle = (ROOT / "liveBootAni2/build.gradle").read_text()
        script_version = int(re.search(r"LAST_SCRIPT_UPDATE = (\d+)", installer).group(1))
        package_version = int(re.search(r"versionCode (\d+)", gradle).group(1))
        self.assertGreater(script_version, 192)
        self.assertLessEqual(script_version, package_version)


if __name__ == "__main__":
    unittest.main()
