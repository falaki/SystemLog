/**
 * SystemLog
 *
 * Copyright (C) 2009 Hossein Falaki
 */

package edu.ucla.cens.systemlog;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


import java.lang.Thread;


import edu.ucla.cens.systemlog.SystemLogWakeLock;


/**
 * Logs polling sensors.
 *
 * @author      Hossein Falaki
 */
public class SystemLogAlarmReceiver extends BroadcastReceiver 
{

    private static final String TAG = "SystemLogAlarmReceiver";


    @Override
    public void onReceive(Context context, Intent intent)
    {

        Log.i(TAG, "Received repeating alarm.");
        // Acquire a lock
        SystemLogWakeLock.acquireCpuWakeLock(context);

        Intent newIntent = new Intent(context, SystemLog.class);
        newIntent.setAction(SystemLog.UPLOAD_ACTION);

        context.startService(newIntent);


    }

}

