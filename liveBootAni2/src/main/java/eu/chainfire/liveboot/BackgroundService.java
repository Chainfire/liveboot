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

package eu.chainfire.liveboot;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;

import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.liveboot.shell.Runner;

public class BackgroundService extends JobIntentService {
    public static final String BOOT_COMPLETED = "boot_completed";
    public static final String PACKAGE_REPLACED = "package_replaced"; 

    private static final int JOB_ID = 1000;
    private static final String EXTRA_ACTION = "eu.chainfire.livebootanimation.EXTRA_ACTION";
        
    public static void launch(Context context, String action) {
        Intent i = new Intent(context, BackgroundService.class);
        i.putExtra(EXTRA_ACTION, action);
        enqueueWork(context, BackgroundService.class, JOB_ID, i);
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        if (BOOT_COMPLETED.equals(intent.getStringExtra(EXTRA_ACTION))) {
            Shell.SU.run("echo 1 > " + Runner.LIVEBOOT_ABORT_FILE);
        }
        if (PACKAGE_REPLACED.equals(intent.getStringExtra(EXTRA_ACTION))) {
            Installer.installData(this);
        }
    }
}
