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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.liveboot.shell.Logcat;
import eu.chainfire.liveboot.Installer.Mode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SettingsFragment extends PreferenceFragment implements OnSharedPreferenceChangeListener {    
    private String APP_TITLE = "";
    private SharedPreferences prefs = null;
    private Settings settings = null;
    
    private MultiSelectListPreference prefLogcatLevels = null;
    private MultiSelectListPreference prefLogcatBuffers = null;
    private ListPreference prefLogcatFormat = null;
    private ListPreference prefLines = null;   
    
    private InAppPurchases iap = null;
    private volatile boolean pro = false;
    private volatile boolean proReal = false;
    
    private Installer.Mode mode = Mode.SU_D;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);     
        
        settings = Settings.getInstance(getActivity());
        prefs = settings.getPrefs();
                
        iap = new InAppPurchases(getActivity());
        
        (new Startup(getActivity())).execute();
    }
    
    private void setAutoExit(boolean autoExit) {
        Activity activity = getActivity();
        if ((activity != null) && (activity instanceof MainActivity)) {
            ((MainActivity)activity).setAutoExit(autoExit);
        }
    }
    
    private void message(Activity activity, int messageId, int negativeId, Runnable onNegative, int neutralId, Runnable onNeutral, int positiveId, Runnable onPositive) {
        final Runnable rNeg = onNegative;
        final Runnable rNeut = onNeutral;
        final Runnable rPos = onPositive;
        AlertDialog.Builder builder = (new AlertDialog.Builder(activity))
            .setTitle(R.string.app_name)
            .setMessage(messageId)
            .setCancelable(true)            
            .setOnCancelListener(new DialogInterface.OnCancelListener() {                    
                @Override
                public void onCancel(DialogInterface dialog) {
                    if (rNeg != null) { 
                        rNeg.run();
                    }
                }
            });            
        if (negativeId > 0) {
            builder.setNegativeButton(negativeId, new DialogInterface.OnClickListener() {                
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (rNeg != null) { 
                        rNeg.run();
                    }
                }
            });
        }
        if (neutralId > 0) {
            builder.setNeutralButton(neutralId, new DialogInterface.OnClickListener() {                
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (rNeut != null) { 
                        rNeut.run();
                    }
                }
            });
        }
        if (positiveId > 0) {
            builder.setPositiveButton(positiveId, new DialogInterface.OnClickListener() {                
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (rPos != null) { 
                        rPos.run();
                    }
                }
            });
        }
        builder.show();                                    
    }

    private class Startup extends AsyncTask<Void, Void, Integer> {        
        private final Activity activity;
        private ProgressDialog dialog = null;
        
        public Startup(Activity activity) {
            this.activity = activity;   
        }

        @Override
        protected void onPreExecute() {
            setAutoExit(false);
            
            dialog = new ProgressDialog(activity);
            dialog.setMessage(activity.getString(R.string.loading));
            dialog.setIndeterminate(true);
            dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            dialog.setCancelable(false);
            dialog.show();            
        }

        @Override
        protected Integer doInBackground(Void... params) {            
            Installer.installData(activity);
            
            final boolean[] suGranted = { false };
            
            Shell.Interactive shell = (new Shell.Builder())
                    .useSU()
                    .addCommand("id", 0, new Shell.OnCommandResultListener() {                        
                        @Override
                        public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                            synchronized (suGranted) {
                                suGranted[0] = true;                                
                            }
                        }
                    })
                    .open();
                                    
            while (shell.isRunning()) {
                synchronized (suGranted) {
                    if (suGranted[0]) break;
                }
                try { 
                    Thread.sleep(32);
                } catch (Exception e) {                    
                }
            }            
            if (!suGranted[0]) return 1;
            
            Shell.SU.clearCachedResults();
            String verString = Shell.SU.version(false);
            String verInt = Shell.SU.version(true);
            int verIntInt = 0;
            try {
                verIntInt = Integer.valueOf(verInt, 10);
            } catch (Exception e) {      
                Logger.ex(e);
            }            
            Logger.dp("VERSION", "%s %d", verString, verIntInt);
            
            boolean root = suGranted[0];
            if (!root) return 1;
            
            boolean SuperSU = true;
            if ((verString == null) || (!verString.endsWith(":SUPERSU"))) SuperSU = false;
            boolean SuperSU240 = SuperSU;
            if (SuperSU && ((verInt == null) || (verIntInt < 240))) SuperSU240 = false;
            
            boolean initd = false;
            boolean susud = false;
            boolean sbinsud = false;
            boolean magiskcore = false;
            boolean magiskadb = false;
            boolean kernelsu = false;

            List<String> ls = Shell.SU.run("ls -ld /system/etc/init.d /su/su.d /sbin/supersu/su.d /data/adb/post-fs-data.d /sbin/.core/img/.core/post-fs-data.d /data/adb/ksud 2> /dev/null");
            if (ls != null) {
                for (String line : ls) {
                    if (line.contains("init.d")) initd = true;
                    if (line.contains("su.d")) susud = true;
                    if (line.contains("su.d")) sbinsud = true;
                    if (line.contains("/adb/post-fs-data.d")) magiskadb = true;
                    if (line.contains("/.core/post-fs-data.d")) magiskcore = true;
                    if (line.contains("/adb/ksud")) kernelsu = true;
                }
            }

            if (SuperSU && !SuperSU240 && !initd) return 2;
            if (!SuperSU && !initd && !magiskcore && !magiskadb && !kernelsu) return 3;

            if (magiskadb) {
                mode = Mode.MAGISK_ADB;
            } else if (magiskcore) {
                mode = Mode.MAGISK_CORE;
            } else if (sbinsud) {
                mode = Mode.SBIN_SU_D;
            } else if (susud) {
                mode = Mode.SU_SU_D;
            } else if (!SuperSU240 && initd) {
                mode = Mode.INIT_D;
            } else if (kernelsu) {
                mode = Mode.KERNELSU;
            }

            if (iap.haveService()) {
                for (int i = 0; i < 10; i++) {
                    if (iap.isServiceConnected()) break;
                    try {
                        Thread.sleep(64);
                    } catch (Exception e) {
                    }
                }
                if (iap.isServiceConnected()) {
                    for (int i = 0; i < 10; i++) {
                        if (iap.isReady()) break;
                        try {
                            Thread.sleep(64);
                        } catch (Exception e) {
                        }
                    }
                    InAppPurchases.Order[] orders = iap.getOrders(null, false);
                    if ((orders != null) && (orders.length > 0)) {
                        proReal = true;
                    }
                }
                
                PackageManager packageManager = (PackageManager)activity.getPackageManager();
                if (!proReal) {
                    try {
                        proReal = (packageManager.getPackageInfo("eu.chainfire.livedmesg", 0) != null);
                    } catch (NameNotFoundException e) {
                    }
                }                
                if (!proReal) {
                    try {
                        proReal = (packageManager.getPackageInfo("eu.chainfire.livelogcat", 0) != null);
                    } catch (NameNotFoundException e) {
                    }
                }   
                
                if (!proReal) {
                    if (settings.HAVE_PRO_CACHED.get()) {
                        proReal = true;
                    }
                } else {
                    settings.HAVE_PRO_CACHED.set(true);
                }
            } else {
                proReal = true;
            }
            
            pro = proReal;
            if (!pro) {
                if (settings.FREELOAD.get()) {
                    pro = true;
                }
            }
            
            if (Installer.installNeeded(activity, mode)) return 4;            
            
            return 0;
        }
        
        private void showPreferences() {
            setPreferenceScreen(createPreferenceHierarchy());            
        }
        
        private void closeScreen() {
            activity.finish();                                    
        }
        
        @Override
        protected void onPostExecute(Integer result) {
            setAutoExit(true);
            
            try {
                dialog.dismiss();
            
                if (result == 0) {
                    showPreferences();
                } else if (result == 1) {
                    message(activity, R.string.error_not_rooted, R.string.generic_ok, new Runnable() {
                        public void run() {
                            closeScreen();
                        }
                    }, 0, null, 0, null);
                } else if (result == 2) {
                    message(activity, R.string.error_supersu_old, R.string.generic_ok, new Runnable() {
                        public void run() {
                            closeScreen();
                        }
                    }, 0, null, 0, null);
                } else if (result == 3) {
                    message(activity, R.string.error_no_supersu_nor_initd, R.string.generic_ok, new Runnable() {
                        public void run() {
                            closeScreen();
                        }
                    }, 0, null, 0, null);
                } else if (result == 4) {
                    if ((mode != Mode.SU_D) && (mode != Mode.INIT_D)) {
                        Installer.installAsync(activity, mode, new Runnable() {
                            @Override
                            public void run() {
                                showPreferences();
                            }
                        });
                    } else {
                        if (!Installer.systemFree(1 * 1024, 2)) {
                            message(activity, R.string.error_install_nospace, R.string.generic_close, new Runnable() {
                                public void run() {
                                    closeScreen();
                                }
                            }, 0, null, 0, null);
                        } else {
                            message(activity, R.string.error_install_needed, R.string.generic_cancel, new Runnable() {
                                public void run() {
                                    closeScreen();
                                }
                            }, 0, null, R.string.generic_install, new Runnable() {
                                public void run() {
                                    Installer.installAsync(activity, mode, new Runnable() {
                                        @Override
                                        public void run() {
                                            showPreferences();
                                        }
                                    });
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {                
                Logger.ex(e);
            }            
        }
    }
    
    private void disableIfNotPro(Activity activity, Preference preference) {
        if (!pro) {
            preference.setEnabled(false);
            preference.setSummary(String.format("%s\n%s", (String)preference.getSummary(), activity.getString(R.string.pro_required)));
        }
    }

    private PreferenceScreen createPreferenceHierarchy() {
        final Activity activity = getActivity();
        if (activity == null) return null;

        PreferenceScreen root = getPreferenceManager().createPreferenceScreen(activity);
        
        APP_TITLE = activity.getString(R.string.app_name);
        String titleLong = APP_TITLE;
        if (proReal) {
            titleLong = titleLong + " Pro";
        } else if (pro) {
            titleLong = titleLong + " PseudoPro";
        }
        try {
            PackageInfo pkg = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            titleLong = titleLong + " v" + pkg.versionName;
        } catch (Exception e) {            
        }
        
        Preference copyright = new Preference(activity);
        copyright.setTitle(titleLong);
        copyright.setSummary(R.string.app_details);
        copyright.setKey("copyright");
        copyright.setEnabled(true);
        copyright.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(getString(R.string.app_website_url)));
                startActivity(i);
                return false;
            }
        });             
        root.addPreference(copyright);
        
        if (!proReal) {
            Preference prefPurchase = Pref.Preference(getActivity(), null, R.string.settings_donate_title, R.string.settings_donate_description, true, new OnPreferenceClickListener() {                
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if ((iap != null) && iap.haveService() && iap.isServiceConnected()) {
                        InAppPurchases.InAppPurchase buy = iap.getInAppPurchase(InAppPurchases.PURCHASE_KEY);
                        if (buy != null) {
                            setAutoExit(false);
                            if (!iap.purchase(buy, getActivity(), (MainActivity)getActivity())) {
                                setAutoExit(true);
                            }
                        }
                    }
                    return false;
                }
            });
            root.addPreference(prefPurchase);
        }
        
        PreferenceCategory catLogcat = Pref.Category(activity, root, R.string.settings_category_logcat);
        
        {
            Set<String> logcatSet = new HashSet<String>();
            CharSequence[] logcatEntries = new CharSequence[Logcat.INDEX_LEVEL_LAST + 1 - Logcat.INDEX_LEVEL_FIRST];
            CharSequence[] logcatEntryValues = new CharSequence[Logcat.INDEX_LEVEL_LAST + 1 - Logcat.INDEX_LEVEL_FIRST];
            String logcatStored = settings.LOGCAT_LEVELS.get(); 
            for (int i = Logcat.INDEX_LEVEL_FIRST; i <= Logcat.INDEX_LEVEL_LAST; i++) {
                String value = "" + Logcat.LEVEL_CHARACTERS[i];
                logcatEntries[i - Logcat.INDEX_LEVEL_FIRST] = activity.getString(Logcat.LEVEL_DESCRIPTIONS[i]);
                logcatEntryValues[i - Logcat.INDEX_LEVEL_FIRST] = value;
                if (logcatStored.contains(value)) logcatSet.add(value);
            }        
            prefLogcatLevels = new MultiSelectListPreference(activity);
            prefLogcatLevels.setTitle(R.string.settings_logcat_levels_title);
            prefLogcatLevels.setDialogTitle(R.string.settings_logcat_levels_title);
            prefLogcatLevels.setPersistent(false);
            prefLogcatLevels.setEntries(logcatEntries);
            prefLogcatLevels.setEntryValues(logcatEntryValues);
            prefLogcatLevels.setValues(logcatSet);
            prefLogcatLevels.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {            
                @SuppressWarnings("unchecked")
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    Set<String> values = (Set<String>)newValue;
                    StringBuilder sb = new StringBuilder();
                    for (int i = Logcat.INDEX_LEVEL_FIRST; i <= Logcat.INDEX_LEVEL_LAST; i++) {
                        if (values.contains("" + Logcat.LEVEL_CHARACTERS[i])) {
                            sb.append(Logcat.LEVEL_CHARACTERS[i]);
                        }                    
                    }
                    settings.LOGCAT_LEVELS.set(sb.toString());
                    return true;
                }
            });
            catLogcat.addPreference(prefLogcatLevels);
        }
        
        {
            if (!pro) {
                if (!settings.LOGCAT_BUFFERS.get().equals(settings.LOGCAT_BUFFERS.defaultValue)) {
                    settings.LOGCAT_BUFFERS.set(settings.LOGCAT_BUFFERS.defaultValue);
                }
            }
            
            Set<String> logcatSet = new HashSet<String>();
            CharSequence[] logcatEntries = new CharSequence[Logcat.INDEX_BUFFER_LAST + 1 - Logcat.INDEX_BUFFER_FIRST];
            CharSequence[] logcatEntryValues = new CharSequence[Logcat.INDEX_BUFFER_LAST + 1 - Logcat.INDEX_BUFFER_FIRST];
            String logcatStored = settings.LOGCAT_BUFFERS.get(); 
            for (int i = Logcat.INDEX_BUFFER_FIRST; i <= Logcat.INDEX_BUFFER_LAST; i++) {
                String value = "" + Logcat.BUFFER_CHARACTERS[i];
                logcatEntries[i - Logcat.INDEX_BUFFER_FIRST] = activity.getString(Logcat.BUFFER_DESCRIPTIONS[i]);
                logcatEntryValues[i - Logcat.INDEX_BUFFER_FIRST] = value;
                if (logcatStored.contains(value)) logcatSet.add(value);
            }        
            prefLogcatBuffers = new MultiSelectListPreference(activity);
            prefLogcatBuffers.setTitle(R.string.settings_logcat_buffers_title);
            prefLogcatBuffers.setDialogTitle(R.string.settings_logcat_buffers_title);
            prefLogcatBuffers.setPersistent(false);
            prefLogcatBuffers.setEntries(logcatEntries);
            prefLogcatBuffers.setEntryValues(logcatEntryValues);
            prefLogcatBuffers.setValues(logcatSet);
            prefLogcatBuffers.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {            
                @SuppressWarnings("unchecked")
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    Set<String> values = (Set<String>)newValue;
                    StringBuilder sb = new StringBuilder();
                    for (int i = Logcat.INDEX_BUFFER_FIRST; i <= Logcat.INDEX_BUFFER_LAST; i++) {
                        if (values.contains("" + Logcat.BUFFER_CHARACTERS[i])) {
                            sb.append(Logcat.BUFFER_CHARACTERS[i]);
                        }                    
                    }
                    settings.LOGCAT_BUFFERS.set(sb.toString());
                    return true;
                }
            });
            catLogcat.addPreference(prefLogcatBuffers);
            disableIfNotPro(activity, prefLogcatBuffers);
        }
        
        {
            if (!pro) {
                if (!settings.LOGCAT_FORMAT.get().equals(settings.LOGCAT_FORMAT.defaultValue)) {
                    settings.LOGCAT_FORMAT.set(settings.LOGCAT_FORMAT.defaultValue);
                }
            }
            CharSequence[] logcatEntries = new CharSequence[Logcat.FORMAT_NAMES.length];
            CharSequence[] logcatEntryValues = new CharSequence[Logcat.FORMAT_NAMES.length];
            for (int i = 0; i < Logcat.FORMAT_NAMES.length; i++) {
                logcatEntries[i] = getString(Logcat.FORMAT_DESCRIPTIONS[i]);
                logcatEntryValues[i] = Logcat.FORMAT_NAMES[i];
            }
            prefLogcatFormat = Pref.List(activity, catLogcat, R.string.settings_logcat_format_title, R.string.settings_logcat_format_description, R.string.settings_logcat_format_title, settings.LOGCAT_FORMAT.name, settings.LOGCAT_FORMAT.defaultValue, logcatEntries, logcatEntryValues);
            disableIfNotPro(activity, prefLogcatFormat);
        }
        
        Pref.Check(activity, catLogcat, R.string.settings_logcat_colors_title, R.string.settings_logcat_colors_description, settings.LOGCAT_COLORS.name, settings.LOGCAT_COLORS.defaultValue);        

        PreferenceCategory catDmesg = Pref.Category(activity, root, R.string.settings_category_dmesg);
        Pref.Check(activity, catDmesg, R.string.settings_dmesg_title, R.string.settings_dmesg_description, settings.DMESG.name, settings.DMESG.defaultValue);        

        PreferenceCategory catOptions = Pref.Category(activity, root, R.string.settings_category_settings);
        
        if (!pro) {
            if (settings.TRANSPARENT.get()) {
                settings.TRANSPARENT.set(false);
            }
        }
        disableIfNotPro(activity, Pref.Check(activity, catOptions, R.string.settings_transparent_title, R.string.settings_transparent_description, settings.TRANSPARENT.name, settings.TRANSPARENT.defaultValue));
        
        Pref.Check(activity, catOptions, R.string.settings_dark_title, R.string.settings_dark_description, settings.DARK.name, settings.DARK.defaultValue);
        
        CharSequence[] lines = new CharSequence[] {
                "20",
                "40",
                "60",
                "80",
                "100",
                "120",
                "140",
                "160"
        };
        prefLines = Pref.List(activity, catOptions, R.string.settings_lines_title, 0, R.string.settings_lines_title, settings.LINES.name, settings.LINES.defaultValue, lines, lines, true);
        
        Pref.Check(activity, catOptions, R.string.settings_wordwrap_title, R.string.settings_wordwrap_description, settings.WORD_WRAP.name, settings.WORD_WRAP.defaultValue);
        
        Pref.Check(activity, catOptions, R.string.settings_save_logs_title, R.string.settings_save_logs_description, settings.SAVE_LOGS.name, settings.SAVE_LOGS.defaultValue);

        PreferenceCategory catMisc = Pref.Category(activity, root, R.string.settings_category_misc);
        
        Pref.Preference(activity, catMisc, R.string.settings_test_title, R.string.settings_test_description, true, new OnPreferenceClickListener() {          
            public boolean onPreferenceClick(Preference preference) {
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Installer.installData(activity);
                        Shell.SU.run(Installer.directBootContext(activity).getFilesDir() + "/test");
                    }
                })).start();
                return false;
            }
        });
        
        Pref.Preference(activity, catMisc, R.string.settings_uninstall_title, R.string.settings_uninstall_description, true, new OnPreferenceClickListener() {          
            public boolean onPreferenceClick(Preference preference) {
                Installer.uninstallAsync(activity, new Runnable() {
                    public void run() {
                        activity.finish();                        
                    }
                });
                return false;
            }
        });  
        
        Pref.Preference(activity, catMisc, R.string.settings_reboot_title, R.string.settings_reboot_description, true, new OnPreferenceClickListener() {          
            public boolean onPreferenceClick(Preference preference) {
                message(activity, R.string.settings_reboot_confirm, R.string.generic_cancel, null, 0, null, R.string.settings_reboot_title, new Runnable() {
                    public void run() {
                        (new Thread(new Runnable() {                    
                            @Override
                            public void run() {
                                Shell.SU.run("reboot");
                            }
                        })).start();                        
                    }
                });
                return false;
            }
        });  
                
        if (!proReal) {
            CheckBoxPreference prefFreeload = Pref.Check(activity, catMisc, R.string.settings_freeload_title, R.string.settings_freeload_description, settings.FREELOAD.name, settings.FREELOAD.defaultValue);
            prefFreeload.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {                
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    activity.finish();
                    activity.startActivity(new Intent(activity, MainActivity.class));                    
                    return true;
                }
            });
        }
        
        PreferenceCategory catMarket = Pref.Category(activity, root, R.string.settings_category_market);        
        Pref.Preference(activity, catMarket, R.string.settings_market, R.string.settings_market_description, true, new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setData(Uri.parse("market://search?q=pub:Chainfire"));
                    startActivity(i);       
                } catch (Exception e) {
                    // market not installed
                }
                return false;
            }
        });             
        
        Pref.Preference(activity, catMarket, R.string.follow_pref_title, R.string.follow_pref_desc, true, new OnPreferenceClickListener() {         
            @Override
            public boolean onPreferenceClick(Preference preference) {
                showFollow(false);
                return false;
            }
        });
        
        updatePrefs(null);
        prefs.registerOnSharedPreferenceChangeListener(this);

        boolean shownPopup = false;        
        if (!shownPopup) {
            if (!settings.SHOWN_FOLLOW.get()) {
                shownPopup = true;
                settings.SHOWN_FOLLOW.set(true);
                showFollow(true);
            }
        }
        
        if (mode == Mode.INIT_D) {
            message(activity, R.string.warning_initd, 0, null, R.string.generic_ok, null, 0, null);
        }

        try {
            Installer.directBootContext(activity).getFilesDir().mkdirs();
        } catch (Exception e) {
        }
        
        return root;
    }
    
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        try {
            updatePrefs(key);            
        } catch (Throwable t) {            
        }
    }   
    
    private void updatePrefs(String key) {
        final Activity activity = getActivity();
        
        if ((key == null) || key.equals(settings.LOGCAT_LEVELS.name)) {
            if (prefLogcatLevels != null) {
                String display = null;
                String values = settings.LOGCAT_LEVELS.get();
                if (values.length() == 0) {
                    display = getString(R.string.generic_none);
                } else if (values.length() == 7) {
                    display = getString(R.string.generic_all);
                } else {
                    StringBuilder parts = new StringBuilder();
                    for (int i = Logcat.INDEX_LEVEL_FIRST; i <= Logcat.INDEX_LEVEL_LAST; i++) {
                        String value = "" + Logcat.LEVEL_CHARACTERS[i];
                        if (values.contains(value)) {
                            parts.append(getString(Logcat.LEVEL_DESCRIPTIONS[i]));
                            parts.append(", ");
                        }
                    }
                    display = parts.toString();
                    display = display.substring(0, display.length() - 2);
                }
                prefLogcatLevels.setSummary(String.format(Locale.ENGLISH, "%s\n[ %s ]",
                        getString(R.string.settings_logcat_levels_description),
                        display
                ));
            }            
        }
        
        if ((key == null) || key.equals(settings.LOGCAT_BUFFERS.name)) {
            if (prefLogcatBuffers != null) {
                String display = null;
                String values = settings.LOGCAT_BUFFERS.get();
                if (values.length() == 0) {
                    display = getString(R.string.generic_none);
                } else if (values.length() == 7) {
                    display = getString(R.string.generic_all);
                } else {
                    StringBuilder parts = new StringBuilder();
                    for (int i = Logcat.INDEX_BUFFER_FIRST; i <= Logcat.INDEX_BUFFER_LAST; i++) {
                        String value = "" + Logcat.BUFFER_CHARACTERS[i];
                        if (values.contains(value)) {
                            parts.append(getString(Logcat.BUFFER_DESCRIPTIONS[i]));
                            parts.append(", ");
                        }
                    }
                    display = parts.toString();
                    display = display.substring(0, display.length() - 2);
                }
                prefLogcatBuffers.setSummary(String.format(Locale.ENGLISH, "%s\n[ %s ]",
                        getString(R.string.settings_logcat_buffers_description),
                        display
                ));
                if (activity != null) disableIfNotPro(activity, prefLogcatBuffers);
            }            
        }
        
        if ((key == null) || key.equals(settings.LOGCAT_FORMAT.name)) {
            if (prefLogcatFormat != null) {
                String display = null;
                String value = settings.LOGCAT_FORMAT.get();
                for (int i = 0; i < Logcat.FORMAT_NAMES.length; i++) {
                    if (Logcat.FORMAT_NAMES[i].equals(value)) {
                        display = getString(Logcat.FORMAT_DESCRIPTIONS[i]);
                        break;
                    }
                }
                prefLogcatFormat.setSummary(String.format(Locale.ENGLISH, "%s\n[ %s ]",
                        getString(R.string.settings_logcat_format_description),
                        display
                ));                
            }
            if (activity != null) disableIfNotPro(activity, prefLogcatFormat);
        }

        if ((key == null) || key.equals(settings.LINES.name)) {
            if (prefLines != null) {
                prefLines.setSummary(String.format(Locale.ENGLISH, "%s\n[ %s ]",
                        getString(R.string.settings_lines_description),
                        settings.LINES.get()
                ));
            }
        }
        
        if (key != null) {
            if (activity != null) {
                (new Thread(new Runnable() {                
                    @Override
                    public void run() {
                        Installer.installData(activity);                    
                    }
                })).start();
            }
        }
    }
        
    private void showFollow(boolean startup) {
        final Activity activity = getActivity();
        if (activity == null) return;
        
        if (startup) {
            AlertDialog.Builder builder = (new AlertDialog.Builder(activity)).
                setTitle(R.string.follow_popup_title).
                setMessage(R.string.follow_popup_desc).
                setCancelable(true).
                setPositiveButton(R.string.follow_twitter, new OnClickListener() {                  
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse("http://www.twitter.com/ChainfireXDA"));
                        startActivity(i);                               
                    }
                }).
                setNegativeButton(R.string.follow_nothanks, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                });
            try {
                builder.show();
            } catch (Exception e) {                                 
            }           
        } else {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse("http://www.twitter.com/ChainfireXDA"));
            startActivity(i);
        }
    }

    @Override
    public void onDestroy() {
        try {
            iap.close();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
