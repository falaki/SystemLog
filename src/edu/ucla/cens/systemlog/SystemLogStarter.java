/**
 * SystemSens
 *
 * Copyright (C) 2009 Hossein Falaki
 */

package edu.ucla.cens.systemlog;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;



/**
 * Starts the SystemLog Service at boot time.
 *
 * @author      Hossein Falaki
 */
public class SystemLogStarter extends BroadcastReceiver 
{

    private static final String TAG = "SystemLogStartup";

    @Override
    public void onReceive(Context context, Intent intent)
    {

        context.startService(new Intent(context, 
                    SystemLog.class));
        Log.i(TAG, "Started SystemLog");

    }

}

