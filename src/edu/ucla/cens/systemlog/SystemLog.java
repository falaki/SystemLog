package edu.ucla.cens.systemlog;

import java.util.HashMap;
import java.util.Calendar;
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

import org.json.JSONObject;
import org.json.JSONException;

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

    public static final String UPLOAD_ACTION = "upload";
	
	private static final boolean OPERATE_LOCAL = false;
	
	/** Version of SystemLog JSON record format */
	private static final String VER = "2.2";
	
	/** Types of messages used by this service */
    private static final int UPLOAD_START_MSG = 2;
    private static final int UPLOAD_END_MSG   = 3;


    /** Time units */
    private static final long ONE_SECOND = 1000;
    private static final long ONE_MINUTE = 60 * ONE_SECOND;
    private static final long TEN_MINUTES = 10 * ONE_MINUTE;
    
    private static final String ERROR_LOGLEVEL = "error";
    private static final String WARNING_LOGLEVEL = "warning";
    private static final String INFO_LOGLEVEL = "info";
    private static final String DEBUG_LOGLEVEL = "debug";
    private static final String VERBOSE_LOGLEVEL = "verbose";
    
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
    
    /** Table that keeps tag to table name associations */
    private HashMap<String, String> mTagMapping;
    
    /** Flag set when the phone is plugged */
    private static boolean mIsPlugged = false;;
	
	private final ISystemLog.Stub mBinder = new ISystemLog.Stub()
	{
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




            constructLogRecord(filteredMsg, tag, loglevel, logger);
            return true;
			
		}

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


                //First check if the phone is plugged, to trigger
                //upload.
                /*
                if (status == BatteryManager.BATTERY_STATUS_CHARGING)
                {
                	mIsPlugged = true;
                }
                
                if (status == BatteryManager.BATTERY_STATUS_DISCHARGING)
                {
                	mIsPlugged = false;
                }
                */
                 
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
                if (action.equals(UPLOAD_ACTION))
                    if (mIsPlugged)
                    {
                        Log.i(TAG, "Asking for an upload.");
                        upload();
                    }
        }

    }

    @Override
    public void onDestroy()
    {
        unregisterReceiver(mBatteryInfoReceiver);
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


        try
        {
            mDbAdaptor.open();
        }
        catch (SQLException e)
        {
            Log.e(TAG, "Exception", e);
        }

        // Register for battery updates
        registerReceiver(mBatteryInfoReceiver, new IntentFilter(
                    Intent.ACTION_BATTERY_CHANGED));


        // Register for a repeating alarm every five minutes
        Intent alarmIntent = new Intent(SystemLog.this,
                SystemLogAlarmReceiver.class);
        PendingIntent sender = PendingIntent.getBroadcast(
                SystemLog.this, 0, alarmIntent, 0);
        long firstTime = SystemClock.elapsedRealtime() +
            (TEN_MINUTES);

        AlarmManager am = (AlarmManager)
            getSystemService(ALARM_SERVICE);
        am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                firstTime, TEN_MINUTES, sender);
    }
    
    /**
     * Constructs a log record JSON object with the given message
     * as the log message field and the given type as the type field. 
     * Returns the string representation of this new object.
     *
     * @param   data        JSONObject to be used for the data field
     * @param   type        String to be used for the type field
     * @return 				String representing the JSON record
     */
    private void constructLogRecord(String message, String tag, String level, String logger)
    {
        JSONObject dataRecord = new JSONObject();

        // First thing, get the current time
        final Calendar c = Calendar.getInstance();
        String timeStr = "" +
            c.get(Calendar.YEAR) + "-" +
            (c.get(Calendar.MONTH) + 1) + "-" +
            c.get(Calendar.DAY_OF_MONTH) + " " +
            c.get(Calendar.HOUR_OF_DAY) + ":" +
            c.get(Calendar.MINUTE) + ":" +
            c.get(Calendar.SECOND);

        try
        {
            dataRecord.put("date", timeStr);
            dataRecord.put("time_stamp", c.getTimeInMillis());
            dataRecord.put("user", IMEI);
            dataRecord.put("tag", tag);
            dataRecord.put("logger", logger);
            dataRecord.put("ver", VER);
            dataRecord.put("message", message);
            dataRecord.put("level", level);
        }
        catch (JSONException e)
        {
            Log.e(TAG, "Exception", e);
        }

        mDbAdaptor.createEntry(logger, timeStr, dataRecord.toString());

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
