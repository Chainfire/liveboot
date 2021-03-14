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
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class GLTextManager extends GLTextRendererBase {
    private class Line {
        private final String mText;
        private final int mColor;
        private Bitmap mBitmap = null;
        private GLPicture mPicture = null;

        public Line(String text, int color) {
            mText = text;
            mColor = color;
        }

        public void destroy() {
            if (mPicture != null) {
                mPicture.destroy();
                mPicture = null;
            }
            if (mBitmap != null) {
                mBitmaps.add(mBitmap);
                mBitmap = null;
            }
        }

        public GLPicture getPicture() {
            if (mBitmap == null) {
                if (mBitmaps.size() > 0) {
                    mBitmap = mBitmaps.remove(0);
                }
                mBitmap = GLTextManager.this.getBitmap(mText, mColor, mWidth, Justification.LEFT, mBitmap);
            }
            if (mPicture == null) {
                mPicture = GLTextManager.this.getPicture(mBitmap);
            }
            return mPicture;
        }

        public void resize() {
            if (mBitmap != null) {
                mBitmaps.add(mBitmap);
                mBitmap = null;
            }
            if (mPicture != null) {
                mPicture.destroy();
                mPicture = null;
            }
        }
    }

    protected volatile int mLeft;
    protected volatile int mTop;
    protected volatile int mWidth;
    protected volatile int mHeight;
    protected volatile int mMaxHeight;
    protected volatile int mLineCount;
    protected volatile List<Line> mLines = new ArrayList<Line>();
    protected volatile List<Bitmap> mBitmaps = new ArrayList<Bitmap>();
    protected volatile boolean mWordWrap = true;

    protected final ReentrantLock lock = new ReentrantLock(true);

    public GLTextManager(GLTextureManager textureManager, GLHelper helper, int width, int height, int lineHeight) {
        this(textureManager, helper, 0, 0, width, height, 0, lineHeight);
    }

    public GLTextManager(GLTextureManager textureManager, GLHelper helper, int left, int top, int width, int height, int maxHeight, int lineHeight) {
        super(textureManager, helper, Typeface.MONOSPACE, lineHeight);
        resize(left, top, width, height, maxHeight, lineHeight);
    }

    public void resize(int left, int top, int width, int height, int maxHeight, int lineHeight) {
        lock.lock();
        try {
            super.resize(lineHeight);
            if (left >= 0) mLeft = left;
            if (top >= 0) mTop = top;
            if (width >= 0) mWidth = width;
            if (height >= 0) mHeight = height;
            if (maxHeight >= 0) mMaxHeight = maxHeight;
            if (lineHeight >= 0) mLineCount = (int)Math.round(Math.ceil((float)mHeight / (float)mLineHeight));
            for (Line line : mLines) {
                line.resize();
            }
            for (Bitmap bitmap : mBitmaps) bitmap.recycle();
            mBitmaps.clear();
        } finally {
            lock.unlock();
        }
    }

    public ReentrantLock getLock() {
        return lock;
    }

    public void setWordWrap(boolean wordWrap) {
        lock.lock();
        try {
            mWordWrap = wordWrap;
        } finally {
            lock.unlock();
        }
    }

    public void draw() {
        draw(1.0f);
    }

    public void draw(float alpha) {
        lock.lock();
        try {
            boolean scissor = true;
            if (mMaxHeight != 0) {
                scissor = mHelper.scissorOn(mLeft, mTop, mWidth, mHeight, mMaxHeight);
            }

            int top = mTop + mHeight - mLineHeight;
            for (int i = mLines.size() - 1; i >= 0; i--) {
                mHelper.draw(mLines.get(i).getPicture(), mLeft, top, mWidth, mLineHeight, GLPicture.AlphaType.IMAGE, alpha);
                top -= mLineHeight;
            }

            mHelper.scissorOff(scissor);
        } finally {
            lock.unlock();
        }
    }

    private void add(Line line) {
        lock.lock();
        try {
            mLines.add(line);
        } finally {
            lock.unlock();
        }
    }

    private void reduce() {
        lock.lock();
        try {
            while (mLines.size() > mLineCount) {
                mLines.remove(0).destroy();
            }
        } finally {
            lock.unlock();
        }
    }

    public void add(String text, int color) {
        add(text, color, mWordWrap);
    }

    public void add(String text, int color, boolean wordWrap) {
        if (text == null) return;
        if (text.equals("")) {
            add(new Line("", color));
        } else {
            while (text.length() > 0) {
                int max = mPaint.breakText(text, true, mWidth - (mHorizontalPadding * 2), null);
                int lf = text.indexOf('\n');
                if ((lf >= 0) && (lf < max)) {
                    add(new Line(text.substring(0, lf), color));
                    if (text.length() > lf) {
                        text = text.substring(lf + 1);
                    } else {
                        break;
                    }
                } else {
                    add(new Line(text.substring(0, max), color));
                    if (text.length() > max) {
                        text = text.substring(max);
                    } else {
                        break;
                    }
                }
                if (!wordWrap) {
                    break;
                }
            }
        }
        reduce();
    }

    public void removeLastLine() {
        lock.lock();
        try {
            if (mLines.size() > 0) {
                mLines.remove(mLines.size() - 1).destroy();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void destroy() {
        lock.lock();
        try {
            for (Line line : mLines) line.destroy();
            mLines.clear();

            for (Bitmap bitmap : mBitmaps) bitmap.recycle();
            mBitmaps.clear();
        } finally {
            lock.unlock();
        }
    }
}
