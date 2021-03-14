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

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import eu.chainfire.libcfsurface.BuildConfig;
import eu.chainfire.librootjava.Logger;

public class GLUtil {
    public static final int BYTES_PER_FLOAT = 4;

    public static int loadShader(int type, String shaderCode) {
        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        int shaderHandle = GLES20.glCreateShader(type);

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shaderHandle, shaderCode);
        GLES20.glCompileShader(shaderHandle);
        checkGlError("glCompileShader");
        return shaderHandle;
    }

    public static int createAndLinkProgram(int vertexShaderHandle, int fragShaderHandle,
            String[] attributes) {
        int programHandle = GLES20.glCreateProgram();
        GLUtil.checkGlError("glCreateProgram");
        GLES20.glAttachShader(programHandle, vertexShaderHandle);
        GLES20.glAttachShader(programHandle, fragShaderHandle);
        if (attributes != null) {
            final int size = attributes.length;
            for (int i = 0; i < size; i++) {
                GLES20.glBindAttribLocation(programHandle, i, attributes[i]);
            }
        }
        GLES20.glLinkProgram(programHandle);
        GLUtil.checkGlError("glLinkProgram");
        GLES20.glDeleteShader(vertexShaderHandle);
        GLES20.glDeleteShader(fragShaderHandle);
        return programHandle;
    }
    
    public static void checkGlError(String glOperation) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Logger.d(glOperation + ": glError " + error);
            if (BuildConfig.DEBUG) {
                throw new RuntimeException(glOperation + ": glError " + error);
            }
        }
    }

    public static FloatBuffer asFloatBuffer(float[] array) {
        FloatBuffer buffer = newFloatBuffer(array.length);
        buffer.put(array);
        buffer.position(0);
        return buffer;
    }

    public static FloatBuffer newFloatBuffer(int size) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.position(0);
        return buffer;
    }
}