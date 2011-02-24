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
import android.util.Log;

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

    private static final String TAG = "SystemLogDbAdapter";
    private DatabaseHelper mDbHelper;
    private SQLiteDatabase mDb;
    
    /** Database creation SQL statement */
    private static final String DATABASE_CREATE =
            "create table systemlog (_id integer primary key "
           + "autoincrement, logger text not null, recordtime text not null, logrecord text not null);";

    private static final String DATABASE_NAME = "data";
    private static final String DATABASE_TABLE = "systemlog";
    private static final int DATABASE_VERSION = 3;

    private final Context mCtx;

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
            Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
                    + newVersion + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS systemlog");
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
    public SystemLogDbAdaptor open() throws SQLException 
    {
        mDbHelper = new DatabaseHelper(mCtx);
        mDb = mDbHelper.getWritableDatabase();
        return this;
    }
    
    /**
      * Closes the database.
      */
    public void close() 
    {
        mDbHelper.close();
    }


    /**
     * Create a new entry using the logrecord provided. 
     * If the entry is successfully created returns the new rowId for
     * that entry, otherwise returns a -1 to indicate failure.
     * 
     * @param logrecord        	log record for the entry
     * @param logger			logger name
     * @return                  rowId or -1 if failed
     */
    public synchronized long createEntry(String logger, String time, String logrecord) 
    {
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_LOGGER, logger);
        initialValues.put(KEY_LOGRECORD, logrecord);
        initialValues.put(KEY_TIME, time);

        return mDb.insert(DATABASE_TABLE, null, initialValues);
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
