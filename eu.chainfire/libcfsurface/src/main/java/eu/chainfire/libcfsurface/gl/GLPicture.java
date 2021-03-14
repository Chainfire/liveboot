/* Copyright 2014 Google Inc.
 * Copyright 2020 Jorrit 'Chainfire' Jongma
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
import android.graphics.Rect;
import android.opengl.GLES20;

import java.nio.FloatBuffer;

public class GLPicture implements GLObject {
    public static enum AlphaType { IMAGE, GLOBAL };

    private static final String VERTEX_SHADER_CODE = "" +
            // This matrix member variable provides a hook to manipulate
            // the coordinates of the objects that use this vertex shader
            "uniform mat4 uMVPMatrix;" +
            "attribute vec4 aPosition;" +
            "attribute vec2 aTexCoords;" +
            "varying vec2 vTexCoords;" +
            "void main() {" +
            "  vTexCoords = aTexCoords;" +
            "  gl_Position = uMVPMatrix * aPosition;" +
            "}";

    private static final String FRAGMENT_SHADER_CODE_FIXED_ALPHA = "" +
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "uniform float uAlpha;" +
            "varying vec2 vTexCoords;" +
            "void main() {" +
            "  gl_FragColor = texture2D(uTexture, vTexCoords);" +
            "  gl_FragColor.a = uAlpha;" +
            "}";

    private static final String FRAGMENT_SHADER_CODE_SOURCE_ALPHA = "" +
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "uniform float uAlpha;" +
            "varying vec2 vTexCoords;" +
            "void main() {" +
            "  gl_FragColor = texture2D(uTexture, vTexCoords) * uAlpha;" +
            "}";
    
    // number of coordinates per vertex in this array
    private static final int COORDS_PER_VERTEX = 3;
    private static final int VERTEX_STRIDE_BYTES = COORDS_PER_VERTEX * GLUtil.BYTES_PER_FLOAT;
    private static final int VERTICES = 6; // TL, BL, BR, TL, BR, TR

    // S, T (or X, Y)
    private static final int COORDS_PER_TEXTURE_VERTEX = 2;
    private static final int TEXTURE_VERTEX_STRIDE_BYTES = COORDS_PER_TEXTURE_VERTEX
            * GLUtil.BYTES_PER_FLOAT;

    private static final float[] SQUARE_TEXTURE_VERTICES = {
            0, 0, // top left
            0, 1, // bottom left
            1, 1, // bottom right

            0, 0, // top left
            1, 1, // bottom right
            1, 0, // top right
    };

    private boolean mHasContent = false;

    private float[] mVertices = new float[COORDS_PER_VERTEX * VERTICES];
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTextureCoordsBuffer;

    private static int sMaxTextureSize;

    private static int[] sProgramHandle = new int[] { 0, 0 };
    private static int[] sAttribPositionHandle = new int[] { 0, 0 };
    private static int[] sAttribTextureCoordsHandle = new int[] { 0, 0 };
    private static int[] sUniformAlphaHandle = new int[] { 0, 0 };
    private static int[] sUniformTextureHandle = new int[] { 0, 0 };
    private static int[] sUniformMVPMatrixHandle = new int[] { 0, 0 };

    private int mCols = 1;
    private int mRows = 1;
    private int mWidth = 0;
    private int mHeight = 0;
    private int mTileSize = sMaxTextureSize;
    private Object mTag = null;
    private int[] mTextureHandles;
    
    private GLTextureManager mTextureManager = null;
    private Bitmap mBitmap = null;
    
    public static void initGl() {
        // Initialize shaders and create/link program
        int vertexShaderHandle = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE);
        int fragShaderFixedAlphaHandle = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE_FIXED_ALPHA);
        int fragShaderSourceAlphaHandle = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE_SOURCE_ALPHA);

        sProgramHandle[0] = GLUtil.createAndLinkProgram(vertexShaderHandle, fragShaderFixedAlphaHandle, null);
        sProgramHandle[1] = GLUtil.createAndLinkProgram(vertexShaderHandle, fragShaderSourceAlphaHandle, null);
        for (int i = 0; i < 2; i++) {
            sAttribPositionHandle[i] = GLES20.glGetAttribLocation(sProgramHandle[i], "aPosition");
            sAttribTextureCoordsHandle[i] = GLES20.glGetAttribLocation(sProgramHandle[i], "aTexCoords");
            sUniformMVPMatrixHandle[i] = GLES20.glGetUniformLocation(sProgramHandle[i], "uMVPMatrix");
            sUniformTextureHandle[i] = GLES20.glGetUniformLocation(sProgramHandle[i], "uTexture");
            sUniformAlphaHandle[i] = GLES20.glGetUniformLocation(sProgramHandle[i], "uAlpha");
        }

        // Compute max texture size
        int[] maxTextureSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        sMaxTextureSize = maxTextureSize[0];
    }

    public GLPicture(GLTextureManager textureManager, Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        mTextureManager = textureManager;
        loadTexture(bitmap);        
        mBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    }

    private void loadTexture(Bitmap bitmap) {
        if (mHasContent) {
            return;
        }
        
        mTileSize = sMaxTextureSize;
        mHasContent = true;
        mVertexBuffer = GLUtil.newFloatBuffer(mVertices.length);
        mTextureCoordsBuffer = GLUtil.asFloatBuffer(SQUARE_TEXTURE_VERTICES);

        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
        int leftoverHeight = mHeight % mTileSize;

        // Load m x n textures
        mCols = mWidth / (mTileSize + 1) + 1;
        mRows = mHeight / (mTileSize + 1) + 1;

        mTextureHandles = new int[mCols * mRows];
        if (mCols == 1 && mRows == 1) {
            mTextureHandles[0] = mTextureManager.loadTexture(bitmap);
        } else {
            Rect rect = new Rect();
            for (int y = 0; y < mRows; y++) {
                for (int x = 0; x < mCols; x++) {
                    rect.set(x * mTileSize,
                            (mRows - y - 1) * mTileSize,
                            (x + 1) * mTileSize,
                            (mRows - y) * mTileSize);
                    // The bottom tiles must be full tiles for drawing, so only allow edge tiles
                    // at the top
                    if (leftoverHeight > 0) {
                        rect.offset(0, -mTileSize + leftoverHeight);
                    }
                    rect.intersect(0, 0, mWidth, mHeight);
                    Bitmap subBitmap = Bitmap.createBitmap(bitmap,
                            rect.left, rect.top, rect.width(), rect.height());
                    mTextureHandles[y * mCols + x] = mTextureManager.loadTexture(subBitmap);
                    subBitmap.recycle();
                }
            }
        }
    }
    
    private void releaseTexture() {
        if (!mHasContent) {
            return;
        }
        
        if (mTextureHandles != null) {
            //GLES20.glDeleteTextures(mTextureHandles.length, mTextureHandles, 0);
            //GLUtil.checkGlError("Destroy picture");
            for (int handle : mTextureHandles) {
                mTextureManager.releaseTextureHandle(handle);
            }
            mTextureHandles = null;
            mHasContent = false;
        }        
    }
    
    public Object getTag() {
        return mTag;
    }
    public void setTag(Object tag) {
        mTag = tag;
    }
    
    public int getWidth() {
        return mWidth;
    }
    public int getHeight() {
        return mHeight;
    }
    
    public void draw(float[] mvpMatrix) {
        draw(mvpMatrix, AlphaType.IMAGE, 1f);
    }

    public void draw(float[] mvpMatrix, float alpha) {
        draw(mvpMatrix, AlphaType.GLOBAL, alpha);
    }

    public void draw(float[] mvpMatrix, AlphaType alphaType, float alpha) {
        resume();
        if (!mHasContent) {
            return;
        }
        
        GLES20.glEnable(GLES20.GL_BLEND);
        
        int shader;

        if (alpha < 0) alphaType = AlphaType.IMAGE;
        if (alphaType == AlphaType.IMAGE) {
            shader = 1;
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            alpha = Math.abs(alpha);
        } else {
            shader = 0;
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE);
        }

        // Add program to OpenGL ES environment
        GLES20.glUseProgram(sProgramHandle[shader]);

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(sUniformMVPMatrixHandle[shader], 1, false, mvpMatrix, 0);
        GLUtil.checkGlError("glUniformMatrix4fv");

        // Set up vertex buffer
        GLES20.glEnableVertexAttribArray(sAttribPositionHandle[shader]);
        GLES20.glVertexAttribPointer(sAttribPositionHandle[shader],
                COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE_BYTES, mVertexBuffer);

        // Set up texture stuff
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(sUniformTextureHandle[shader], 0);
        GLES20.glVertexAttribPointer(sAttribTextureCoordsHandle[shader],
                COORDS_PER_TEXTURE_VERTEX, GLES20.GL_FLOAT, false,
                TEXTURE_VERTEX_STRIDE_BYTES, mTextureCoordsBuffer);
        GLES20.glEnableVertexAttribArray(sAttribTextureCoordsHandle[shader]);

        // Set the alpha
        GLES20.glUniform1f(sUniformAlphaHandle[shader], alpha);

        // Draw tiles
        for (int y = 0; y < mRows; y++) {
            for (int x = 0; x < mCols; x++) {
                // Pass in the vertex information
                mVertices[0] = mVertices[3] = mVertices[9]
                        = Math.min(-1 + 2f * x * mTileSize / mWidth, 1); // left
                mVertices[1] = mVertices[10] = mVertices[16]
                        = Math.min(-1 + 2f * (y + 1) * mTileSize / mHeight, 1); // top
                mVertices[6] = mVertices[12] = mVertices[15]
                        = Math.min(-1 + 2f * (x + 1) * mTileSize / mWidth, 1); // right
                mVertices[4] = mVertices[7] = mVertices[13]
                        = Math.min(-1 + 2f * y * mTileSize / mHeight, 1); // bottom
                mVertexBuffer.put(mVertices);
                mVertexBuffer.position(0);

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,
                        mTextureHandles[y * mCols + x]);
                GLUtil.checkGlError("glBindTexture");

                // Draw the two triangles
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mVertices.length / COORDS_PER_VERTEX);
            }
        }

        GLES20.glDisableVertexAttribArray(sAttribPositionHandle[shader]);
        GLES20.glDisableVertexAttribArray(sAttribTextureCoordsHandle[shader]);
    }
    
    public void suspend() {
        releaseTexture();
    }
    
    public void resume() {
        loadTexture(mBitmap);
    }

    @Override
    public void destroy() {
        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }
        releaseTexture();
    }
}