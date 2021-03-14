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

public class GLTextRenderer extends GLTextRendererBase {
    public GLTextRenderer(GLTextureManager textureManager, GLHelper helper, Typeface typeface, int lineHeight) {
        super(textureManager, helper, typeface, lineHeight);
    }

    @Override
    public Bitmap getBitmap(String text, int color, int width, Justification justification, Bitmap inBitmap) {
        return super.getBitmap(text, color, width, justification, inBitmap);
    }

    @Override
    public GLPicture getPicture(Bitmap bitmap) {
        return super.getPicture(bitmap);
    }

    @Override
    public GLPicture getPicture(String text, int color, int width, Justification justification, Bitmap inBitmap) {
        return super.getPicture(text, color, width, justification, inBitmap);
    }
}
