/* Copyright 2020 Jorrit 'Chainfire' Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.chainfire.libcfsurface;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.librootjava.RootJava;

public abstract class SurfaceHost {
    public static String getLaunchString(Context context, Class<?> clazz, String app_process, String niceName) {
        return RootJava.getLaunchString(context, clazz, app_process, new String[] { context.getPackageCodePath() }, niceName);
    }

    private volatile boolean mShow = true;
    private volatile boolean mIsVisible = false;
    private volatile int mWidth = 0;
    private volatile int mHeight = 0;
    private volatile long mLastFrame = 0;
    private volatile String mAPK = null;
    private volatile Context mContext = null;
    private volatile DisplayManager mDisplayManager = null;
    private volatile int mLastRotation = Surface.ROTATION_0;

    private Object mSurfaceSession = null;
    private Class<?> cSurfaceControl = null;
    private Object mSurfaceControl = null;
    private Method mSurfaceControlOpenTransaction = null;
    private Method mSurfaceControlCloseTransaction = null;
    private Method mSurfaceControlSetLayer = null;
    private Method mSurfaceControlShow = null;
    private Method mSurfaceControlHide = null;
    private Method mSurfaceControlSetSize = null;
    private Surface mSurface = null;
    Method mSurfaceControlGetGlobalTransaction = null;
    Method mTransactionShow;
    Method mTransactionHide;
    Method mTransactionSetLayer;
    Method mTransactionSetBufferSize;

    private final boolean checkRotation() {
        // This is fairly weird construct only because we need to handle the case of (for example)
        // LiveBoot running while Android really isn't started up yet. It would normally be much
        // easier to use WindowManager::getDefaultDisplay(), but that will bring down the process
        // in that specific case.
        try {
            if (mDisplayManager != null) {
                Display[] displays = mDisplayManager.getDisplays();
                if ((displays != null) && (displays.length > 0)) {
                    int rotation = mDisplayManager.getDisplay(0).getRotation();
                    if (rotation != mLastRotation) {
                        boolean neutral = (rotation == Surface.ROTATION_0) || (rotation == Surface.ROTATION_180);
                        boolean lastNeutral = (mLastRotation == Surface.ROTATION_0) || (mLastRotation == Surface.ROTATION_180);
                        mLastRotation = rotation;
                        if (neutral != lastNeutral) {
                            int swap = mWidth;
                            mWidth = mHeight;
                            mHeight = swap;
                            updateSurfaceSize();
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // there will be exceptions during boot-up while DisplayManager isn't ready yet
        }
        return false;
    }

    private final boolean initSurface() {
        // Note that it is often possible just to include a Java file from AOSP in your own
        // project and referring to classes you do not normally have access to that way, and
        // the VM links the method up to the correct real methods implemented in the running
        // Android framework. That becomes a bit trickier as these (hidden) classes change
        // between API versions, the method signatures change, etc. Hence the raw reflection here.
        try {
            // Create SurfaceSession
            Class<?> cSurfaceSession = Class.forName("android.view.SurfaceSession");
            Constructor<?> ctorSurfaceSession = cSurfaceSession.getConstructor();
            ctorSurfaceSession.setAccessible(true);
            mSurfaceSession = ctorSurfaceSession.newInstance();

            // Get display configuration
            cSurfaceControl = Class.forName("android.view.SurfaceControl");

            IBinder mBuiltInDisplay = null;
            try {
                // API 28-
                Method mGetBuiltInDisplay = cSurfaceControl.getDeclaredMethod("getBuiltInDisplay", int.class);
                mBuiltInDisplay = (IBinder)mGetBuiltInDisplay.invoke(null,0 /* SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN */);
            } catch (NoSuchMethodException e) {
            }
            if (mBuiltInDisplay == null) {
                // API 29+
                Method mGetPhysicalDisplayIds = cSurfaceControl.getDeclaredMethod("getPhysicalDisplayIds");
                long[] ids = (long[])mGetPhysicalDisplayIds.invoke(null);
                Method mGetPhysicalDisplayToken = cSurfaceControl.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                mBuiltInDisplay = (IBinder)mGetPhysicalDisplayToken.invoke(null, ids[0]);
            }

            Method mGetDisplayConfigs;
            Object[] displayConfigs;
            if (Build.VERSION.SDK_INT <= 30) {
                // API 30-
                mGetDisplayConfigs = cSurfaceControl.getDeclaredMethod("getDisplayConfigs", IBinder.class);
                displayConfigs = (Object[]) mGetDisplayConfigs.invoke(null, mBuiltInDisplay);
            } else {
                // API 31+
                Method mGetDynamicDisplayInfo = cSurfaceControl.getDeclaredMethod("getDynamicDisplayInfo", IBinder.class);
                Object dynamicDisplayInfo = mGetDynamicDisplayInfo.invoke(null, mBuiltInDisplay);
                Class<?> cDynamicDisplayInfo = Class.forName("android.view.SurfaceControl$DynamicDisplayInfo");
                @SuppressLint("BlockedPrivateApi") Field fSsupportedDisplayModes = cDynamicDisplayInfo.getDeclaredField("supportedDisplayModes");
                displayConfigs = (Object[]) fSsupportedDisplayModes.get(dynamicDisplayInfo);
            }

            Class<?> cPhysicalDisplayInfo = null;
            // API 29-
            try {
                cPhysicalDisplayInfo = Class.forName("android.view.SurfaceControl$PhysicalDisplayInfo");
            } catch (ClassNotFoundException e) {
            }
            // API 30
            if (cPhysicalDisplayInfo == null) {
                try {
                    cPhysicalDisplayInfo = Class.forName("android.view.SurfaceControl$DisplayConfig");
                } catch (ClassNotFoundException e) {
                }
            }
            // API 31+
            if (cPhysicalDisplayInfo == null) {
                try {
                    cPhysicalDisplayInfo = Class.forName("android.view.SurfaceControl$DisplayMode");
                } catch (ClassNotFoundException e) {
                }
            }

            @SuppressLint("BlockedPrivateApi") Field fWidth = cPhysicalDisplayInfo.getDeclaredField("width");
            @SuppressLint("BlockedPrivateApi") Field fHeight = cPhysicalDisplayInfo.getDeclaredField("height");
            if ((displayConfigs == null) || (displayConfigs.length == 0)) {
                throw new RuntimeException("CFSurface: could not determine screen dimensions");
            }
            mWidth = fWidth.getInt(displayConfigs[0]);
            mHeight = fHeight.getInt(displayConfigs[0]);
            checkRotation();

            // Create SurfaceControl
            if (mSurfaceControl == null) {
                // API 30+
                try {
                    Constructor<?> ctorSurfaceControl = cSurfaceControl.getDeclaredConstructor(cSurfaceSession, String.class, int.class, int.class, int.class, int.class, cSurfaceControl, SparseIntArray.class, java.lang.ref.WeakReference.class, String.class);
                    ctorSurfaceControl.setAccessible(true);
                    SparseIntArray sia = new SparseIntArray(0);
                    mSurfaceControl = ctorSurfaceControl.newInstance(mSurfaceSession, "CFSurface", mWidth, mHeight, getPixelFormat(), 0x00000004 /*SurfaceControl.HIDDEN*/, null, sia, null, "CFSurface");
                } catch (NoSuchMethodException e) {
                }
            }
            if (mSurfaceControl == null) {
                // API 29+
                try {
                    Constructor<?> ctorSurfaceControl = cSurfaceControl.getDeclaredConstructor(cSurfaceSession, String.class, int.class, int.class, int.class, int.class, cSurfaceControl, SparseIntArray.class);
                    ctorSurfaceControl.setAccessible(true);
                    SparseIntArray sia = new SparseIntArray(0);
                    mSurfaceControl = ctorSurfaceControl.newInstance(mSurfaceSession, "CFSurface", mWidth, mHeight, getPixelFormat(), 0x00000004 /*SurfaceControl.HIDDEN*/, null, sia);
                } catch (NoSuchMethodException e) {
                }
            }
            if (mSurfaceControl == null) {
                // API 28+
                try {
                    Constructor<?> ctorSurfaceControl = cSurfaceControl.getDeclaredConstructor(cSurfaceSession, String.class, int.class, int.class, int.class, int.class, cSurfaceControl, int.class, int.class);
                    ctorSurfaceControl.setAccessible(true);
                    mSurfaceControl = ctorSurfaceControl.newInstance(mSurfaceSession, "CFSurface", mWidth, mHeight, getPixelFormat(), 0x00000004 /*SurfaceControl.HIDDEN*/, null, 0, 0);
                } catch (NoSuchMethodException e) {
                }
            }
            if (mSurfaceControl == null) {
                // API 28+ #2 - this doesn't appear to be in any sources, but some devices have this (including Samsung S9 on Pie). What does the last boolean mean? We don't know. Either true or false seems to work.
                try {
                    Constructor<?> ctorSurfaceControl = cSurfaceControl.getDeclaredConstructor(cSurfaceSession, String.class, int.class, int.class, int.class, int.class, cSurfaceControl, int.class, int.class, boolean.class);
                    ctorSurfaceControl.setAccessible(true);
                    mSurfaceControl = ctorSurfaceControl.newInstance(mSurfaceSession, "CFSurface", mWidth, mHeight, getPixelFormat(), 0x00000004 /*SurfaceControl.HIDDEN*/, null, 0, 0, true);
                } catch (NoSuchMethodException e) {
                }
            }
            if (mSurfaceControl == null) {
                // API 26+
                try {
                    Constructor<?> ctorSurfaceControl = cSurfaceControl.getDeclaredConstructor(cSurfaceSession, String.class, int.class, int.class, int.class, int.class, int.class, int.class);
                    ctorSurfaceControl.setAccessible(true);
                    mSurfaceControl = ctorSurfaceControl.newInstance(mSurfaceSession, "CFSurface", mWidth, mHeight, getPixelFormat(), 0x00000004 /*SurfaceControl.HIDDEN*/, 0, 0);
                } catch (NoSuchMethodException e) {
                }
            }
            if (mSurfaceControl == null) {
                // API 21+ (actually since 18 or 19, but we don't support that anyway)
                try {
                    Constructor<?> ctorSurfaceControl = cSurfaceControl.getDeclaredConstructor(cSurfaceSession, String.class, int.class, int.class, int.class, int.class);
                    ctorSurfaceControl.setAccessible(true);
                    mSurfaceControl = ctorSurfaceControl.newInstance(mSurfaceSession, "CFSurface", mWidth, mHeight, getPixelFormat(), 0x00000004 /*SurfaceControl.HIDDEN*/);
                } catch (NoSuchMethodException e) {
                }
            }
            if (mSurfaceControl == null) {
                Constructor<?>[] constructors = cSurfaceControl.getDeclaredConstructors();
                int index = 0;
                for (Constructor<?> constructor : constructors) {
                    Class<?>[] params = constructor.getParameterTypes();
                    StringBuilder sb = new StringBuilder();
                    for (Class<?> param : params) {
                        sb.append(param.getName());
                        sb.append(" ");
                    }
                    Logger.d("constructor[%d]: %s", index, sb.toString());
                    index++;
                }
                throw new RuntimeException("CFSurface: could not create SurfaceControl");
            }

            Class<?> cTransaction = null;

            // Get SurfaceControl methods we need later
            mSurfaceControlOpenTransaction = cSurfaceControl.getDeclaredMethod("openTransaction");
            mSurfaceControlCloseTransaction = cSurfaceControl.getDeclaredMethod("closeTransaction");
            if (Build.VERSION.SDK_INT <= 30) {
                mSurfaceControlSetLayer = cSurfaceControl.getDeclaredMethod("setLayer", int.class);
                mSurfaceControlShow = cSurfaceControl.getDeclaredMethod("show");
                mSurfaceControlHide = cSurfaceControl.getDeclaredMethod("hide");
            } else {
                mSurfaceControlGetGlobalTransaction = cSurfaceControl.getDeclaredMethod("getGlobalTransaction");
                cTransaction = Class.forName("android.view.SurfaceControl$Transaction");
                mTransactionSetLayer = cTransaction.getDeclaredMethod("setLayer", cSurfaceControl, int.class);
                mTransactionShow = cTransaction.getDeclaredMethod("show", cSurfaceControl);
                mTransactionHide = cTransaction.getDeclaredMethod("hide", cSurfaceControl);
            }

            try {
                if (Build.VERSION.SDK_INT <= 30) {
                    mSurfaceControlSetSize = cSurfaceControl.getDeclaredMethod("setSize", int.class, int.class);
                } else {
                    mTransactionSetBufferSize = cTransaction.getDeclaredMethod("setBufferSize", cSurfaceControl, int.class, int.class);
                }
            } catch (NoSuchMethodException e) {
                //TODO QP1: this method is messing, check Q source when it becomes available on how to work around
                Logger.e("QP1: Could not retrieve setSize method");
            }

            // Get hidden Surface constructor and copyFrom
            Constructor<?> ctorSurface = Surface.class.getDeclaredConstructor();
            mSurface = (Surface)ctorSurface.newInstance();
            Method mCopyFrom = Surface.class.getDeclaredMethod("copyFrom", cSurfaceControl);
            mCopyFrom.invoke(mSurface, mSurfaceControl);

            // Set top z-index
            mSurfaceControlOpenTransaction.invoke(null);
            if (mSurfaceControlGetGlobalTransaction != null) {
                // API 31+
                synchronized (cSurfaceControl) {
                    mTransactionSetLayer.invoke(mSurfaceControlGetGlobalTransaction.invoke(mSurfaceControl), mSurfaceControl, 0x7FFFFFFF);
                }
            } else {
                // API 30-
                mSurfaceControlSetLayer.invoke(mSurfaceControl, 0x7FFFFFFF);
            }
            mSurfaceControlCloseTransaction.invoke(null);

            if (mSurfaceControlGetGlobalTransaction != null) {
                // API 31+
                Class<?> cTypeface = Class.forName("android.graphics.Typeface");
                @SuppressLint("BlockedPrivateApi") Method mGetDefault = cTypeface.getDeclaredMethod("getDefault");
                mGetDefault.setAccessible(true);

                if (mGetDefault.invoke(null) == null) {
                    @SuppressLint("BlockedPrivateApi") Method mLoadPreinstalledSystemFontMap = cTypeface.getDeclaredMethod("loadPreinstalledSystemFontMap");
                    mLoadPreinstalledSystemFontMap.invoke(null);
                }
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            Logger.ex(e);
            throw new RuntimeException("CFSurface: unexpected exception during SurfaceControl creation");
        }
    }

    private final boolean doneSurface() {
        try {
            mSurface.release();
            cSurfaceControl.getDeclaredMethod("release").invoke(mSurfaceControl);
        } catch (Exception e) {
            Logger.ex(e);
            return false;
        }
        return true;
    }

    private final void updateSurfaceVisibility() {
        if (mShow != mIsVisible) {
            try {
                mSurfaceControlOpenTransaction.invoke(null);
                if (mSurfaceControlGetGlobalTransaction != null) {
                    // API 31+
                    synchronized (cSurfaceControl) {
                        if (mShow) {
                            mTransactionShow.invoke(mSurfaceControlGetGlobalTransaction.invoke(mSurfaceControl), mSurfaceControl);
                        } else {
                            mTransactionHide.invoke(mSurfaceControlGetGlobalTransaction.invoke(mSurfaceControl), mSurfaceControl);
                        }
                    }
                } else {
                    // API 30-
                    if (mShow) {
                        mSurfaceControlShow.invoke(mSurfaceControl);
                    } else {
                        mSurfaceControlHide.invoke(mSurfaceControl);
                    }
                }
                mSurfaceControlCloseTransaction.invoke(null);
            } catch (Exception e) {
                Logger.ex(e);
            }
            mIsVisible = mShow;
        }
    }

    private final void updateSurfaceSize() {
        if (mSurface != null) { // we can be called during initSurface
            try {
                if (mSurfaceControlSetSize == null && mTransactionSetBufferSize == null) { //TODO QP1
                    Logger.e("QP1: setSize == null");
                } else {
                    // Does this nested conditional check have to be nested in this way?
                    mSurfaceControlOpenTransaction.invoke(null);
                    if (mSurfaceControlGetGlobalTransaction != null) {
                        // API 31+
                        synchronized (cSurfaceControl) {
                            mTransactionSetBufferSize.invoke(mSurfaceControlGetGlobalTransaction.invoke(mSurfaceControl), mWidth, mHeight);
                        }
                    } else {
                        // API 30-
                        mSurfaceControlSetSize.invoke(mSurfaceControl, mWidth, mHeight);
                    }
                    mSurfaceControlCloseTransaction.invoke(null);
                }
            } catch (Exception e) {
                Logger.ex(e);
            }
        }
    }

    private final void veryBadFPSLimiter() {
        long now = SystemClock.uptimeMillis();
        long diff = now - mLastFrame;
        if (diff < 17) {
            try {
                Thread.sleep(17 - diff);
            } catch (Exception e) {
            }
        }
        mLastFrame = now;
    }

    private final Thread createGLRenderThread() {
        return new Thread() {
            @Override
            public void run() {
                int[] eglVersion = new int[2];
                final EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (!EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1))
                    throw new RuntimeException("CFSurface: eglInitialize failure");

                EGLConfig[] config = new EGLConfig[1];
                int[] num_config = new int[1];
                if (!EGL14.eglChooseConfig(display, new int[] {
                        EGL14.EGL_RED_SIZE,         8,
                        EGL14.EGL_GREEN_SIZE,       8,
                        EGL14.EGL_BLUE_SIZE,        8,
                        EGL14.EGL_ALPHA_SIZE,       8,
                        EGL14.EGL_DEPTH_SIZE,       0,
                        EGL14.EGL_RENDERABLE_TYPE,  EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE,             EGL14.EGL_NONE
                }, 0, config, 0, 1, num_config, 0))
                    throw new RuntimeException("CFSurface: eglChooseConfig failure");

                EGLSurface surface = EGL14.eglCreateWindowSurface(display, config[0], mSurface, new int[] {
                        EGL14.EGL_NONE,             EGL14.EGL_NONE
                }, 0);
                if (surface == null) throw new RuntimeException("CFSurface: eglCreateWindowSurface failure");

                EGLContext context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT, new int[] {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION,   2,
                        EGL14.EGL_NONE,                     EGL14.EGL_NONE
                }, 0);
                if (context == null) throw new RuntimeException("CFSurface: eglCreateContext failure");

                if (!EGL14.eglMakeCurrent(display, surface, surface, context))
                    throw new RuntimeException("CFSurface: eglCreateContext failure");

                onSize(mWidth, mHeight);
                onInitRender();
                while (!isInterrupted()) {
                    ((IGLRenderCallback)SurfaceHost.this).onGLRenderFrame();
                    EGL14.eglSwapBuffers(display, surface);
                    updateSurfaceVisibility();
                    if (checkRotation()) {
                        GLES20.glViewport(0, 0, mWidth, mHeight);
                        onResize(mWidth, mHeight);
                    }
                    veryBadFPSLimiter();
                }
                onDoneRender();

                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroyContext(display, context);
                EGL14.eglDestroySurface(display, surface);
                EGL14.eglTerminate(display);
            }
        };
    }

    private final Thread createSurfaceRenderThread() {
        return new Thread() {
            @Override
            public void run() {
                onSize(mWidth, mHeight);
                onInitRender();
                while (!isInterrupted()) {
                    if (SurfaceHost.this instanceof ISurfaceRenderCallback) {
                        ((ISurfaceRenderCallback)SurfaceHost.this).onSurfaceRenderFrame(mSurface);
                    } else if (SurfaceHost.this instanceof ICanvasRenderCallback) {
                        Canvas canvas = mSurface.lockCanvas(null);
                        try {
                            ((ICanvasRenderCallback)SurfaceHost.this).onCanvasRenderFrame(canvas);
                        } finally {
                            mSurface.unlockCanvasAndPost(canvas);
                        }
                    } else if (SurfaceHost.this instanceof IHardwareCanvasRenderCallback) {
                        Canvas canvas;
                        if (Build.VERSION.SDK_INT >= 23) {
                            canvas = mSurface.lockHardwareCanvas();
                        } else {
                            canvas = mSurface.lockCanvas(null);
                        }
                        try {
                            ((IHardwareCanvasRenderCallback)SurfaceHost.this).onHardwareCanvasRenderFrame(canvas);
                        } finally {
                            mSurface.unlockCanvasAndPost(canvas);
                        }
                    }
                    updateSurfaceVisibility();
                    if (checkRotation()) onResize(mWidth, mHeight);
                    veryBadFPSLimiter();
                }
                onDoneRender();
            }
        };
    }

    public static final int reservedArgs = 1;
    public final void run(String[] args) {
        if (!(this instanceof IGLRenderCallback) || (this instanceof ICanvasRenderCallback) || (this instanceof IHardwareCanvasRenderCallback) || (this instanceof ISurfaceRenderCallback)) {
            throw new RuntimeException("CFSurface: no render callback implemented");
        }

        //TODO check and wait for SurfaceFlinger connection ??
        // IServiceManager sm = ServiceManager.getIServiceManager();
        // while (sm.checkService("SurfaceFlinger") == null) {
        //     ... wait ...
        // }
        //Doesn't seem to be necessary in Pie ?

        try {
            if ((args == null) || (args.length < reservedArgs))
                return;
        
            mAPK = args[0];
            RootJava.restoreOriginalLdLibraryPath();

            String[] initArgs = new String[args.length - reservedArgs];
            for (int i = 0; i < args.length - reservedArgs; i++) {
                initArgs[i] = args[i + reservedArgs];
            }

            mContext = RootJava.getSystemContext();
            mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);

            onInit(initArgs);
            initSurface();

            Thread renderThread = this instanceof IGLRenderCallback ? createGLRenderThread() : createSurfaceRenderThread();
            renderThread.start();

            onMainLoop();

            renderThread.interrupt();
            renderThread.join();

            doneSurface();
            onDone();
        } catch (Exception e) {
            Logger.ex(e);
        }
        System.exit(0);
    }

    protected final void show() {
        mShow = true;
    }

    protected final void hide() {
        mShow = false;
    }

    protected final boolean isShown() {
        return mShow;
    }

    protected final int getWidth() {
        return mWidth;
    }

    protected final int getHeight() {
        return mHeight;
    }

    protected final String getAPK() {
        return mAPK;
    }

    // ---------------------------------------------------------------------------------------

    // main thread, called in this order
    protected abstract void onInit(String[] args);
    protected abstract void onMainLoop();
    protected abstract void onDone();

    // render thread, called in this order
    protected abstract void onSize(int width, int height);
    protected abstract void onInitRender();
    // on...RenderFrame called here
    protected abstract void onResize(int width, int height); // surface contents may be lost!
    protected abstract void onDoneRender();

    // implement this interface to render using OpenGL
    public interface IGLRenderCallback {
        void onGLRenderFrame();
    }

    // implement this interface to render based on Canvas (note: entire surface is considered dirty)
    public interface ICanvasRenderCallback {
        void onCanvasRenderFrame(Canvas canvas);
    }

    // implement this interface to render based on hardware-accelerated Canvas (note: https://developer.android.com/guide/topics/graphics/hardware-accel#unsupported )
    // will use a normal software-based canvas on API < 23
    public interface IHardwareCanvasRenderCallback {
        void onHardwareCanvasRenderFrame(Canvas canvas);
    }

    // implement this interface to customize rendering yourself
    public interface ISurfaceRenderCallback {
        void onSurfaceRenderFrame(Surface surface);
    }

    // you may want to customize this for your purposes
    protected int getPixelFormat() {
        if (
            (Build.VERSION.SDK_INT >= 29) ||
            ((Build.VERSION.SDK_INT == 28) && (Build.VERSION.PREVIEW_SDK_INT != 0))
        ) {
            // on Q Preview 1, we need to set RGBA_8888 to get transparency working;
            // not sure why this worked previously with RGB_888, but never seen reports of it
            // *not* working, so not adjusting it for older versions
            return PixelFormat.RGBA_8888;
        } else if (Build.VERSION.SDK_INT >= 24) {
            // assume any device on 7.0+ is fastest as RGB_888
            return PixelFormat.RGB_888;
        } else {
            // on older devices this is faster than RGB_888, regardless of setting the EGL context to 888(8)
            return PixelFormat.RGB_565;
        }
    }
}
