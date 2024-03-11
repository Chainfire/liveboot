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

package eu.chainfire.liveboot.shell;

import android.graphics.Color;
import android.os.Handler;
import android.os.SystemClock;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.StreamGobbler;

import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

public class Dmesg {
    private static final int COLOR = Color.WHITE;

    private volatile int mShowMin = 0;
    private volatile int mShowMax = 99;
    
    private final Shell.Interactive mShell;
    private final OnLineListener mOnLineListener;
    
    private volatile long mLineLast = 0;
    private volatile boolean mLinePassthrough = false;
    private final LinkedList<String> mCache = new LinkedList<String>();
    private final int mCacheSize;
    private volatile boolean mReady = false;
        
    private final ReentrantLock mLock = new ReentrantLock(true);

    public Dmesg(OnLineListener onLineListener, int cacheSize, String show, Handler handler) {
        if (show != null) {
            int p = show.indexOf('-');
            if (p > -1) {
                try {
                    mShowMin = Integer.valueOf(show.substring(0, p), 10);
                    mShowMax = Integer.valueOf(show.substring(p + 1), 10);
                } catch (Exception e) {
                    mShowMin = 0;
                    mShowMax = 99;
                    Logger.ex(e);
                }
            }
        }
        
        final Dmesg _this = this;
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
                    Logger.dp("dmesg/stderr", "%s", line);
                }
            })
            .addCommand("cat /dev/kmsg")
            .addCommand("cat /proc/kmsg")
            .open();
    }

    private synchronized void processCache() {
        for (String s : mCache) {
            processLine(s);
        }        
        mCache.clear();
    }
    
    private void processLine(String line) {
        if (line.length() > 0) {
            String processed = null;
            
            if (line.startsWith("<")) {
                // /proc/kmsg
                int p = line.indexOf('>');
                if (p > -1) {
                    int level = -1;
                    try {
                        level = Integer.valueOf(line.substring(1, p), 10);
                    } catch (Exception e) {                        
                    }
                    if ((level >= mShowMin) && (level <= mShowMax)) {
                        processed = line;
                    }
                }
            } else {
                // /dev/kmsg
                int p = line.indexOf(';');
                if (p > -1) {
                    String content = line.substring(p + 1);
                    String[] flags = line.split(",");
                    if ((flags != null) && (flags.length >= 3)) {
                        try {
                            int level = Integer.valueOf(flags[0], 10);
                            if ((level >= mShowMin) && (level <= mShowMax)) {
                                String time = flags[2];
                                String time1 = time.substring(0, time.length() - 6);
                                String time2 = time.substring(time.length() - 6);
                                processed = String.format(Locale.ENGLISH, "<%d>[%s.%6s] %s", level, time1, time2, content);
                            }
                        } catch (NumberFormatException e) {                                        
                        }
                    }                                
                }
            }
            
            if (processed != null) {
                mOnLineListener.onLine(this, processed, COLOR);
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
