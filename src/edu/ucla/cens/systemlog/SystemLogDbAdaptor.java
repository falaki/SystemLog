/**
 * SystemLog
 *
 * Copyright (C) 2009 Center for Embedded Networked Sensing
 */
package edu.ucla.cens.systemlog;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.PowerManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.HashSet;
import java.util.Calendar;

import org.json.JSONObject;
import org.json.JSONException;

/**
 * Simple  database access helper class. 
 * Interfaces with the SQLite database to store system logs.
 * Written based on sample code provided by Google.
 *
 * @author Hossein Falaki
 */
public class SystemLogDbAdaptor 
{

    public static final String KEY_LOGRECORD = "logrecord";
    public static final String KEY_LOGGER = "logger";
    public static final String KEY_TIME = "recordtime";
    public static final String KEY_ROWID = "_id";


    private static final String IMEI = SystemLog.IMEI;
    private static final String VER = SystemLog.VER;


    private SimpleDateFormat mSDF;

    private static final String TAG = "SystemLogDbAdapter";
    private DatabaseHelper mDbHelper;
    private SQLiteDatabase mDb;

    private long mDbBirthDate;


    private HashSet<ContentValues> mBuffer;
    private HashSet<ContentValues> mTempBuffer;

    private boolean mOpenLock = false;
    private boolean mFlushLock = false;
    
    /** Database creation SQL statement */
    private static final String DATABASE_CREATE =
            "create table systemlog (_id integer primary key "
           + "autoincrement, logger text not null, "
           + "recordtime text not null, logrecord text not null);";
    private static final String DATABASE_DROP = 
        "DROP TABLE IF EXISTS systemlog";


    private static final String DATABASE_NAME = "data";
    private static final String DATABASE_TABLE = "systemlog";
    private static final int DATABASE_VERSION = 3;

    private static final long ONE_MINUTE = 1000 * 60;
    private static final long ONE_HOUR = 60 * ONE_MINUTE;
    private static final long ONE_DAY = 24 * ONE_HOUR;


    private static final long MIN_TICKLE_INTERVAL = ONE_HOUR;


    private final Context mCtx;
    private final PowerManager.WakeLock mWL;

    private static class DatabaseHelper extends SQLiteOpenHelper 
    {

        DatabaseHelper(Context context) 
        {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) 
        {
        	Log.i(TAG, "Creating database");
            db.execSQL(DATABASE_CREATE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, 
                int newVersion) 
        {
            Log.w(TAG, "Upgrading database from version " 
                    + oldVersion + " to "
                    + newVersion + ", which will destroy all old data");
            db.execSQL(DATABASE_DROP);
            onCreate(db);
        }
    }

    /**
     * Constructor - takes the context to allow the database to be
     * opened/created
     * 
     * @param ctx       the Context within which to work
     */
    public SystemLogDbAdaptor(Context ctx) 
    {
        this.mCtx = ctx;

        mBuffer = new HashSet<ContentValues>();

        PowerManager pm = (PowerManager)
            ctx.getSystemService(Context.POWER_SERVICE);
        mWL = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWL.setReferenceCounted(false);

        mSDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        mDbBirthDate = 0L;

    }

    /**
     * Open the database.
     * If it cannot be opened, try to create a new instance of the
     * database. If it cannot be created, throw an exception to signal
     * the failure.
     * 
     * @return this         (self reference, allowing this to be
     *                      chained in an initialization call)
     * @throws SQLException if the database could be neither opened or
     *                      created
     */
    public synchronized SystemLogDbAdaptor open() throws SQLException 
    {
        if (!mFlushLock)
        {
            mDbHelper = new DatabaseHelper(mCtx);
            mDb = mDbHelper.getWritableDatabase();
        }
        mOpenLock = true;
        return this;
    }
    
    /**
      * Closes the database.
      */
    public synchronized void close() 
    {
        if (!mFlushLock)
            mDbHelper.close();
        mOpenLock = false;
    }


    /**
      * Cause the database adaptor to drop the table and clreate it
      * again. This hack is necessary to prevent the index values from
      * getting too large. When the DB is created the index starts
      * from 0.  This method assumes that the database has been
      * opened.
      */
    public synchronized void tickle()
    {

        Log.i(TAG, "Got a tickle");


        if (!mOpenLock)
                return;


        long curTime = Calendar.getInstance().getTimeInMillis();

        if (curTime - mDbBirthDate < MIN_TICKLE_INTERVAL)
                return;


        SQLiteStatement countQuery = mDb.compileStatement(
                        "SELECT COUNT (*) FROM " + DATABASE_TABLE +
                        ";");

        long count = countQuery.simpleQueryForLong();

        if (count == 0)
        {
            Log.i(TAG, "Dropping the table.");
            mDb.execSQL(DATABASE_DROP);

            Log.i(TAG, "Creating a new table.");
            mDb.execSQL(DATABASE_CREATE);

            mDbBirthDate = curTime;
        }
    }


