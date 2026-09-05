package eu.chainfire.liveboot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.StatFs;
import android.view.Display;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.chainfire.librootjava.AppProcess;
import eu.chainfire.librootjava.Logger;
import eu.chainfire.librootjava.Policies;
import eu.chainfire.librootjava.RootJava;
import eu.chainfire.librootjavadaemon.RootDaemon;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.Toolbox;
import eu.chainfire.liveboot.shell.Runner;

public class Installer {
    public enum Mode { SU_D, INIT_D, SU_SU_D, SBIN_SU_D, MAGISK_CORE, MAGISK_ADB, KERNELSU }

    private static final int LAST_SCRIPT_UPDATE = 195;
    private static final String BOOT_SCRIPT_MARKER = "# liveboot-boot-script-v2";
    private static final int BOOT_SCRIPT_FAST_WAIT_ATTEMPTS = 50;
    private static final int BOOT_SCRIPT_MAX_WAIT_SECONDS = 180;
    private static final String[] SYSTEM_SCRIPTS_SU_D = new String[] { "/system/su.d/0000liveboot" };
    private static final String[] SYSTEM_SCRIPTS_INIT_D = new String[] { "/system/etc/init.d/0000liveboot" };
    private static final String[] SYSTEM_SCRIPTS_SU_SU_D = new String[] { "/su/su.d/0000liveboot" };
    private static final String[] SYSTEM_SCRIPTS_SBIN_SU_D = new String[] { "/sbin/supersu/su.d/0000liveboot" };
    private static final String[] SYSTEM_SCRIPTS_MAGISK_CORE = new String[] { "/sbin/.core/img/.core/post-fs-data.d/0000liveboot", "/sbin/.core/img/.core/service.d/0000liveboot" };
    private static final String[] SYSTEM_SCRIPTS_MAGISK_ADB = new String[] { "/data/adb/post-fs-data.d/0000liveboot", "/data/adb/service.d/0000liveboot" };
    private static final String[] SYSTEM_SCRIPTS_KERNELSU = new String[] { "/data/adb/post-fs-data.d/0000liveboot", "/data/adb/service.d/0000liveboot" };

    public static String[] getScript(Mode mode) {
        switch (mode) {
            case SU_D: return SYSTEM_SCRIPTS_SU_D;
            case INIT_D: return SYSTEM_SCRIPTS_INIT_D;
            case SU_SU_D: return SYSTEM_SCRIPTS_SU_SU_D;
            case SBIN_SU_D: return SYSTEM_SCRIPTS_SBIN_SU_D;
            case MAGISK_CORE: return SYSTEM_SCRIPTS_MAGISK_CORE;
            case MAGISK_ADB: return SYSTEM_SCRIPTS_MAGISK_ADB;
            case KERNELSU: return SYSTEM_SCRIPTS_KERNELSU;
        }
        return null;
    }

    private static boolean usesDelayedBootScript(Mode mode) {
        return (mode == Mode.MAGISK_CORE) || (mode == Mode.MAGISK_ADB) || (mode == Mode.KERNELSU);
    }

    public static boolean systemFree(long wanted, int filecount) {
        try {
            StatFs fs = new StatFs("/system");

            long blocks = (wanted / fs.getBlockSizeLong()) + (filecount * 3);

            return (
                        (fs.getAvailableBlocksLong() >= blocks) ||
                        (fs.getFreeBlocksLong() >= blocks)
            );
        } catch (Exception e) {
        }
        return true;
    }

    public static Context directBootContext(Context context) {
        if (Build.VERSION.SDK_INT >= 24) {
            context = context.createDeviceProtectedStorageContext();
        }
        return context;
    }

