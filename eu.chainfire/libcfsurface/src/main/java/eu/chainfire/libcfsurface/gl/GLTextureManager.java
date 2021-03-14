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

package eu.chainfire.libcfsurface.gl;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import eu.chainfire.libcfsurface.BuildConfig;
import eu.chainfire.librootjava.Logger;

public class GLTextureManager {
    private static final boolean logHandles = false;
    
    private ArrayList<Integer> handles = new ArrayList<Integer>();

    protected final ReentrantLock lock = new ReentrantLock(true);

    public int newTextureHandle() {
        lock.lock();
        try {
            while (handles.size() > 0) {
                int h = handles.get(handles.size() - 1);
                handles.remove(handles.size() - 1);
                
                if (GLES20.glIsTexture(h)) {
                    if (logHandles) Logger.d("newTextureHandle-->old[%d]", h);
                    return h;
                }
            }
            
            final int[] h = new int[1];
            GLES20.glGenTextures(1, h, 0);
            if (logHandles) Logger.d("newTextureHandle-->new[%d]", h[0]);
            GLUtil.checkGlError("glGenTextures");
            return h[0];                
        } finally {
            lock.unlock();
        }
    }
        
    public void releaseTextureHandle(int handle) {
        lock.lock();
        try {
            // this may not work from current thread, so hand off to texture pool
            GLES20.glDeleteTextures(1, new int[] { handle }, 0); 
            
            handles.add(handle);
        } finally {
            lock.unlock();
        }
    }
    
    private void releaseTextureHandles() {
        lock.lock();
        try {
            for (int h : handles) {
                GLES20.glDeleteTextures(1, new int[] { h }, 0);
            }
            handles.clear();
        } finally {
            lock.unlock();
        }
    }    

    public void destroy() {
        releaseTextureHandles();
    }
    
    public int loadTexture(Bitmap bitmap) {
        final int[] textureHandle = new int[] { newTextureHandle() };

        if (textureHandle[0] != 0) {
            // Bind to the texture in OpenGL
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);

            // Set filtering
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);

            // Load the bitmap into the bound texture.
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLUtil.checkGlError("texImage2D");
        }

        if (textureHandle[0] == 0) {
            Logger.d("Error loading texture (empty texture handle)");
            if (BuildConfig.DEBUG) {
                throw new RuntimeException("Error loading texture (empty texture handle).");
            }
        }
        
        if (!GLES20.glIsTexture(textureHandle[0])) {
            Logger.d("Error loading texture (invalid texture handle)");
            if (BuildConfig.DEBUG) {
                throw new RuntimeException("Error loading texture (invalid texture handle).");
            }
        }

        return textureHandle[0];
    }
}