    /**
     * Constructs a log record JSON object with the given message
     * as the log message field and the given type as the type field. 
     * Returns the string representation of this new object.
     *
     * @param   message     log message
     * @param   tag         message tag
     * @param   level       Log level string
     * @param   logger      logger name
     */
    public synchronized void createEntry(String message, String tag, String level, String logger)
    {
        JSONObject dataRecord = new JSONObject();

        // First thing, get the current time
        final Calendar cal = Calendar.getInstance();
        String timeStr = mSDF.format(cal.getTime());

        /*
        String timeStr = "" +
            c.get(Calendar.YEAR) + "-" +
            (c.get(Calendar.MONTH) + 1) + "-" +
            c.get(Calendar.DAY_OF_MONTH) + " " +
            c.get(Calendar.HOUR_OF_DAY) + ":" +
            c.get(Calendar.MINUTE) + ":" +
            c.get(Calendar.SECOND);
        */

        try
        {
            dataRecord.put("date", timeStr);
            dataRecord.put("time_stamp", cal.getTimeInMillis());
            dataRecord.put("user", IMEI);
            dataRecord.put("tag", tag);
            dataRecord.put("logger", logger);
            dataRecord.put("ver", VER);
            dataRecord.put("message", message);
            dataRecord.put("level", level);
        }
        catch (JSONException e)
        {
            Log.e(TAG, "JSON Error", e);
            return;
        }


        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_LOGGER, logger);
        initialValues.put(KEY_LOGRECORD, dataRecord.toString());
        initialValues.put(KEY_TIME, timeStr);

        mBuffer.add(initialValues);


    }




    /**
     * Opens a new thread and flush the cached log records into the
     * database. 
     */
    public void flushDb()
    {
        synchronized(this)
        {
            mTempBuffer = mBuffer;
            mBuffer = new HashSet<ContentValues>();
        }

        Log.i(TAG, "flushDB called to flush " + mTempBuffer.size() 
                + " records.");

        Thread flushThread = new Thread()
        {
            public void run()
            {
                mWL.acquire();

                if (!mOpenLock)
                {
                    try
                    {
                        mDbHelper = new DatabaseHelper(mCtx);
                        mDb = mDbHelper.getWritableDatabase();
                    }
                    catch (SQLException se)
                    {
                        Log.e(TAG, "Could not open DB to flush records" , 
                                se);
                    }
                }
                mFlushLock = true;

                Log.i(TAG, "Flusshing " 
                        + mTempBuffer.size() + " records.");

                for (ContentValues value : mTempBuffer)
                    mDb.insert(DATABASE_TABLE, null, value);

                if (!mOpenLock)
                {
                    mDb.close();
                    mDbHelper.close();
                }

                mFlushLock = false;
                mWL.release();
            }

        };

        flushThread.start();


    }

    /**
     * Deletes the entry with the given rowId
     * 
     * @param rowId         id of log record to delete
     * @return              true if deleted, false otherwise
     */
    public synchronized boolean deleteEntry(long rowId) 
    {

        return mDb.delete(DATABASE_TABLE, KEY_ROWID 
                + "=" + rowId, null) > 0;
    }


    /**
     * Deletes all the entries between the given ID boundries
     * 
     * @param fromId        id of the first log record to delete
     * @param toId          id of the last log record to delete
     * @return              true if deleted, false otherwise
     */
    public synchronized boolean deleteRange(long fromId, long toId) 
    {

        return mDb.delete(DATABASE_TABLE, KEY_ROWID 
                + " BETWEEN " 
                + fromId
                + " AND "
                + toId, null) > 0;

    }


    /**
     * Returns a Cursor over the list of all logrecords in the database
     * 
     * @return              Cursor over all notes
     */
    public Cursor fetchAllEntries() 
    {

        return mDb.query(DATABASE_TABLE, new String[] {KEY_ROWID, KEY_LOGGER,
                KEY_TIME, KEY_LOGRECORD}, null, null, null, null, null);
    }

    /**
     * Returns a Cursor positioned at the record that matches the
     * given rowId.
     * 
     * @param  rowId        id of note to retrieve
     * @return              Cursor positioned to matching note, if found
     * @throws SQLException if note could not be found/retrieved
     */
    public Cursor fetchEntry(long rowId) throws SQLException 
    {

        Cursor mCursor = mDb.query(true, DATABASE_TABLE, new String[]
                {KEY_ROWID, KEY_LOGGER, KEY_TIME, KEY_LOGRECORD}, KEY_ROWID + "=" + rowId,
                null, null, null, null, null);
        if (mCursor != null) {
            mCursor.moveToFirst();
        }
        return mCursor;

    }

}
