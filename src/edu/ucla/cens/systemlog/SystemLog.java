package edu.ucla.cens.systemlog;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;


import android.content.BroadcastReceiver;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.RemoteException;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.util.Log;
import android.telephony.TelephonyManager;
import android.database.SQLException;

import android.util.AndroidException;

/**
 * SystemLog runs as a service and receives log messages from
 * other applications on the phone. The log messages are saved
 * as JSON records in a local database, and uploaded to sensorbase.org
 * periodically.
 */
public class SystemLog extends Service
{
	/** name of the service used for adb logging */
	private static final String TAG = "SystemLog";


    private static String ACTION_LOG_MESSAGE =
                "edu.ucla.cens.systemlog.log_message";

    private static final String KEY_TAG =
                "edu.ucla.cens.systemlog.key_tag";
    private static final String KEY_MSG =
                "edu.ucla.cens.systemlog.key_msg";
    private static final String KEY_APP_NAME =
                "edu.ucla.cens.systemlog.key_app_name";
    private static final String KEY_LOG_LEVEL =
                "edu.ucla.cens.systemlog.key_log_level";

    public static final String UPLOAD_ACTION = "upload";
	
	private static final boolean OPERATE_LOCAL = false;
	
	/** Version of SystemLog JSON record format */
	public static final String VER = "2.2";
	
	/** Types of messages used by this service */
    private static final int UPLOAD_START_MSG = 2;
    private static final int UPLOAD_END_MSG   = 3;


    /** Time units */
    private static final long ONE_SECOND = 1000;
    private static final long ONE_MINUTE = 60 * ONE_SECOND;
    private static final long TWO_MINUTES = 2 * ONE_MINUTE;
    
    private static final String ERROR_LOGLEVEL = "error";
    private static final String WARNING_LOGLEVEL = "warning";
    private static final String INFO_LOGLEVEL = "info";
    private static final String DEBUG_LOGLEVEL = "debug";
    private static final String VERBOSE_LOGLEVEL = "verbose";

    private ArrayList<String> mLogLevels;
    
    /** The default sensorbase.org table name */
    private static final String DEFAULT_LOGGER = "androidsyslog2";

	
    /** Holds the IMEI of the device */
    public static  String IMEI;	

	 /** State variable set when a worker thread starts uploading */
    private boolean mIsUploading;
    
    /** Uploader Object */
    private Uploader mUploader;
    
    /** Dumper object */
    private SystemLogDumper mDumper;

    /** Database adaptor object */
    private SystemLogDbAdaptor mDbAdaptor;
    
    /** telephonyManager object */
    private TelephonyManager mTelManager;


    /** Alarm Manager object */
    AlarmManager  mAlarmManager;

    PendingIntent mUploadSender;
    
    /** Table that keeps tag to table name associations */
    private HashMap<String, String> mTagMapping;
    
    /** Flag set when the phone is plugged */
    private static boolean mIsPlugged = false;;
	
