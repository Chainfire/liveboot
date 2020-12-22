/* Copyright (C) 2011-2020 Jorrit "Chainfire" Jongma
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

package eu.chainfire.liveboot;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.view.Surface;
import android.view.WindowManager;

import eu.chainfire.librootjava.Logger;
import eu.chainfire.liveboot.R;

public class MainActivity extends Activity {
    public static final int REQUEST_PURCHASE = 1501;

    private Handler handler = new Handler();
    private boolean autoExit = true;
    private int autoExitCounter = 0;
    
    private int getDeviceDefaultOrientation() {
        WindowManager windowManager =  (WindowManager) getSystemService(WINDOW_SERVICE);

        Configuration config = getResources().getConfiguration();

        int rotation = windowManager.getDefaultDisplay().getRotation();

        if ( ((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) &&
                config.orientation == Configuration.ORIENTATION_LANDSCAPE)
            || ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) &&    
                config.orientation == Configuration.ORIENTATION_PORTRAIT)) {
          return Configuration.ORIENTATION_LANDSCAPE;
        } else { 
          return Configuration.ORIENTATION_PORTRAIT;
        }
    }    
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(getDeviceDefaultOrientation());
        setContentView(R.layout.activity_main); 
    }    

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (autoExit) {
            final int counter = autoExitCounter;
            handler.postDelayed(new Runnable() {                
                @Override
                public void run() {
                    if (autoExitCounter == counter)
                        finish();
                }
            }, 60000);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        autoExit = true;
        autoExitCounter++;
    }

    public void setAutoExit(boolean autoExit) {
        this.autoExit = autoExit;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if ((requestCode == REQUEST_PURCHASE) && (data != null) && (resultCode == Activity.RESULT_OK)) {
                finish();
                startActivity(new Intent(this, MainActivity.class));
            }
        } catch (Exception e) {  
            Logger.ex(e);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }            
}
