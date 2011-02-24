/**
 * SystemLog
 *
 * Copyright (C) 2009 Center for Embedded Networked Sensing
 */
package edu.ucla.cens.systemlog;

import android.database.Cursor;
import android.util.Log;
import android.database.SQLException;

import java.util.HashSet;
import java.util.HashMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.MalformedURLException;


/**
 * This class implements mechanisms to write data collected by
 * SystemLog in a file on the sdcard. The file is named 
 * systemlogdump.txt
 * It is passed a pointer to a Database Adaptor object upon creation.
 * Each time the upload() method is called a new thread is spawned.
 * The new thread will read all the records in the
 * database and uploaded and then delete them.
 *
 * @author  Hossein Falaki
 */
public class SystemLogDumper
{
    /** Tag used for ADB log messages */
    private static final String TAG = "SystemLogDumper";
    
    private static final String FILE_PATH = "/sdcard/systemlogdump.txt";

    /** Database adaptor object */
    private SystemLogDbAdaptor mDbAdaptor;

    /** Maximum number of records that will be read and deleted at a
     * time*/
    private static final int MAX_DUMP_SIZE = 500;
    
    /** FileOutputStream handler */
    private FileOutputStream dumpStream;


    /** File handler */
    File mFile;

    /**
     * Constructor - creates a dumper object with access to the
     * given database adaptor object. 
     *
     * @param   dbAdaptor       database adaptor object
     */
    public SystemLogDumper(SystemLogDbAdaptor dbAdaptor)
    {
        this.mDbAdaptor = dbAdaptor;
        mFile = new File(FILE_PATH);
        

    }



    public void tryDump()
    {
        boolean dumpRes = true;
    	StringBuilder sBuffer = new StringBuilder();
        HashSet<Integer> keySet = new HashSet<Integer>();
        

        try
        {
            dumpStream = new FileOutputStream(mFile, true);
        }
        catch (FileNotFoundException fe)
        {
        	Log.e(TAG, "Exception when opening the file", fe);
        }

        //HashSet<Integer> keySet = new HashSet<Integer>();
        try
        {
            Cursor  c = mDbAdaptor.fetchAllEntries();
            int dataIndex = c.getColumnIndex(
                    SystemLogDbAdaptor.KEY_LOGRECORD);
            int idIndex = c.getColumnIndex(
                    SystemLogDbAdaptor.KEY_ROWID);
            int loggerIndex = c.getColumnIndex(
                    SystemLogDbAdaptor.KEY_LOGGER);
            


            
            int dbSize =  c.getCount();
            String newRecord, loggerName;

            //DEBUG
            Log.i(TAG, "Count: " + c.getCount());
            
            

            c.moveToFirst();
            while (dbSize > 0)
            {
                //DEBUG
                Log.i(TAG, "dbSize: " + dbSize);
                int maxCount = (MAX_DUMP_SIZE > dbSize) 
                    ? dbSize : MAX_DUMP_SIZE;


                for (int i=0; i < maxCount; i++)
                {
                    newRecord = c.getString(dataIndex);
                    loggerName = c.getString(loggerIndex);
                	sBuffer.append(newRecord + "\n");
                    keySet.add(c.getInt(idIndex));
                    c.moveToNext();
                }
                
                
                
                try
                {
                    dumpStream.write(sBuffer.toString().getBytes());
                }
                catch (IOException ioe)
                {
                    dumpRes = false;
                    Log.e(TAG, "write failed", ioe);
                }

                if (dumpRes)
                {
                    // Delete these records from the database
                    // DEBUG
                    Log.i(TAG, "keys to delete: " + keySet.toString());
                    for (int id : keySet)
                    {
                        if( !mDbAdaptor.deleteEntry(id) )
                        {
                            Log.e(TAG, "Error deleting row ID =" + id);
                        }
                    }
                    keySet.clear();
                }
                else
                {
                    Log.e(TAG, "tryDump failed");
                    c.close();
                    return;
                }

                
                dbSize -= MAX_DUMP_SIZE;
            }
            c.close();
            

            
        }
        catch (SQLException e)
        {
            Log.e(TAG, "Exception", e);
        }
        
        try
        {
        	dumpStream.flush();
        }
        catch (IOException ioe)
        {
        	Log.e(TAG, "Exception when flusshing the file", ioe);
        }

    }


}
