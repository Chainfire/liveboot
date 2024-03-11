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
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.libcfsurface.SurfaceHost;
import eu.chainfire.libcfsurface.gl.GLHelper;
import eu.chainfire.libcfsurface.gl.GLPicture;
import eu.chainfire.libcfsurface.gl.GLTextManager;
import eu.chainfire.libcfsurface.gl.GLTextureManager;
import eu.chainfire.librootjavadaemon.RootDaemon;
import eu.chainfire.libsuperuser.Debug;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.Toolbox;
import eu.chainfire.liveboot.BuildConfig;

public class 
    Runner 
extends 
    SurfaceHost 
implements 
    OnLineListener,
    SurfaceHost.IGLRenderCallback
{
    public static void main(String[] args) {
        Logger.setLogTag("LiveBootSurface");
        Logger.setDebugLogging(BuildConfig.DEBUG);
        Debug.setDebug(BuildConfig.DEBUG);
        Debug.setLogTypeEnabled(Debug.LOG_GENERAL | Debug.LOG_COMMAND, true);
        Debug.setLogTypeEnabled(Debug.LOG_OUTPUT, false);
        Debug.setSanityChecksEnabled(false); // don't complain about calls on the main thread

        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Logger.dp("EXCEPTION", "%s", throwable.getClass().getName());
                if (oldHandler != null) {
                    oldHandler.uncaughtException(thread, throwable);
                } else {
                    System.exit(1);
                }
            }
        });
        
        new Runner().run(args);
    }
    
    public static final String LIVEBOOT_ABORT_FILE = "/dev/.liveboot_exit";
    
    private static final int TEST_TIME = 5000;
    private static final int LEAD_TIME = 200;
    private static final int FOLLOW_TIME_SCRIPT = 60000;        

    private boolean mTest = false;
    private int mWidth = 0;
    private int mHeight = 0;
    private int mLines = 80;
    private boolean mWordWrap = false;
    private boolean mTransparent = false;
    private boolean mDark = false;
    private boolean mLogcatColor = true;
    private static final String LOG_NAME = "/cache/liveboot.log";
    private boolean mLogSave = false;
    private OutputStream mLogStream = null;
    private ReentrantLock mLogLock = new ReentrantLock(true);
    private static final String SCRIPT_NAME_SYSTEM = "/system/su.d/0000liveboot.script";
    private static final String SCRIPT_NAME_SU = "/su/su.d/0000liveboot.script";
    private static final String SCRIPT_NAME_SBIN = "/sbin/supersu/su.d/0000liveboot.script";
    //TODO where to put the script for Magisk?
    private String mRunScript = null;
    
    private GLTextureManager mTextureManager = null;
    private GLHelper mHelper = null;
    private volatile GLTextManager mTextManager = null;
        
    private Logcat mLogcat = null;
    private Dmesg mDmesg = null;  
    private Script mScript = null;
    
    private HandlerThread mHandlerThread = null;
    private Handler mHandler = null;
    
    private long mFirstLine = 0;
    private int mLinesPassed = 0;
    
    private volatile long mComplete = 0;
    
    private boolean isBootAnimationRunning() {
        List<String> ps = Shell.SH.run(new String[] {
                "/system/bin/" + Toolbox.command("ps") + " | /system/bin/" + Toolbox.command("grep") + " bootanim"+  " | /system/bin/" + Toolbox.command("grep") + " -v grep",
                "/system/bin/" + Toolbox.command("ps") + " -A | /system/bin/" + Toolbox.command("grep") + " bootanim" + " | /system/bin/" + Toolbox.command("grep") + " -v grep"
        });
        if (ps != null) {
            for (String line : ps) {
                if (line.contains("bootanim")) {
                    return true;
                }
            }
        }
        return SystemProperties.get("init.svc.bootanim", "stopped").equals("running");
    }

    private void killBootAnimation() {
        List<String> ps = Shell.SH.run(new String[] {
                "/system/bin/" + Toolbox.command("ps") + " | /system/bin/" + Toolbox.command("grep") + " bootanim"+  " | /system/bin/" + Toolbox.command("grep") + " -v grep",
                "/system/bin/" + Toolbox.command("ps") + " -A | /system/bin/" + Toolbox.command("grep") + " bootanim" + " | /system/bin/" + Toolbox.command("grep") + " -v grep"
        });
        if (ps != null) {
            for (String line : ps) {
                String[] parts = line.split(" +");
                if (parts.length >= 2) {
                    Shell.run("sh", new String[] { "/system/bin/" + Toolbox.command("kill") + " -9 " + parts[1] }, null, false);
                }
            }
        }        
    }
    
    private void infanticide() { // children
        String pid = String.valueOf(android.os.Process.myPid());
        List<String> ps = Shell.SH.run(new String[] {
                "/system/bin/" + Toolbox.command("ps") + " | /system/bin/" + Toolbox.command("grep") + " " + pid + " | /system/bin/" + Toolbox.command("grep") + " -v grep",
                "/system/bin/" + Toolbox.command("ps") + " -A | /system/bin/" + Toolbox.command("grep") + " " + pid + " | /system/bin/" + Toolbox.command("grep") + " -v grep"
        });
        if (ps != null) {
            for (String line : ps) {
                String[] parts = line.split(" +");
                if (parts.length >= 3) {
                    if (!parts[1].equals(pid) && parts[2].equals(pid)) { 
                        Shell.run("sh", new String[] { "/system/bin/" + Toolbox.command("kill") + " -9 " + parts[1] }, null, false);
                    }
                }
            }
        }                
    }
    
    private void suicide() { // self
        Shell.SH.run(new String[] { "/system/bin/" + Toolbox.command("kill") + " -9 " + String.valueOf(android.os.Process.myPid()) });
    }
    
    @Override
    protected void onSize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    @Override
    protected void onResize(int width, int height) {
        mWidth = width;
        mHeight = height;
        mTextManager.resize(-1, -1, width, height, -1, mHeight / mLines);
        mHelper.resize(width, height);
    }

    @Override
    protected void onInit(String[] args) {
        RootDaemon.daemonize(BuildConfig.APPLICATION_ID, 0, false, null);

        Toolbox.init();

        // parse options
        String logcatLevelOpts = null;
        String logcatBufferOpts = null;
        String logcatFormatOpt = null;
        String dmesgOpts = null;
                
        for (String arg : args) {
            try {
                if (arg.equals("test")) {
                    mTest = true;
                    Logger.dp("OPTS", "test==1");
                } else if (arg.equals("transparent")) {
                    mTransparent = true;
                    Logger.dp("OPTS", "transparent==1");
                } else if (arg.equals("wordwrap")) {
                    mWordWrap = true;
                    Logger.dp("OPTS", "wordwrap==1");
                } else if (arg.equals("save")) {
                    mLogSave = true;
                    Logger.dp("OPTS", "save==1");
                } else if (arg.equals("dark")) {
                    mDark = true;
                    Logger.dp("OPTS", "dark==1");
                } else if (arg.equals("logcatnocolors")) {
                    mLogcatColor = false;
                    Logger.dp("OPTS", "logcatnocolors==1");
                } else if (arg.contains("=")) {
                    String key = arg.substring(0, arg.indexOf('='));
                    String value = arg.substring(arg.indexOf('=') + 1);

                    if (key.equals("fallbackwidth")) {
                        fallbackWidth = Integer.valueOf(value, 10);
                        Logger.dp("OPTS", "fallbackWidth==%d", fallbackWidth);
                    } else if (key.equals("fallbackheight")) {
                        fallbackHeight = Integer.valueOf(value, 10);
                        Logger.dp("OPTS", "fallbackHeight==%d", fallbackHeight);
                    } else if (key.equals("lines")) {
                        mLines = Integer.valueOf(value, 10);
                        Logger.dp("OPTS", "mLines==%s", mLines);
                    } else if (key.equals("logcatlevels")) {
                        logcatLevelOpts = value;
                        Logger.dp("OPTS", "logcatLevelOpts==%s", logcatLevelOpts);
                    } else if (key.equals("logcatbuffers")) {
                        logcatBufferOpts = value;
                        Logger.dp("OPTS", "logcatBufferOpts==%s", logcatBufferOpts);
                    } else if (key.equals("logcatformat")) {
                        logcatFormatOpt = value;
                        Logger.dp("OPTS", "logcatFormatOpt==%s", logcatFormatOpt);                    
                    } else if (key.equals("dmesg")) {
                        dmesgOpts = value;
                        Logger.dp("OPTS", "dmesgOpts==%s", dmesgOpts);
                    }
                }
            } catch (Exception e) {
                Logger.ex(e);
            }
        }

        if ((new File(SCRIPT_NAME_SBIN)).exists()) {
            mRunScript = SCRIPT_NAME_SBIN;
        } else if ((new File(SCRIPT_NAME_SU)).exists()) {
            mRunScript = SCRIPT_NAME_SU;
        } else if ((new File(SCRIPT_NAME_SYSTEM)).exists()) {
            mRunScript = SCRIPT_NAME_SYSTEM;
        } //TODO Magisk, KernelSU

        mHandlerThread = new HandlerThread("LiveBoot HandlerThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        
        if (mLogSave) {
            try {
                mLogStream = new FileOutputStream(LOG_NAME, false);
            } catch (Exception e) {                
            }
        }
        
        // start logcat and dmesg
        if (mRunScript == null) {
            mLogcat = new Logcat(this, mLines * 4, logcatLevelOpts, logcatBufferOpts, logcatFormatOpt, mHandler);
            mDmesg = new Dmesg(this, mLines * 4, dmesgOpts, mHandler);
        }
    }

    @Override
    protected void onDone() {
        mHandlerThread.quit();
        if (mLogcat != null) mLogcat.destroy();
        if (mDmesg != null) mDmesg.destroy();
        if (mScript != null) mScript.destroy();
    }
    
    @Override
    protected void onInitRender() {
        mTextureManager = new GLTextureManager();
        mHelper = new GLHelper(mWidth, mHeight, GLHelper.getDefaultVMatrix());
        mTextManager = new GLTextManager(mTextureManager, mHelper, mWidth, mHeight, mHeight / mLines);

        GLPicture.initGl();            
                
        // ready to receive lines
        if (mRunScript == null) {
            if (mDmesg != null) mDmesg.setReady();
            if (mLogcat != null) mLogcat.setReady();
        } else {        
            mScript = new Script(this, mRunScript);
        }
    }
    
    @Override
    public void onGLRenderFrame() {
        GLES20.glDisable(GLES20.GL_BLEND);
        float alpha = 1.0f;
        if (mComplete > 0) {
            alpha -= ((float)(SystemClock.elapsedRealtime() - mComplete) / (float)LEAD_TIME);
        }
        if (!mTransparent) {
            float color = (mDark ? 0.0f : 0.2f * alpha);
            GLES20.glClearColor(color, color, color, alpha);
        } else {
            float color = (mDark ? 0.75f : 0.25f) * alpha;
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, color);
        }        
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);        
        GLES20.glEnable(GLES20.GL_BLEND);
        
        mTextManager.draw();
    }    

    @Override
    protected void onDoneRender() {
        mTextManager.destroy();
        mTextManager = null;
        mTextureManager.destroy();
        mTextureManager = null;
    }

    @Override
    public void onLine(Object sender, String text, int color) {
        final String t = text;
        final int c = color;
        final Object s = sender;
        mHandler.post(new Runnable() {           
            @Override
            public void run() {
                if (mTextManager != null) {
                    long wait = 0L;
                    if (mComplete == 0) {
                        if (mFirstLine == 0) mFirstLine = SystemClock.elapsedRealtime();
                        wait = mFirstLine;
                    } else {
                        wait = mComplete;
                    }
                    mLinesPassed++;
                    while (SystemClock.elapsedRealtime() - wait < Math.min(LEAD_TIME, (int)((float)LEAD_TIME * ((float)mLinesPassed / (float)mLines)))) {
                        try { 
                            Thread.sleep(1); 
                        } catch (Exception e) {                        
                        }
                    }                
                    if (mComplete == 0) {
                        int color = c;
                        if ((s == mLogcat) && (!mLogcatColor)) color = Color.WHITE;
                        mTextManager.add(t, color, mWordWrap);
                    } else {
                        mTextManager.add("", Color.WHITE, mWordWrap);
                    }
                }
            }
        });
    }
    
    @Override
    public void onLog(Object sender, String text) {
        if (mLogSave) {
            mLogLock.lock();            
            try {
                if (mLogStream != null) {
                    try {
                        mLogStream.write((text + "\n").getBytes());
                    } catch (Exception e) {                        
                    }
                }
            } finally {
                mLogLock.unlock();
            }
        }
    }            
    
    @Override
    protected void onMainLoop() {
        if (mTest) {
            try { 
                Thread.sleep(TEST_TIME); 
            } catch (Exception e) {                 
            }            
        } else {
            long start = SystemClock.elapsedRealtime();
            boolean bootAnimationSeen = false;
            boolean bootAnimationGone = false;
            boolean bootAnimationKilled = false;
            long complete = 0;
            while (true) {
                // do not check sys.boot_completed or dev.bootcomplete, as these are already set to 1 before entering decryption password
                long now = SystemClock.elapsedRealtime();
                if ((complete == 0) && SystemProperties.get("service.bootanim.exit", "0").equals("1")) {
                    // sign from Android that we should quit
                    Logger.d("service.bootanim.exit");
                    complete = now;
                }
                if ((complete == 0) && SystemProperties.get("service.bootanim.completed", "0").equals("1")) {
                    // sign from Android that we should quit
                    Logger.d("service.bootanim.completed");
                    complete = now;
                }
                if ((complete == 0) && !bootAnimationSeen && (now - start > 1500)) {
                    // register if we ever saw the bootanimation
                    if (isBootAnimationRunning()) {
                        Logger.d("bootAnimationSeen");
                        bootAnimationSeen = true;
                    }
                }
                if (bootAnimationSeen && !bootAnimationGone && (now - start > 2500)) {
                    // if we saw the bootanimation before and its gone now, note that
                    if (!isBootAnimationRunning()) {
                        Logger.d("bootAnimationGone");
                        bootAnimationGone = true;
                    }
                }
                if ((complete > 0) && bootAnimationSeen && !bootAnimationGone && !bootAnimationKilled) {
                    // if we have an exit sign from Android and bootanimation is still running, kill it
                    Logger.d("bootAnimationkill");
                    killBootAnimation();
                    bootAnimationKilled = true;
                    if (!isBootAnimationRunning()) {
                        Logger.d("bootAnimationkill/Gone");
                        bootAnimationGone = true;
                    }
                }
                if ((complete == 0) && (new File(LIVEBOOT_ABORT_FILE)).exists()) {
                    Logger.d("bootCompleteAbortFromAPK");
                    complete = now;
                    bootAnimationSeen = true;
                    bootAnimationKilled = true;
                    bootAnimationGone = true;                    
                }
                
                if (
                        // Android has signaled to quit, and we haven't seen bootanimation
                        ((complete > 0) && !bootAnimationSeen) ||
                        
                        // bootanimation has come and gone
                        (bootAnimationSeen && bootAnimationGone) ||
                        
                        // Android has signaled to quit, we've seen the bootanimation, but it's still there after 2.5 seconds
                        (bootAnimationSeen && (complete > 0) && (now - complete > 2500))
                ) {
                    Logger.dp("EXIT", "exit sequence");
                    if ((mRunScript != null) && !mTest) {
                        try { 
                            Thread.sleep(FOLLOW_TIME_SCRIPT); 
                        } catch (Exception e) {                            
                        }
                    }
                    break;
                }
                try { 
                    Thread.sleep(64); 
                } catch (Exception e) {                 
                }
            }
            Logger.d("Runtime: %dms", SystemClock.elapsedRealtime() - start);
        }
        mComplete = SystemClock.elapsedRealtime();
        mLinesPassed = 0;
        for (int i = 0; i < (mLines * 5) / 4; i++) {
            onLine(null, "", Color.WHITE);
        }
        try { 
            Thread.sleep(LEAD_TIME); 
        } catch (Exception e) {                            
        }        
        if (mLogSave) {
            mLogLock.lock();
            try {
                try {
                    mLogStream.close();
                } catch (Exception e) {                    
                }                
                mLogStream = null;
            } finally {
                mLogLock.unlock();
            }
        }
        killBootAnimation();
        infanticide();
        suicide();
    }
}
