/* Copyright (C) 2011-2020 Jorrit "Chainfire" Jongma
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

package eu.chainfire.liveboot.shell;

import android.graphics.Color;
import android.os.Handler;
import android.os.SystemClock;

import java.io.File;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.StreamGobbler;
import eu.chainfire.liveboot.R;

public class Logcat {    
    public static final int INDEX_LEVEL_VERBOSE = 0;
    public static final int INDEX_LEVEL_DEBUG   = 1;
    public static final int INDEX_LEVEL_INFO    = 2;
    public static final int INDEX_LEVEL_WARNING = 3;
    public static final int INDEX_LEVEL_ERROR   = 4;
    public static final int INDEX_LEVEL_FATAL   = 5;
    public static final int INDEX_LEVEL_SILENT  = 6;
    
    public static final int INDEX_LEVEL_FIRST   = INDEX_LEVEL_VERBOSE;
    public static final int INDEX_LEVEL_LAST    = INDEX_LEVEL_SILENT;
    
    public static final char[] LEVEL_CHARACTERS = new char[] { 'V', 'D', 'I', 'W', 'E', 'F', 'S' };
    
    public static final int[] LEVEL_COLORS = new int[] {
        /* VERBOSE  */ Color.WHITE,
        /* DEBUG    */ Color.rgb(0x40, 0x80, 0xFF),
        /* INFO     */ Color.GREEN,
        /* WARNING  */ Color.YELLOW,
        /* ERROR    */ Color.RED,
        /* FATAL    */ Color.RED,
        /* SILENT   */ Color.BLACK
    };
    
    public static final int[] LEVEL_DESCRIPTIONS = new int[] {
        R.string.logcat_level_verbose,
        R.string.logcat_level_debug,
        R.string.logcat_level_info,
        R.string.logcat_level_warning,
        R.string.logcat_level_error,
        R.string.logcat_level_fatal,
        R.string.logcat_level_silent
    };

    public static final int INDEX_BUFFER_MAIN   = 0;
    public static final int INDEX_BUFFER_SYSTEM = 1;
    public static final int INDEX_BUFFER_RADIO  = 2;
    public static final int INDEX_BUFFER_EVENTS = 3;
    public static final int INDEX_BUFFER_CRASH  = 4;
    
    public static final int INDEX_BUFFER_FIRST  = INDEX_BUFFER_MAIN;
    public static final int INDEX_BUFFER_LAST   = INDEX_BUFFER_CRASH;    

    public static final char[] BUFFER_CHARACTERS = new char[] { 'M', 'S', 'R', 'E', 'C' };
    
    public static final int[] BUFFER_DESCRIPTIONS = new int[] {
        R.string.logcat_buffer_main,
        R.string.logcat_buffer_system,
        R.string.logcat_buffer_radio,
        R.string.logcat_buffer_events,
        R.string.logcat_buffer_crash
    };
    
    public static final String[] BUFFER_NAMES = new String[] {
        "main",
        "system",
        "radio",
        "events",
        "crash"
    };
    
    public static final int[] FORMAT_DESCRIPTIONS = new int[] {
        R.string.logcat_format_brief,
        R.string.logcat_format_process,
        R.string.logcat_format_tag,
        R.string.logcat_format_thread,
        R.string.logcat_format_time,
        R.string.logcat_format_threadtime
    }; // raw, long, not supported

    public static final String[] FORMAT_NAMES = new String[] {
        "brief",
        "process",
        "tag",
        "thread",
        "time",
        "threadtime",
    }; // raw, long, not supported
    
    private static boolean[] mLevels = new boolean[] { true, true, true, true, true, true, true };
    private static boolean[] mBuffers = new boolean[] { true, true, true, true, true };
    private static String mFormat = "brief";

    private final Shell.Interactive mShell;
    private final OnLineListener mOnLineListener;
    
    private volatile long mLineLast = 0;
    private volatile boolean mLinePassthrough = false;
    private final LinkedList<String> mCache = new LinkedList<String>();
    private final int mCacheSize;
    private volatile boolean mReady = false;
    
    private final String[] mSkip = new String[] { 
            " " + String.valueOf(android.os.Process.myPid()) + ")", 
            "(" + String.valueOf(android.os.Process.myPid()) + ")", 
            "LiveBoot" 
    };
    
    private final ReentrantLock mLock = new ReentrantLock(true);
    
    public Logcat(OnLineListener onLineListener, int cacheSize, String levels, String buffers, String format, Handler handler) {
        boolean haveLevels = true;
        boolean haveBuffers = true;
        
        if (levels != null) {
            haveLevels = false;
            for (int i = INDEX_LEVEL_FIRST; i <= INDEX_LEVEL_LAST; i++) {
                mLevels[i] = levels.contains("" + LEVEL_CHARACTERS[i]);
                haveLevels |= mLevels[i];
            }
        }
        if (buffers != null) {
            haveBuffers = false;
            for (int i = INDEX_BUFFER_FIRST; i <= INDEX_BUFFER_LAST; i++) {
                mBuffers[i] = buffers.contains("" + BUFFER_CHARACTERS[i]);
                haveBuffers |= mBuffers[i];
            }
        }
        boolean formatFound = false;
        if (format != null) {        
            for (int i = 0; i < FORMAT_NAMES.length; i++) {
                if (FORMAT_NAMES[i].equals(format)) {
                    formatFound = true;
                }
            }
        }
        if (!formatFound) {
            format = "brief";
        }
        mFormat = format;
        
        String logcat = "";        
        if (haveLevels && haveBuffers) {
            StringBuilder command = new StringBuilder();
            command.append("logcat");
            command.append(" -v ");
            command.append(format);
            for (int i = INDEX_BUFFER_FIRST; i <= INDEX_BUFFER_LAST; i++) {
                if (mBuffers[i]) {
                    if ((new File(String.format(Locale.ENGLISH, "/dev/log/%s", BUFFER_NAMES[i]))).exists()) {
                        command.append(" -b ");
                        command.append(BUFFER_NAMES[i]);
                    }
                }
            }            
            logcat = command.toString();
        }
        
        final Logcat _this = this;
        mOnLineListener = onLineListener;
        mCacheSize = cacheSize;
        mShell = (new Shell.Builder())
            .setAutoHandler(false)
            .setHandler(handler)
            .useSH()
            .setOnSTDOUTLineListener(new StreamGobbler.OnLineListener() {                
                @Override
                public void onLine(String line) {                    
                    mOnLineListener.onLog(_this, line);
                    if (line.contains("libsuperuser")) return;
                    if (line.contains("SuperSU")) return;
                    if (line.contains("LiveBoot")) return;

                    try {
                        mLock.lock();
                        try {
                            if (!mLinePassthrough) {
                                long now = SystemClock.uptimeMillis();
                                if ((mLineLast > 0) && (now - mLineLast > 16)) {
                                    if (mReady) {
                                        mLinePassthrough = true;
                                        processCache();
                                    }
                                    mLineLast = 1;
                                } else {
                                    mLineLast = now;
                                }
                            }
                        
                            if (mLinePassthrough) {
                                processLine(line);
                            } else {
                                if (mCache.size() >= mCacheSize) mCache.pop();
                                mCache.add(line);
                            }
                        } finally {
                            mLock.unlock();
                        }
                    } catch (Exception e) {
                        Logger.ex(e);
                    }
                }
            })
            .setOnSTDERRLineListener(new StreamGobbler.OnLineListener() {                
                @Override
                public void onLine(String line) {
                    mOnLineListener.onLog(_this, line);
                    Logger.dp("logcat/stderr", "%s", line);
                }
            })
            .addCommand(logcat)
            .open();
    }
    
    private void processCache() {
        for (String s : mCache) {
            processLine(s);
        }        
        mCache.clear();
    }
    
    private void processLine(String line) {
        if (line.length() > 0) {
            int index = -1;
            if (mFormat.equals("time")) {
                int end = line.indexOf('(');
                if (end > -1) {
                    for (int i = INDEX_LEVEL_FIRST; i <= INDEX_LEVEL_LAST; i++) {
                        int pos = line.indexOf(" " + LEVEL_CHARACTERS[i] + "/");
                        if ((pos > -1) && (pos < end)) {
                            index = i;
                            break;
                        }
                    }
                }
            } else if (mFormat.equals("threadtime")) {
                int end = line.indexOf(": ");
                if (end > -1) {
                    for (int i = INDEX_LEVEL_FIRST; i <= INDEX_LEVEL_LAST; i++) {
                        int pos = line.indexOf(" " + LEVEL_CHARACTERS[i] + " ");
                        if ((pos > -1) && (pos < end)) {
                            index = i;
                            break;
                        }
                    }
                }                
            } else {
                char c = line.charAt(0);
                for (int i = INDEX_LEVEL_FIRST; i <= INDEX_LEVEL_LAST; i++) {
                    if (c == LEVEL_CHARACTERS[i]) {
                        index = i;
                        break;
                    }
                }
            }
            if (index > -1) {
                if (mLevels[index]) {
                    boolean ok = true;
                    for (String skip : mSkip) {
                        if (line.contains(skip)) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        mOnLineListener.onLine(this, line, LEVEL_COLORS[index]);
                    }
                }
            }
        }
    }    
    
    public void setReady() {
        mLock.lock();
        try {
            mReady = true;
            processCache();
        } finally {
            mLock.unlock();
        }
    }

    public void destroy() {
        final Shell.Interactive shell = mShell;
        (new Thread(new Runnable() {            
             @Override
             public void run() {
                 shell.kill();
                 shell.close();
             }
        })).start();
    }
}