	private final ISystemLog.Stub mBinder = new ISystemLog.Stub()
	{

        /** 
         * Returns true of the given tag has been registered.  
         * 
         * @param       tag         tag to check for registeration 
         *                          status 
         * @return                  true if the tag has been * registered 
         */
        public boolean isRegistered(String tag)
        {
			if (mTagMapping.containsKey(tag))
            {
                return true;
            }
            else
            {
                return false;
            }

        }

		
		/**
		 * Sends the given info-level log message to be logged with 
		 * the given tag.
		 *
		 * @param		tag			tag associated with the log message
		 * @param		message		log message
		 */
		public boolean info (String tag, String message) 
		{
			return log(tag, message, INFO_LOGLEVEL);
		}
		
		/**
		 * Sends the given debug-level log message to be logged with 
		 * the given tag.
		 *
		 * @param		tag			tag associated with the log message
		 * @param		message		log message
		 */
		public boolean debug (String tag, String message)
		{
			return log(tag, message, DEBUG_LOGLEVEL);
		}
		

		/**
		 * Sends the given warning-level log message to be logged with 
		 * the given tag.
		 *
		 * @param		tag			tag associated with the log message
		 * @param		message		log message
		 */
		public boolean warning (String tag, String message)
		{
			return log(tag, message, WARNING_LOGLEVEL);
		}
		
		/**
		 * Sends the given error-level log message to be logged with 
		 * the given tag.
		 *
		 * @param		tag			tag associated with the log message
		 * @param		message		log message
		 */
		public boolean error ( String tag, String message)
		{
			return log(tag, message, ERROR_LOGLEVEL);
		}

		/**
		 * Sends the given verbose-level log message to be logged with 
		 * the given tag.
		 *
		 * @param		tag			tag associated with the log message
		 * @param		message		log message
		 */
		public boolean verbose ( String tag, String message)
		{
			return log(tag, message, VERBOSE_LOGLEVEL);
		}

		
		
		/**
		 * Registers the given tag and dbTable name with 
		 * SystemLog. All logs with the given tag will be
		 * uploaded to the corresponding dbTable on 
		 * sensorbase.org.
		 * 
		 * @param		tag			tag that will be used for logging
		 * @param		dbTable		table name on sensorbase.org
		 * @return					registration result. True if succeeds. 
		 */ 
		public boolean registerLogger (String tag, String dbTable)
		{
			mTagMapping.put(tag, dbTable);
			return true;
		}
	};


	
	
