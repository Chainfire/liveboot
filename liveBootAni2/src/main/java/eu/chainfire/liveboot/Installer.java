/* Copyright (C) 2011-2022 Jorrit "Chainfire" Jongma
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.chainfire.liveboot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.os.StatFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    
    private static final int LAST_SCRIPT_UPDATE = 182;
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
            boolean have = false;
            List<String> ls = Shell.SU.run(String.format(Locale.ENGLISH, "cat %s", file));
            if (ls != null) {
                for (String line : ls) {
                    if (line.contains(String.format(Locale.ENGLISH, "%s/liveboot", filesDir))) {
                        have = true;
                    }
                }
            }
            haveAll = haveAll && have;
        }
        return !haveAll;
    }
        
    @SuppressLint("SdCardPath")
    public static boolean installNeeded(Context context, Mode mode) {
        Settings settings = Settings.getInstance(context);
        return installNeededVersion(settings) || installNeededData(context) || installNeededScript(context, mode);
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
        if (settings.WORD_WRAP.get()) params.add("wordwrap");
        if (settings.SAVE_LOGS.get() && boot) params.add("save");
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
        // works around some boot-time issues on older API levels
        String shell = "/system/bin/sh";
        if (testShell("/su/bin/sush")) {
            shell = "/su/bin/sush";
        } else if (testShell("/tmp-mksh/tmp-mksh")) {
            shell = "/tmp-mksh/tmp-mksh";
        }
        return shell;
    }
    
    public static void installData(Context context) {
        // much added weirdness here to work around some issues with running during the boot process,
        // on various different API levels and root versions.
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
        if (Build.VERSION.SDK_INT == 19) { // 4.4 only, not 4.3, not 5.0 - some Note4 madness
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
//            commands.add(String.format(Locale.ENGLISH, "echo '" + Toolbox.command("cp") + " %s /dev/.app_process_liveboot' >> %s/%s", app_process, filesDir, target));
//            commands.add(String.format(Locale.ENGLISH, "echo '" + Toolbox.command("chown") + " 0.0 /dev/.app_process_liveboot' >> %s/%s", filesDir, target));
//            commands.add(String.format(Locale.ENGLISH, "echo '" + Toolbox.command("chmod") + " 0700 /dev/.app_process_liveboot' >> %s/%s", filesDir, target));
            if (secontext != null) {
                commands.add(String.format(Locale.ENGLISH, "echo 'echo \"%s\" > /proc/self/attr/current' >> %s/%s", secontext, filesDir, target));                
            }
            for (String line : getLaunchScript(context, target.equals("liveboot"))) {
                commands.add(String.format(Locale.ENGLISH, "echo '%s' >> %s/%s", line, filesDir, target));
                commands.add(String.format(Locale.ENGLISH, "%s 0700 %s/%s", Toolbox.command("chmod"), filesDir, target));
            }
//            commands.add(String.format(Locale.ENGLISH, "echo '" + Toolbox.command("rm") + " /dev/.app_process_liveboot' >> %s/%s", filesDir, target));
//            commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0755 %s/%s", filesDir, target));
        }
        
        Shell.SU.run(commands);
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
            for (String script : (mode == Mode.MAGISK_CORE) ? SYSTEM_SCRIPTS_MAGISK_CORE : SYSTEM_SCRIPTS_MAGISK_ADB) {
                commands.add(String.format(Locale.ENGLISH, "echo '#!%s' > %s", shell, script));
                commands.add(String.format(Locale.ENGLISH, "echo '{' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '    while (true); do' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '        if [ -d \"%s\" ]; then' >> %s", filesDir, script));
                commands.add(String.format(Locale.ENGLISH, "echo '            break;' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '        fi' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '        sleep 0.1' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '    done' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '    %s %s/liveboot' >> %s", shell, filesDir, script));
                commands.add(String.format(Locale.ENGLISH, "echo '} &' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s", script));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s", script));
            }
        } else if (mode == Mode.KERNELSU) {
            commands.add(Toolbox.command("mkdir") + " /data/adb/post-fs-data.d");
            commands.add(Toolbox.command("chown") + " 0.0 /data/adb/post-fs-data.d");
            commands.add(Toolbox.command("chmod") + " 0755 /data/adb/post-fs-data.d");
            for (String script : SYSTEM_SCRIPTS_KERNELSU) {
                commands.add(String.format(Locale.ENGLISH, "echo '#!%s' > %s", shell, script));
                commands.add(String.format(Locale.ENGLISH, "echo '{' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '    while (true); do' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '        if [ -d \"%s\" ]; then' >> %s", filesDir, script));
                commands.add(String.format(Locale.ENGLISH, "echo '            break;' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '        fi' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '        sleep 0.1' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '    done' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, "echo '    %s %s/liveboot' >> %s", shell, filesDir, script));
                commands.add(String.format(Locale.ENGLISH, "echo '} &' >> %s", script));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chown") + " 0.0 %s", script));
                commands.add(String.format(Locale.ENGLISH, Toolbox.command("chmod") + " 0700 %s", script));
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
