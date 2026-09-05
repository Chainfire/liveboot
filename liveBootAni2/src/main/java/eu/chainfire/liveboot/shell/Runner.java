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
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static volatile Runner sCurrentRunner = null;

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
                if (sCurrentRunner != null) {
                    sCurrentRunner.diagException("EXCEPTION", throwable);
                }
                if (oldHandler != null) {
                    oldHandler.uncaughtException(thread, throwable);
                } else {
                    System.exit(1);
                }
            }
        });
        
        Runner runner = new Runner();
        sCurrentRunner = runner;
        runner.run(args);
    }
    
    public static final String LIVEBOOT_ABORT_FILE = "/dev/.liveboot_exit";
    
    private static final int TEST_TIME = 5000;
    private static final int LEAD_TIME = 200;
    private static final int FOLLOW_TIME_SCRIPT = 60000;
    private static final int FALLBACK_BOOT_SIGNAL_WAIT = 10000;
    private static final int FAILSAFE_MAX_RUNTIME = 240000;
    private static final int BOOT_STATE_LOG_INTERVAL = 2000;
    private static final int MAX_SUICIDE_DELAY_MS = 60000;
    private static final String[] BOOT_ANIMATION_PROCESS_HINTS = new String[] { "bootanim", "bootanimation" };
    private static final String[] BOOT_ANIMATION_SERVICE_HINTS = new String[] { "bootanim", "bootanimation" };
    private static final String[] DIAG_LOG_NAMES = new String[] { "/cache/liveboot.diag.log", "/data/local/tmp/liveboot.diag.log" };

    private boolean mTest = false;
    private int mWidth = 0;
    private int mHeight = 0;
    private int mLines = 80;
    private int mSuicideDelayMs = 0;
    private boolean mWordWrap = false;
    private boolean mTransparent = false;
    private boolean mDark = false;
    private boolean mLogcatColor = true;
    private static final String LOG_NAME = "/cache/liveboot.log";
    private boolean mLogSave = false;
    private OutputStream mLogStream = null;
    private ReentrantLock mLogLock = new ReentrantLock(true);
    private String mDiagLogName = null;
    private OutputStream mDiagStream = null;
    private ReentrantLock mDiagLock = new ReentrantLock(true);
    private PrintStream mDiagPrintStream = null;
    private PrintStream mOldErr = null;
    private PrintStream mOldOut = null;
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
    private int mDroppedLines = 0;
    private boolean mLoggedFirstLogcatLine = false;
    private boolean mLoggedFirstDmesgLine = false;
    private boolean mLoggedFirstScriptLine = false;
    private long mLastBootStateLog = -BOOT_STATE_LOG_INTERVAL;
    private String mLastBootStateSummary = null;
    
    private volatile long mComplete = 0;

    private void openDiagLog() {
        for (String fileName : DIAG_LOG_NAMES) {
            try {
                mDiagStream = new FileOutputStream(fileName, false);
                mDiagLogName = fileName;
                break;
            } catch (Exception e) {
            }
        }
    }

    private void redirectStandardStreams() {
        if (mDiagLogName == null) return;
        try {
            mDiagPrintStream = new PrintStream(new FileOutputStream(mDiagLogName, true), true);
            mOldErr = System.err;
            mOldOut = System.out;
            System.setErr(mDiagPrintStream);
            System.setOut(mDiagPrintStream);
        } catch (Exception e) {
        }
    }

    private void restoreStandardStreams() {
        try {
            if (mOldErr != null) {
                System.setErr(mOldErr);
            }
            if (mOldOut != null) {
                System.setOut(mOldOut);
            }
        } catch (Exception e) {
        }
        try {
            if (mDiagPrintStream != null) {
                mDiagPrintStream.close();
            }
        } catch (Exception e) {
        }
        mDiagPrintStream = null;
        mOldErr = null;
        mOldOut = null;
    }

    private void closeDiagLog() {
        restoreStandardStreams();
        mDiagLock.lock();
        try {
            if (mDiagStream != null) {
                try {
                    mDiagStream.close();
                } catch (Exception e) {
                }
                mDiagStream = null;
            }
        } finally {
            mDiagLock.unlock();
        }
    }

    private void diag(String tag, String format, Object... params) {
        OutputStream diagStream = mDiagStream;
        if (diagStream == null) return;

        String message;
        try {
            message = (params == null || params.length == 0) ? format : String.format(Locale.ENGLISH, format, params);
        } catch (Exception e) {
            message = format;
        }
        String line = String.format(Locale.ENGLISH, "[%8d][%s] %s\n", SystemClock.elapsedRealtime(), tag, message);

        mDiagLock.lock();
        try {
            if (mDiagStream != null) {
                mDiagStream.write(line.getBytes());
                mDiagStream.flush();
            }
        } catch (Exception e) {
        } finally {
            mDiagLock.unlock();
        }
    }

    private void diagException(String tag, Throwable throwable) {
        if (throwable == null) return;
        diag(tag, "%s: %s", throwable.getClass().getName(), String.valueOf(throwable.getMessage()));
    }

    private String shorten(String text) {
        if (text == null) return "";
        text = text.replace('\n', ' ').replace('\r', ' ');
        if (text.length() > 160) {
            return text.substring(0, 160);
        }
        return text;
    }

    private String getSenderName(Object sender) {
        if (sender == mLogcat) return "logcat";
        if (sender == mDmesg) return "dmesg";
        if (sender == mScript) return "script";
        if (sender == null) return "null";
        return sender.getClass().getSimpleName();
    }

    private void maybeLogFirstLine(Object sender, String text) {
        if ((sender == mLogcat) && !mLoggedFirstLogcatLine) {
            mLoggedFirstLogcatLine = true;
            diag("LINES", "first logcat line: %s", shorten(text));
        } else if ((sender == mDmesg) && !mLoggedFirstDmesgLine) {
            mLoggedFirstDmesgLine = true;
            diag("LINES", "first dmesg line: %s", shorten(text));
        } else if ((sender == mScript) && !mLoggedFirstScriptLine) {
            mLoggedFirstScriptLine = true;
            diag("LINES", "first script line: %s", shorten(text));
        }
    }

    private String getInterestingProperties() {
        return String.format(
                Locale.ENGLISH,
                "init.svc.bootanim=%s init.svc.bootanimation=%s service.bootanim.exit=%s service.bootanim.completed=%s sys.boot_completed=%s dev.bootcomplete=%s persist.sys.multi_display_type=%s",
                SystemProperties.get("init.svc.bootanim", "stopped"),
                SystemProperties.get("init.svc.bootanimation", "stopped"),
                SystemProperties.get("service.bootanim.exit", "0"),
                SystemProperties.get("service.bootanim.completed", "0"),
                SystemProperties.get("sys.boot_completed", "0"),
                SystemProperties.get("dev.bootcomplete", "0"),
                SystemProperties.get("persist.sys.multi_display_type", "")
        );
    }

    private void maybeLogBootState(long elapsed, boolean bootAnimationSeen, boolean bootAnimationGone, boolean bootAnimationRunning, List<Integer> pids, long complete, String completeSignal) {
        String summary = String.format(
                Locale.ENGLISH,
                "elapsed=%d running=%s seen=%s gone=%s complete=%s signal=%s pids=%s props={%s}",
                elapsed,
                String.valueOf(bootAnimationRunning),
                String.valueOf(bootAnimationSeen),
                String.valueOf(bootAnimationGone),
                String.valueOf(complete > 0),
                (completeSignal == null ? "-" : completeSignal),
                pids.toString(),
                getInterestingProperties()
        );
        if (!summary.equals(mLastBootStateSummary) || (elapsed - mLastBootStateLog >= BOOT_STATE_LOG_INTERVAL)) {
            diag("BOOT", summary);
            mLastBootStateSummary = summary;
            mLastBootStateLog = elapsed;
        }
    }

    private boolean isPropertyOne(String key) {
        return SystemProperties.get(key, "0").equals("1");
    }

    private String getBootCompletionSignal(boolean bootAnimationSeen, boolean bootAnimationRunning, long elapsed) {
        if (isPropertyOne("service.bootanim.exit")) {
            return "service.bootanim.exit";
        }
        if (isPropertyOne("service.bootanim.completed")) {
            return "service.bootanim.completed";
        }

        // Some ROMs no longer expose service.bootanim.* during boot.
        if (
                (elapsed >= FALLBACK_BOOT_SIGNAL_WAIT) &&
                (bootAnimationSeen || !bootAnimationRunning)
        ) {
            if (isPropertyOne("sys.boot_completed")) {
                return "sys.boot_completed";
            }
            if (isPropertyOne("dev.bootcomplete")) {
                return "dev.bootcomplete";
            }
        }
        return null;
    }

    private List<String> getProcessList() {
        return Shell.SH.run(new String[] {
                "/system/bin/" + Toolbox.command("ps"),
                "/system/bin/" + Toolbox.command("ps") + " -A"
        });
    }

    private Integer getPidFromPsLine(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split(" +");
        for (String part : parts) {
            try {
                int pid = Integer.valueOf(part, 10);
                if (pid > 1) {
                    return pid;
                }
            } catch (Exception e) {
            }
        }
        return null;
    }

    private boolean isBootAnimationProcessLine(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase(Locale.ENGLISH);
        for (String hint : BOOT_ANIMATION_PROCESS_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> getBootAnimationPids() {
        List<Integer> ret = new ArrayList<Integer>();
        Set<Integer> seen = new HashSet<Integer>();
        List<String> ps = getProcessList();
        if (ps != null) {
            for (String line : ps) {
                if (isBootAnimationProcessLine(line)) {
                    Integer pid = getPidFromPsLine(line);
                    if ((pid != null) && !seen.contains(pid)) {
                        seen.add(pid);
                        ret.add(pid);
                    }
                }
            }
        }
        return ret;
    }

    private boolean isBootAnimationServiceRunning() {
        for (String service : BOOT_ANIMATION_SERVICE_HINTS) {
            if (SystemProperties.get("init.svc." + service, "stopped").equals("running")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBootAnimationRunning() {
        return isBootAnimationRunning(getBootAnimationPids());
    }

    private boolean isBootAnimationRunning(List<Integer> pids) {
        return !pids.isEmpty() || isBootAnimationServiceRunning();
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
        diag("SURFACE", "size=%dx%d", width, height);
    }

    @Override
    protected void onResize(int width, int height) {
        mWidth = width;
        mHeight = height;
        diag("SURFACE", "resize=%dx%d", width, height);
        mTextManager.resize(-1, -1, width, height, -1, mHeight / mLines);
        mHelper.resize(width, height);
    }

    @Override
    protected void onInit(String[] args) {
        RootDaemon.daemonize(BuildConfig.APPLICATION_ID, 0, false, null);

        Toolbox.init();
        openDiagLog();
        redirectStandardStreams();
        diag("INIT", "pid=%d args=%s", android.os.Process.myPid(), Arrays.toString(args));
        diag("INIT", "properties={%s}", getInterestingProperties());
        diag("INIT", "diag log=%s stderr_redirect=%s", String.valueOf(mDiagLogName), String.valueOf(mDiagPrintStream != null));

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
                    } else if (key.equals("suicidedelay")) {
                        mSuicideDelayMs = Math.max(0, Math.min(MAX_SUICIDE_DELAY_MS, Integer.valueOf(value, 10)));
                        Logger.dp("OPTS", "mSuicideDelayMs==%d", mSuicideDelayMs);
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
                diagException("INIT", e);
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
        diag(
                "INIT",
                "runScript=%s test=%s transparent=%s dark=%s wordwrap=%s save=%s lines=%d suicideDelayMs=%d fallback=%dx%d logcatLevels=%s logcatBuffers=%s logcatFormat=%s dmesg=%s",
                (mRunScript == null ? "<direct>" : mRunScript),
                String.valueOf(mTest),
                String.valueOf(mTransparent),
                String.valueOf(mDark),
                String.valueOf(mWordWrap),
                String.valueOf(mLogSave),
                mLines,
                mSuicideDelayMs,
                fallbackWidth,
                fallbackHeight,
                String.valueOf(logcatLevelOpts),
                String.valueOf(logcatBufferOpts),
                String.valueOf(logcatFormatOpt),
                String.valueOf(dmesgOpts)
        );
        
        if (mLogSave) {
            try {
                mLogStream = new FileOutputStream(LOG_NAME, false);
                diag("INIT", "log stream opened at %s", LOG_NAME);
            } catch (Exception e) {                
                diagException("INIT", e);
            }
        }
        
        // start logcat and dmesg
        if (mRunScript == null) {
            mLogcat = new Logcat(this, mLines * 4, logcatLevelOpts, logcatBufferOpts, logcatFormatOpt, mHandler);
            mDmesg = new Dmesg(this, mLines * 4, dmesgOpts, mHandler);
            diag("INIT", "started direct log sources");
        } else {
            diag("INIT", "waiting for script mode source");
        }
    }

    @Override
    protected void onDone() {
        diag("DONE", "onDone start");
        mHandlerThread.quit();
        if (mLogcat != null) mLogcat.destroy();
        if (mDmesg != null) mDmesg.destroy();
        if (mScript != null) mScript.destroy();
        diag("DONE", "onDone complete");
        closeDiagLog();
    }
    
    @Override
    protected void onInitRender() {
        diag("SURFACE", "initRender width=%d height=%d runScript=%s", mWidth, mHeight, (mRunScript == null ? "<direct>" : mRunScript));
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
            diag("SURFACE", "script renderer started for %s", mRunScript);
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
        diag("SURFACE", "doneRender");
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
                maybeLogFirstLine(s, t);
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
                } else {
                    mDroppedLines++;
                    if ((mDroppedLines <= 5) || ((mDroppedLines % 100) == 0)) {
                        diag("LINES", "drop count=%d sender=%s text=%s", mDroppedLines, getSenderName(s), shorten(t));
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
            diag("BOOT", "test mode sleep=%d", TEST_TIME);
            try { 
                Thread.sleep(TEST_TIME); 
            } catch (Exception e) {                 
                diagException("BOOT", e);
            }            
        } else {
            long start = SystemClock.elapsedRealtime();
            boolean bootAnimationSeen = false;
            boolean bootAnimationGone = false;
            long complete = 0;
            diag("BOOT", "main loop start");
            while (true) {
                long now = SystemClock.elapsedRealtime();
                long elapsed = now - start;
                List<Integer> bootAnimationPids = getBootAnimationPids();
                boolean bootAnimationRunning = isBootAnimationRunning(bootAnimationPids);
                String completeSignal = getBootCompletionSignal(bootAnimationSeen, bootAnimationRunning, elapsed);
                maybeLogBootState(elapsed, bootAnimationSeen, bootAnimationGone, bootAnimationRunning, bootAnimationPids, complete, completeSignal);
                if ((complete == 0) && (completeSignal != null)) {
                    Logger.d(completeSignal);
                    diag("BOOT", "complete signal=%s", completeSignal);
                    complete = now;
                }
                if ((complete == 0) && !bootAnimationSeen && (elapsed > 1500)) {
                    // register if we ever saw the bootanimation
                    if (bootAnimationRunning) {
                        Logger.d("bootAnimationSeen");
                        diag("BOOT", "bootAnimationSeen pids=%s", bootAnimationPids.toString());
                        bootAnimationSeen = true;
                    }
                }
                if (bootAnimationSeen && !bootAnimationGone && (elapsed > 2500)) {
                    // if we saw the bootanimation before and its gone now, note that
                    if (!bootAnimationRunning) {
                        Logger.d("bootAnimationGone");
                        diag("BOOT", "bootAnimationGone");
                        bootAnimationGone = true;
                    }
                }
                if ((complete == 0) && (new File(LIVEBOOT_ABORT_FILE)).exists()) {
                    Logger.d("bootCompleteAbortFromAPK");
                    diag("BOOT", "abort file detected: %s", LIVEBOOT_ABORT_FILE);
                    complete = now;
                    bootAnimationSeen = true;
                    bootAnimationGone = true;                    
                }
                if ((complete == 0) && (elapsed > FAILSAFE_MAX_RUNTIME)) {
                    Logger.d("bootAnimationTimeout");
                    diag("BOOT", "timeout after %d ms", elapsed);
                    complete = now;
                    bootAnimationSeen = true;
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
                    diag("BOOT", "exit sequence entered");
                    if ((mRunScript != null) && !mTest) {
                        try { 
                            Thread.sleep(FOLLOW_TIME_SCRIPT); 
                        } catch (Exception e) {                            
                            diagException("BOOT", e);
                        }
                    }
                    if (mSuicideDelayMs > 0) {
                        diag("BOOT", "suicide delay sleep=%d", mSuicideDelayMs);
                        try {
                            Thread.sleep(mSuicideDelayMs);
                        } catch (Exception e) {
                            diagException("BOOT", e);
                        }
                    }
                    break;
                }
                try { 
                    Thread.sleep(64); 
                } catch (Exception e) {                 
                    diagException("BOOT", e);
                }
            }
            Logger.d("Runtime: %dms", SystemClock.elapsedRealtime() - start);
            diag("BOOT", "runtime=%d", SystemClock.elapsedRealtime() - start);
        }
        mComplete = SystemClock.elapsedRealtime();
        mLinesPassed = 0;
        for (int i = 0; i < (mLines * 5) / 4; i++) {
            onLine(null, "", Color.WHITE);
        }
        try { 
            Thread.sleep(LEAD_TIME); 
        } catch (Exception e) {                            
            diagException("BOOT", e);
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
        diag("DONE", "final cleanup start");
        infanticide();
        suicide();
    }
}