    /**
     * Broadcast receiver for Battery information updates.
     * An object of this class has been passed to the system through 
     * registerReceiver. 
     *
     */
    private BroadcastReceiver mBatteryInfoReceiver = new
        BroadcastReceiver()
    {
        /**
         * Method called whenever the intent is received.
         * 
         */
        @Override
        public void onReceive(Context context, Intent intent) 
        {
            String action = intent.getAction();
            
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) 
            {

                // Get Battery status
                int plugType = intent.getIntExtra("plugged", 0);

                if (plugType > 0)
                {
                	mIsPlugged = true;

                    Log.i(TAG, "Phone is plugged.");
                    Log.i(TAG, "Starting upload.");
                    upload();

                }
                else
                {

                	mIsPlugged = false;
                }

                int status = intent.getIntExtra("status",
                        BatteryManager.BATTERY_STATUS_UNKNOWN);

                 
            }
        }
    };
    
	
	@Override
	public IBinder onBind(Intent intent)
	{
		return mBinder;
	}

    @Override
    public void onStart(Intent intent, int startId)
    {
        super.onStart(intent, startId);
        Log.i(TAG, "onStart");

        if (intent != null)
        {
            String action = intent.getAction();

            if (action != null)
            {
                if (action.equals(UPLOAD_ACTION))
                {
                    Log.i(TAG, "Flushing to DB.");
                    mDbAdaptor.flushDb();
                    if (mIsPlugged)
                    {
                        Log.i(TAG, "Asking for an upload.");
                        upload();
                    }
                }
                else if(action.equals(ACTION_LOG_MESSAGE))
                {
                    logIntentMessage(intent);
                }

            }
        }

    }

    @Override
    public void onDestroy()
    {
        unregisterReceiver(mBatteryInfoReceiver);
        mAlarmManager.cancel(mUploadSender);
    }
	
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");

        mTagMapping = new HashMap<String, String>();
        /* This object is used to log call durations */
        mTelManager =
            (TelephonyManager)this.getSystemService(
                    Context.TELEPHONY_SERVICE);
        this.IMEI = mTelManager.getDeviceId(); 
        mIsUploading = false;
        mDbAdaptor = new SystemLogDbAdaptor(this);
        mUploader = new Uploader(mDbAdaptor);
        mDumper = new SystemLogDumper(mDbAdaptor);


        mLogLevels = new ArrayList<String>(Arrays.asList(
                    ERROR_LOGLEVEL,
                    WARNING_LOGLEVEL,
                    INFO_LOGLEVEL,
                    DEBUG_LOGLEVEL,
                    VERBOSE_LOGLEVEL));

        // Register for battery updates
        registerReceiver(mBatteryInfoReceiver, new IntentFilter(
                    Intent.ACTION_BATTERY_CHANGED));


        // Register for a repeating alarm every five minutes
        Intent alarmIntent = new Intent(SystemLog.this,
                SystemLogAlarmReceiver.class);
         mUploadSender = PendingIntent.getBroadcast(
                SystemLog.this, 0, alarmIntent, 0);
        long firstTime = SystemClock.elapsedRealtime() +
            (TWO_MINUTES);

        mAlarmManager = (AlarmManager)
            getSystemService(ALARM_SERVICE);
        mAlarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME,
                firstTime, TWO_MINUTES, mUploadSender);
    }


    /**
     * Private method used internally for logging.
     * 
     * @param 		tag			tag associated with the log message
     * @param 		message		log message
     * @param 		loglevel	log level string 
     */
    private boolean log(String tag, String message, String loglevel)
    {
        String logger;

        // Filter non-ascii characters.
        String filteredMsg = message.replaceAll("[^\\x20-\\x7e]", "");

        Log.i(TAG, "Received from " + tag + ": " + filteredMsg);

        
        if (!mTagMapping.containsKey(tag))
        {
            return false;
        }
        else
        {
            logger = mTagMapping.get(tag);
        }


        mDbAdaptor.createEntry(filteredMsg, tag, loglevel, logger);
        return true;
        
    }

    
    /**
     * Constructs a log record from information encoded in an Intent.
     *
     * @param   intent        Intent received from logger app.
     */
    private void logIntentMessage(Intent intent)
    {
        String appName = intent.getStringExtra(KEY_APP_NAME);
        String tag = intent.getStringExtra(KEY_TAG);
        String msg = intent.getStringExtra(KEY_MSG);
        String logLevel = intent.getStringExtra(KEY_LOG_LEVEL);


        if(appName == null || 
                tag == null || 
                msg == null || 
                logLevel == null) 
        {
            Log.w(TAG, "Received message intent with null field(s).");
            return;
        }

        if (!mLogLevels.contains(logLevel))
        {
            Log.w(TAG, "Invalid log level string.");
            return;
        }

        //Register the tag if necessary
        if(!mTagMapping.containsKey(tag)) 
        {
            Log.i(TAG, "Registering " + tag + " for " + appName);
            mTagMapping.put(tag, appName);
        }

        log(tag, msg, logLevel);
    }
    
    
    /**
     * Message handler object.
     */
    private final Handler mHandler = new Handler()
    {
        @Override
        public void handleMessage(Message msg)
        {
            if (msg.what == UPLOAD_START_MSG)
            {
                mIsUploading = true;
            }

            if (msg.what == UPLOAD_END_MSG)
            {
                mIsUploading = false;
            }
        }

    };    


    
    
    /**
     * Spawns a worker thread to "try" to upload the contents of the
     * database.
     * Before starting the thread, checks if a worker thread is
     * already trying to upload. If so, returns. Otherwise a new
     * thread is spawned and tasked with the upload job.
     * 
     */
    private void upload()
    {
        if (!mIsUploading)
        {
            SystemLogWakeLock.acquireCpuWakeLock(this);
            Thread uploaderThread = new Thread()
            {
                public void run()
                {
                    // Send an immediate message to the main thread
                    // to inform that a worker thread is running.
                    mHandler.sendMessageAtTime( mHandler.obtainMessage(
                                UPLOAD_START_MSG), 
                            SystemClock.uptimeMillis());

                    
                    Log.i(TAG, "Worker thread started upload task");
                    if (OPERATE_LOCAL)
                    {
                    	mDumper.tryDump();
                    }
                    else
                    {
                    	mUploader.tryUpload();
                    }
                    	

                    // Send an immediate message to the main thread to
                    // inform that the worker thread is finished.
                    mHandler.sendMessageAtTime( mHandler.obtainMessage(
                                UPLOAD_END_MSG), 
                            SystemClock.uptimeMillis());
                }
            };

            uploaderThread.start();

        }
        else
        {
            Log.i(TAG, "Upload in already progress ...");
        }
    }
    
    public static boolean isPlugged()
    {
    	return mIsPlugged;
    }

    
}
