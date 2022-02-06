/* Copyright (C) 2011-2022 Jorrit "Chainfire" Jongma
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

import java.io.File;

import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.StreamGobbler;

public class Script {
    private static final int COLOR_STDOUT = Color.WHITE;
    private static final int COLOR_STDERR = Color.RED;

    private final Shell.Interactive mShell;
    private final OnLineListener mOnLineListener;
    
    public Script(OnLineListener onLineListener, String script) {
        mOnLineListener = onLineListener;

        if (!(new File(script)).exists()) {
            mShell = null;
            return;
        }
        
        final Script _this = this;
        mShell = (new Shell.Builder())
            .useSH()
            .setOnSTDOUTLineListener(new StreamGobbler.OnLineListener() {
                @Override
                public void onLine(String line) {
                    mOnLineListener.onLog(_this, line);
                    mOnLineListener.onLine(Script.this, line, COLOR_STDOUT);
                }
            })
            .setOnSTDERRLineListener(new StreamGobbler.OnLineListener() {                
                @Override
                public void onLine(String line) {
                    mOnLineListener.onLog(_this, line);
                    mOnLineListener.onLine(Script.this, line, COLOR_STDERR);
                }
            })
            .addCommand("sh " + script)
            .open();
    }
    
    public void destroy() {
        final Shell.Interactive shell = mShell;
        if (shell != null) {
            (new Thread(new Runnable() {            
                @Override
                public void run() {
                    shell.kill();
                    shell.close();
                }
            })).start();
        }
    }
}
