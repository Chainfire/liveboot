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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

public class GLTextRendererBase implements GLObject {
    public enum Justification { LEFT, CENTER, RIGHT };
    public static final int WIDTH_AUTO = 0;

    protected final GLTextureManager mTextureManager;
    protected final GLHelper mHelper;
    protected final Paint mPaint;
    protected final Typeface mTypeface;
    protected volatile int mTextSize;
    protected volatile int mLineHeight;
    protected volatile int mVerticalPadding;
    protected volatile int mHorizontalPadding;

    protected GLTextRendererBase(GLTextureManager textureManager, GLHelper helper, Typeface typeface, int lineHeight) {
        mTextureManager = textureManager;
        mHelper = helper;
        mTypeface = typeface;
        mPaint = new Paint();
        resize(lineHeight);
    }

    public void resize(int lineHeight) {
        if (lineHeight >= 0) mLineHeight = lineHeight;
        mTextSize = (int)Math.round(Math.ceil((float)mLineHeight / 1.2f));
        mVerticalPadding = (mLineHeight - mTextSize) / 2;
        mHorizontalPadding = mLineHeight / 4;
        mPaint.setAntiAlias(true);
        mPaint.setTypeface(mTypeface);
        mPaint.setTextSize(mTextSize);
        mPaint.setColor(Color.WHITE);
        mPaint.setShadowLayer(1.0f, 1.0f, 1.0f, Color.BLACK);
    }

    protected Bitmap getBitmap(String text, int color, int width, Justification justification, Bitmap inBitmap) {
        Rect r = new Rect();
        mPaint.getTextBounds(text, 0, text.length(), r);
        mPaint.setColor(color);
        int textWidth = (r.right - r.left) + (mHorizontalPadding * 2);

        if (width == WIDTH_AUTO) width = textWidth;

        Bitmap bitmap;
        if (inBitmap == null) {
            bitmap = Bitmap.createBitmap(width, mLineHeight, Bitmap.Config.ARGB_8888);
        } else {
            bitmap = inBitmap;
            width = bitmap.getWidth();
            bitmap.eraseColor(0x0000000);
        }

        int leftMargin = 0;
        switch (justification) {
            case LEFT: break;
            case CENTER: leftMargin = (width - textWidth) / 2; break;
            case RIGHT: leftMargin = (width - textWidth);
        }

        Canvas c = new Canvas(bitmap);
        c.drawText(text, -r.left + mHorizontalPadding + leftMargin, -r.top + mVerticalPadding, mPaint);
        return bitmap;
    }

    protected GLPicture getPicture(Bitmap bitmap) {
        return new GLPicture(mTextureManager, bitmap);
    }

    protected GLPicture getPicture(String text, int color, int width, Justification justification, Bitmap inBitmap) {
        return getPicture(getBitmap(text, color, width, justification, inBitmap));
    }

    @Override
    public void destroy() {
    }

    public int getLineHeight() {
        return mLineHeight;
    }
}
