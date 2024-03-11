/* Copyright (C) 2011-2024 Jorrit "Chainfire" Jongma
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
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class Settings {
    protected SharedPreferences prefs = null;
    protected SharedPreferences.Editor prefsEditor = null;

    public class Setting {
        protected Settings owner = null;
        protected SharedPreferences.Editor prefsEditor = null;
        public String name = "";

        public Setting(Settings owner, String name) {
            this.owner = owner;
            this.name = name;
        }

        protected SharedPreferences.Editor edit() {
            if (owner.prefsEditor != null) {
                prefsEditor = null;
                return owner.prefsEditor;
            } else {
                prefsEditor = owner.prefs.edit();
                return prefsEditor;
            }
        }

        protected void commit() {
            if (owner.prefsEditor == null) {
                if (prefsEditor != null) {
                    prefsEditor.commit();
                    prefsEditor = null;
                }
            }
        }
    }

    public class BooleanSetting extends Setting {
        public boolean defaultValue = false;

        public BooleanSetting(Settings owner, String name, boolean defaultValue) {
            super(owner, name);
            this.defaultValue = defaultValue;
        }

        public boolean get() {
            return owner.prefs.getBoolean(name, defaultValue);
        }

        public void set(boolean value) {
            edit().
                    putBoolean(name, value);
            commit();
        }
    }

    public class IntSetting extends Setting {
        public int defaultValue = 0;

        public IntSetting(Settings owner, String name, int defaultValue) {
            super(owner, name);
            this.defaultValue = defaultValue;
        }

        public int get() {
            return owner.prefs.getInt(name, defaultValue);
        }

        public void set(int value) {
            edit().
                    putInt(name, value);
            commit();
        }
    }

    public class LongSetting extends Setting {
        public long defaultValue = 0;

        public LongSetting(Settings owner, String name, long defaultValue) {
            super(owner, name);
            this.defaultValue = defaultValue;
        }

        public long get() {
            return owner.prefs.getLong(name, defaultValue);
        }

        public void set(long value) {
            edit().
                    putLong(name, value);
            commit();
        }
    }

    public class FloatSetting extends Setting {
        public float defaultValue = 0f;

        public FloatSetting(Settings owner, String name, float defaultValue) {
            super(owner, name);
            this.defaultValue = defaultValue;
        }

        public float get() {
            return owner.prefs.getFloat(name, defaultValue);
        }

        public void set(float value) {
            edit().
                    putFloat(name, value);
            commit();
        }
    }

    public class StringSetting extends Setting {
        public String defaultValue = "";

        public StringSetting(Settings owner, String name, String defaultValue) {
            super(owner, name);
            this.defaultValue = defaultValue;
        }

        public String get() {
            return owner.prefs.getString(name, defaultValue);
        }

        public void set(String value) {
            edit().
                    putString(name, value);
            commit();
        }
    }

    private static Settings instance = null;

    private Settings(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static Settings getInstance(Context context) {
        if (instance == null) {
            instance = new Settings(context);
        }
        return instance;
    }

    @SuppressLint("CommitPrefEdits")
    public SharedPreferences.Editor beginUpdate() {
        if (prefsEditor == null) {
            prefsEditor = prefs.edit();
        }
        return prefsEditor;
    }

    public void endUpdate() {
        if (prefsEditor != null) {
            prefsEditor.commit();
            prefsEditor = null;
        }
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    public BooleanSetting      SHOWN_FOLLOW                    = new BooleanSetting(this, "shown_follow", false);

    public IntSetting          LAST_UPDATE                     = new IntSetting(this, "last_update", 0);

    public BooleanSetting      TRANSPARENT                     = new BooleanSetting(this, "transparent", false);
    public BooleanSetting      DARK                            = new BooleanSetting(this, "dark", false);

    public static final String LOGCAT_LEVELS_ALL               = "VDIWEFS";
    public static final String LOGCAT_LEVELS_DEFAULT           = LOGCAT_LEVELS_ALL;
    public static final String LOGCAT_LEVELS_NONE              = "";
    public StringSetting       LOGCAT_LEVELS                   = new StringSetting(this, "logcat", LOGCAT_LEVELS_DEFAULT);

    public static final String LOGCAT_BUFFERS_ALL              = "MSREC";
    public static final String LOGCAT_BUFFERS_DEFAULT          = "MSC";
    public static final String LOGCAT_BUFFERS_NONE             = "";
    public StringSetting       LOGCAT_BUFFERS                  = new StringSetting(this, "logcat_buffers", LOGCAT_BUFFERS_DEFAULT);

    public static final String LOGCAT_FORMAT_DEFAULT           = "brief";
    public StringSetting       LOGCAT_FORMAT                   = new StringSetting(this, "logcat_format", LOGCAT_FORMAT_DEFAULT);

    public BooleanSetting      LOGCAT_COLORS                   = new BooleanSetting(this, "logcat_colors", true);

    public static final String DMESG_ALL                       = "0-99";
    public static final String DMESG_NONE                      = "0--1";
    public BooleanSetting      DMESG                           = new BooleanSetting(this, "dmesg", true);

    public StringSetting       LINES                           = new StringSetting(this, "lines", "80");
    public BooleanSetting      WORD_WRAP                       = new BooleanSetting(this, "word_wrap", true);

    public BooleanSetting      SAVE_LOGS                       = new BooleanSetting(this, "save_logs", false);

    public BooleanSetting      HAVE_PRO_CACHED                 = new BooleanSetting(this, "have_pro_cached", false);
    public BooleanSetting      FREELOAD                        = new BooleanSetting(this, "freeload", false);
}