    private static int getVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            Logger.ex(e);
            return 0;
        }
    }

    public static boolean installNeededVersion(Settings settings) {
        int lastVersion = settings.LAST_UPDATE.get();
        if ((lastVersion == 0) || (lastVersion < LAST_SCRIPT_UPDATE)) {
            return true;
        }
        return false;
    }

    public static boolean installNeededData(Context context) {
        context = directBootContext(context);

        String filesDir = context.getFilesDir().getAbsolutePath();
        return !(new File(String.format(Locale.ENGLISH, "%s/liveboot", filesDir))).exists();
    }

    public static boolean installNeededScript(Context context, Mode mode) {
        context = directBootContext(context);

        String filesDir = context.getFilesDir().getAbsolutePath();
        boolean haveAll = true;
        for (String file : getScript(mode)) {
            boolean haveLaunchCommand = false;
            boolean haveCurrentVersion = !usesDelayedBootScript(mode);
            List<String> ls = Shell.SU.run(String.format(Locale.ENGLISH, "cat %s", file));
            if (ls != null) {
                for (String line : ls) {
                    if (line.contains(String.format(Locale.ENGLISH, "%s/liveboot", filesDir))) {
                        haveLaunchCommand = true;
                    }
                    if (line.contains(BOOT_SCRIPT_MARKER)) {
                        haveCurrentVersion = true;
                    }
                }
            }
            haveAll = haveAll && haveLaunchCommand && haveCurrentVersion;
        }
        return !haveAll;
    }

    @SuppressLint("SdCardPath")
    public static boolean installNeeded(Context context, Mode mode) {
        Settings settings = Settings.getInstance(context);
        return installNeededVersion(settings) || installNeededData(context) || installNeededScript(context, mode);
    }

    private static long getArea(int width, int height) {
        return (long) width * (long) height;
    }

    private static boolean isBetterDimensions(int width, int height, Point currentBest) {
        if (width <= 0 || height <= 0) return false;
        long candidateArea = getArea(width, height);
        long currentArea = getArea(currentBest.x, currentBest.y);
        return (candidateArea > currentArea) ||
                ((candidateArea == currentArea) && ((width > currentBest.x) || ((width == currentBest.x) && (height > currentBest.y))));
    }

    private static Point getScreenDimensionsFromDisplayManager(Context context) {
        Point ret = new Point(0, 0);
        try {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                if (defaultDisplay != null) {
                    Point size = new Point();
                    defaultDisplay.getRealSize(size);
                    if (size.x > 0 && size.y > 0) {
                        ret.set(size.x, size.y);
                        return ret;
                    }
                }

                Display[] displays = displayManager.getDisplays();
                if (displays != null) {
                    for (Display display : displays) {
                        if (display == null) continue;
                        Point size = new Point();
                        display.getRealSize(size);
                        if (isBetterDimensions(size.x, size.y, ret)) {
                            ret.set(size.x, size.y);
                        }
                    }
                }
            }
        } catch(Exception e) {
            Logger.ex(e);
        }
        return ret;
    }

    private static Point getScreenDimensionsFromDumpsys() {
        Point ret = new Point(0, 0);
        Pattern realPattern = Pattern.compile("\\breal\\s+(\\d+)\\s*x\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);
        try {
            List<String> output = Shell.SU.run("dumpsys display | grep -i real | grep -vi overridedisplay");
            if (output != null) {
                for (String line : output) {
                    Matcher matcher = realPattern.matcher(line);
                    while (matcher.find()) {
                        int width = Integer.parseInt(matcher.group(1), 10);
                        int height = Integer.parseInt(matcher.group(2), 10);
                        if (isBetterDimensions(width, height, ret)) {
                            ret.set(width, height);
                        }
                    }
                }
            }
        } catch(Exception e) {
            Logger.ex(e);
        }
        return ret;
    }

    public static synchronized Point getScreenDimensions(Context context) {
        Point ret = new Point(0, 0);
        if (context != null) {
            ret = getScreenDimensionsFromDisplayManager(directBootContext(context));
        }
        if (ret.x <= 0 || ret.y <= 0) {
            ret = getScreenDimensionsFromDumpsys();
        }
        return ret;
    }

    public static synchronized List<String> getLaunchScript(Context context, boolean boot) {
        Settings settings = Settings.getInstance(context);

        context = directBootContext(context);

        boolean haveLogcat = true;
        if (
                settings.LOGCAT_LEVELS.get().equals(Settings.LOGCAT_LEVELS_NONE) ||
                settings.LOGCAT_BUFFERS.get().equals(Settings.LOGCAT_BUFFERS_NONE)
        ) {
            haveLogcat = false;
        }

        Policies.setPatched(true);
        List<String> params = new ArrayList<String>();
        params.add(context.getPackageCodePath());
        params.add(boot ? "boot" : "test");
        if (settings.TRANSPARENT.get()) params.add("transparent");
        if (settings.DARK.get()) params.add("dark");
        params.add("logcatlevels=" + settings.LOGCAT_LEVELS.get());
        params.add("logcatbuffers=" + settings.LOGCAT_BUFFERS.get());
        params.add("logcatformat=" + settings.LOGCAT_FORMAT.get());
        if (!settings.LOGCAT_COLORS.get()) params.add("logcatnocolors");
        params.add("dmesg=" + ((settings.DMESG.get() && (boot || !haveLogcat)) ? Settings.DMESG_ALL : Settings.DMESG_NONE));
        params.add("lines=" + settings.LINES.get());
        params.add("suicidedelay=" + settings.SUICIDE_DELAY_MS.get());
        if (settings.WORD_WRAP.get()) params.add("wordwrap");
        if (settings.SAVE_LOGS.get() && boot) params.add("save");
        Point dms = getScreenDimensions(context);
        params.add("fallbackwidth=" + dms.x);
        params.add("fallbackheight=" + dms.y);
        String relocate = AppProcess.shouldAppProcessBeRelocated() ? "/dev" : null;
        if (boot) {
            return RootDaemon.getLaunchScript(context, Runner.class, null, relocate, params.toArray(new String[params.size()]), BuildConfig.APPLICATION_ID + ":root");
        } else {
            return RootJava.getLaunchScript(context, Runner.class, null, relocate, params.toArray(new String[params.size()]), BuildConfig.APPLICATION_ID + ":root");
        }
    }

    private static boolean testShell(String shell) {
        List<String> ret = Shell.run("su", new String[] { shell + " -c \"echo OK\"" }, null, true);
        if (ret != null) {
            for (String line : ret) {
                if (line.contains("OK")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String getShell() {
        String shell = "/system/bin/sh";
        if (testShell("/su/bin/sush")) {
            shell = "/su/bin/sush";
        } else if (testShell("/tmp-mksh/tmp-mksh")) {
            shell = "/tmp-mksh/tmp-mksh";
        }
        return shell;
    }

    public static void installData(Context context) {
        context = directBootContext(context);

        String filesDir = context.getFilesDir().getAbsolutePath();

        String app_process = AppProcess.getAppProcess();

        List<String> commands = new ArrayList<String>();
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("rm") + " %s/app_process", filesDir));
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("rm") + " %s/liveboot", filesDir));
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("cp") + " %s %s/app_process", app_process, filesDir));
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s/app_process", filesDir));
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s/app_process", filesDir));
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("chcon") + " u:object_r:app_data_file:s0 %s/app_process", filesDir));

        String secontext = null;
        if (Build.VERSION.SDK_INT == 19) {
            String id = Toolbox.command("id");
            List<String> ret = Shell.run("su --context u:r:recovery:s0", new String[] { id, "sh -c \"" + id + "\"" }, null, false);
            if (ret != null) {
                for (String line : ret) {
                    if (line.contains("u:r:recovery:s0")) {
                        secontext = "u:r:recovery:s0";
                        break;
                    }
                    if (line.contains("u:r:init_shell:s0")) {
                        secontext = "u:r:init_shell:s0";
                        break;
                    }
                }
            }
        }

        String shell = getShell();
        for (String target : new String[] { "liveboot", "test" }) {
            commands.add(String.format(Locale.ENGLISH, "echo '#!%s' > %s/%s", shell, filesDir, target));
            if (secontext != null) {
                commands.add(String.format(Locale.ENGLISH, "echo 'echo \"%s\" > /proc/self/attr/current' >> %s/%s", secontext, filesDir, target));
            }
            for (String line : getLaunchScript(context, target.equals("liveboot"))) {
                commands.add(String.format(Locale.ENGLISH, "echo '%s' >> %s/%s", line, filesDir, target));
                commands.add(String.format(Locale.ENGLISH, "%s 0700 %s/%s", Toolbox.command("chmod"), filesDir, target));
            }
        }

        Shell.SU.run(commands);
    }

    private static void addDelayedBootScriptInstallCommands(List<String> commands, String shell, String filesDir, String script, String[] scripts) {
        commands.add(String.format(Locale.ENGLISH, "echo '#!%s' > %s", shell, script));
        commands.add(String.format(Locale.ENGLISH, "echo '%s' >> %s", BOOT_SCRIPT_MARKER, script));
        commands.add(String.format(Locale.ENGLISH, "echo '{' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '    liveboot=\"%s/liveboot\"' >> %s", filesDir, script));
        commands.add(String.format(Locale.ENGLISH, "echo '    attempts=0' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '    while [ ! -x \"$liveboot\" ] && [ \"$attempts\" -lt %d ]; do' >> %s", BOOT_SCRIPT_FAST_WAIT_ATTEMPTS, script));
        commands.add(String.format(Locale.ENGLISH, "echo '        sleep 0.1' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '        attempts=$((attempts + 1))' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '    done' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '    waited=0' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '    while [ ! -x \"$liveboot\" ]; do' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '        if [ \"$(/system/bin/getprop sys.boot_completed)\" = \"1\" ]; then' >> %s", script));
        for (String staleScript : scripts) {
            commands.add(String.format(Locale.ENGLISH, "echo '            %s -f %s' >> %s", Toolbox.command("rm"), staleScript, script));
        }
        commands.add(String.format(Locale.ENGLISH, "echo '            exit 0' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '        fi' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '        if [ \"$waited\" -ge %d ]; then' >> %s", BOOT_SCRIPT_MAX_WAIT_SECONDS, script));
        commands.add(String.format(Locale.ENGLISH, "echo '            exit 0' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '        fi' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '        sleep 1' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '        waited=$((waited + 1))' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '    done' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, "echo '    %s \"$liveboot\"' >> %s", shell, script));
        commands.add(String.format(Locale.ENGLISH, "echo '} &' >> %s", script));
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s", script));
        commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s", script));
    }

    public static void install(Context context, Mode mode) {
        Settings settings = Settings.getInstance(context);

        context = directBootContext(context);

        String filesDir = context.getFilesDir().getAbsolutePath();

        String shell = getShell();

        installData(context);
        List<String> commands = new ArrayList<String>();
        if ((mode == Mode.SU_D) || (mode == Mode.INIT_D)) {
            commands.add("mount -o rw,remount /system");
            commands.add("mount -o rw,remount /system /system");
            for (String SYSTEM_SCRIPT_SU_D : SYSTEM_SCRIPTS_SU_D) {
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("rm") + " %s", SYSTEM_SCRIPT_SU_D));
            }
            for (String SYSTEM_SCRIPT_INIT_D : SYSTEM_SCRIPTS_INIT_D) {
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("rm") + " %s", SYSTEM_SCRIPT_INIT_D));
            }
        }
        if (mode == Mode.SU_D) {
            commands.add(Toolbox.command("mkdir") + " /system/su.d");
            commands.add(Toolbox.command("chown") + " 0.0 /system/su.d");
            commands.add(Toolbox.command("chmod") + " 0700 /system/su.d");
            for (String SYSTEM_SCRIPT_SU_D : SYSTEM_SCRIPTS_SU_D) {
                commands.add(String.format(Locale.ENGLISH, "echo '#!%s' > %s", shell, SYSTEM_SCRIPT_SU_D));
                commands.add(String.format(Locale.ENGLISH, "echo '%s %s/liveboot &' >> %s", shell, filesDir, SYSTEM_SCRIPT_SU_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s", SYSTEM_SCRIPT_SU_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s", SYSTEM_SCRIPT_SU_D));
            }
        } else if (mode == Mode.INIT_D) {
            for (String SYSTEM_SCRIPT_INIT_D : SYSTEM_SCRIPTS_INIT_D) {
                commands.add(String.format(Locale.ENGLISH, "echo '#!/system/bin/sh' > %s", SYSTEM_SCRIPT_INIT_D));
                commands.add(String.format(Locale.ENGLISH, "echo '/system/bin/sh %s/liveboot &' >> %s", filesDir, SYSTEM_SCRIPT_INIT_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s", SYSTEM_SCRIPT_INIT_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s", SYSTEM_SCRIPT_INIT_D));
            }
        } else if (mode == Mode.SU_SU_D) {
            commands.add(Toolbox.command("mkdir") + " /su/su.d");
            commands.add(Toolbox.command("chown") + " 0.0 /su/su.d");
            commands.add(Toolbox.command("chmod") + " 0700 /su/su.d");
            for (String SYSTEM_SCRIPT_SU_SU_D : SYSTEM_SCRIPTS_SU_SU_D) {
                commands.add(String.format(Locale.ENGLISH, "echo '#!%s' > %s", shell, SYSTEM_SCRIPT_SU_SU_D));
                commands.add(String.format(Locale.ENGLISH, "echo '%s %s/liveboot &' >> %s", shell, filesDir, SYSTEM_SCRIPT_SU_SU_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s", SYSTEM_SCRIPT_SU_SU_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s", SYSTEM_SCRIPT_SU_SU_D));
            }
        } else if (mode == Mode.SBIN_SU_D) {
            commands.add(Toolbox.command("mkdir") + " /sbin/supersu/su.d");
            commands.add(Toolbox.command("chown") + " 0.0 /sbin/supersu/su.d");
            commands.add(Toolbox.command("chmod") + " 0700 /sbin/supersu/su.d");
            for (String SYSTEM_SCRIPT_SBIN_SU_D : SYSTEM_SCRIPTS_SBIN_SU_D) {
                commands.add(String.format(Locale.ENGLISH, "echo '#!%s' > %s", shell, SYSTEM_SCRIPT_SBIN_SU_D));
                commands.add(String.format(Locale.ENGLISH, "echo '%s %s/liveboot &' >> %s", shell, filesDir, SYSTEM_SCRIPT_SBIN_SU_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s", SYSTEM_SCRIPT_SBIN_SU_D));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s", SYSTEM_SCRIPT_SBIN_SU_D));
            }
        } else if ((mode == Mode.MAGISK_CORE) || (mode == Mode.MAGISK_ADB)) {
            String[] scripts = (mode == Mode.MAGISK_CORE) ? SYSTEM_SCRIPTS_MAGISK_CORE : SYSTEM_SCRIPTS_MAGISK_ADB;
            for (String script : scripts) {
                addDelayedBootScriptInstallCommands(commands, shell, filesDir, script, scripts);
            }
        } else if (mode == Mode.KERNELSU) {
            commands.add(Toolbox.command("mkdir") + " /data/adb/post-fs-data.d");
            commands.add(Toolbox.command("chown") + " 0.0 /data/adb/post-fs-data.d");
            commands.add(Toolbox.command("chmod") + " 0755 /data/adb/post-fs-data.d");
            for (String script : SYSTEM_SCRIPTS_KERNELSU) {
                addDelayedBootScriptInstallCommands(commands, shell, filesDir, script, SYSTEM_SCRIPTS_KERNELSU);
            }
        }
        if ((mode == Mode.SU_D) || (mode == Mode.INIT_D)) {
            commands.add("mount -o ro,remount /system /system");
            commands.add("mount -o ro,remount /system");
        }
        Shell.SU.run(commands);

        if (!installNeededScript(context, mode)) {
            settings.LAST_UPDATE.set(getVersion(context));
        }
    }

    public static void uninstall(Context context) {
        List<String> ls = new ArrayList<String>();
        for (String file : SYSTEM_SCRIPTS_SU_D) {
            ls.add("ls -l " + file);
        }
        for (String file : SYSTEM_SCRIPTS_INIT_D) {
            ls.add("ls -l " + file);
        }
        List<String> ret = Shell.run("su", ls.toArray(new String[ls.size()]), null, false);

        boolean system = false;
        if (ret != null) {
            for (String line : ret) {
                if (line.contains("liveboot")) {
                    system = true;
                    break;
                }
            }
        }

        List<String> commands = new ArrayList<String>();
        if (system) {
            commands.add("mount -o rw,remount /system");
            commands.add("mount -o rw,remount /system /system");
        }
        for (String[] scripts : new String[][] {
            SYSTEM_SCRIPTS_SU_D,
            SYSTEM_SCRIPTS_INIT_D,
            SYSTEM_SCRIPTS_SU_SU_D,
            SYSTEM_SCRIPTS_SBIN_SU_D,
            SYSTEM_SCRIPTS_MAGISK_CORE,
            SYSTEM_SCRIPTS_MAGISK_ADB,
            SYSTEM_SCRIPTS_KERNELSU
        }) {
            for (String script : scripts) {
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("rm") + " %s", script));
            }
        }
        if (system) {
            commands.add("mount -o ro,remount /system /system");
            commands.add("mount -o ro,remount /system");
        }
        Shell.SU.run(commands);
    }

    public static void installAsync(Activity activity, Mode mode, Runnable onDone) {
        (new Async(activity, Async.ACTION_INSTALL, mode, onDone)).execute();
    }

    public static void uninstallAsync(Activity activity, Runnable onDone) {
        (new Async(activity, Async.ACTION_UNINSTALL, null, onDone)).execute();
    }

    private static class Async extends AsyncTask<Void, Integer, Void> {
        public static final int ACTION_INSTALL = 1;
        public static final int ACTION_UNINSTALL = 2;

        private final Context context;
        private final int action;
        private final Mode mode;
        private final Runnable onDone;
        private ProgressDialog dialog;

        public Async(Context context, int action, Mode mode, Runnable onDone) {
            this.context = context;
            this.action = action;
            this.mode = mode;
            this.onDone = onDone;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            dialog.setMessage(context.getString(values[0]));
        }

        @Override
        protected void onPreExecute() {
            dialog = new ProgressDialog(context);
            dialog.setMessage(context.getString(R.string.loading));
            dialog.setIndeterminate(true);
            dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            if (action == ACTION_INSTALL) {
                publishProgress(R.string.installing);
                install(context, mode);
            } else if (action == ACTION_UNINSTALL) {
                publishProgress(R.string.uninstalling);
                uninstall(context);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            try {
                dialog.dismiss();
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                Logger.ex(e);
            }
        }
    }
}
