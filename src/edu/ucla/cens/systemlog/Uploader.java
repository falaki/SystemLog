/**
 * SystemLog
 *
 * Copyright (C) 2009 Hossein Falaki
 */
package edu.ucla.cens.systemlog;

import android.database.Cursor;
import android.util.Log;
import android.database.SQLException;

import java.lang.ProcessBuilder;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;





/**
 * This class implements mechanisms to upload data collected by
 * SystemLog.
 * It is passed a pointer to a Database Adaptor object upon creation.
 * Each time the upload() method is called a new thread is spawned.
 * The new thread will read all the records in the
 * database and uploaded and then delete them.
 *
 * @author  Hossein Falaki
 */
public class Uploader
{
    /** Tag used for log messages */
    private static final String TAG = "SystemLogUploader";

    /** Database adaptor object */
    private SystemLogDbAdaptor mDbAdaptor;

    /** Maximum number of records that will be read and deleted at a
     * time*/
    private static final int MAX_UPLOAD_SIZE = 1000;

    /** After this number of failiurs upload will abort */
    private static final int MAX_FAIL_COUNT = 50;


    /** Upload location of the SystemSens server */
    private static final String CUSTOM_URL 
        = "https://systemsens.cens.ucla.edu/systemlog/logs/put/";


    private static final String IMEI = SystemLog.IMEI;



    /**
     * Constructor - creates an uploader object with access to the
     * given database adaptor object. 
     *
     * @param   dbAdaptor       database adaptor object
     */
    public Uploader(SystemLogDbAdaptor dbAdaptor)
    {
        this.mDbAdaptor = dbAdaptor;
    }





    public void tryUpload()
    {

        Log.i(TAG, "tryUpload started");
        Cursor  c = null;
        boolean postResult = false;

        try
        {
            mDbAdaptor.open();

            c = mDbAdaptor.fetchAllEntries();
            int logIndex = c.getColumnIndex(
                    SystemLogDbAdaptor.KEY_LOGRECORD);
            int idIndex = c.getColumnIndex(
                    SystemLogDbAdaptor.KEY_ROWID);
            int timeIndex = c.getColumnIndex(
                    SystemLogDbAdaptor.KEY_TIME);
            int loggerIndex = c.getColumnIndex(
                    SystemLogDbAdaptor.KEY_LOGGER);


            Integer id;
            int dbSize =  c.getCount();
            HashSet<Integer> keySet = new HashSet<Integer>();
            String newRecord, newTime;
            ArrayList<String> content;

            int failCount = 0;



            c.moveToFirst();


            while ((dbSize > 0) && SystemLog.isPlugged())
            {
                
                Log.i(TAG, "Total DB size is: " + dbSize);
                int maxCount = (MAX_UPLOAD_SIZE > dbSize) 
                    ? dbSize : MAX_UPLOAD_SIZE;

                content = new ArrayList<String>();

                for (int i = 0; i < maxCount; i++)
                {

                    id = c.getInt(idIndex);
                    
                    newRecord =  c.getString(logIndex);


                    content.add(URLEncoder.encode(newRecord));
                    keySet.add(id);

                    c.moveToNext();
                    
                }
                
                dbSize -= maxCount;

                do
                {
                    postResult =  doPost("data=" 
                            + content.toString(), CUSTOM_URL);
                    if (postResult)
                    {
                        failCount = 0;
                        long fromId = Collections.min(keySet);
                        long toId = Collections.max(keySet);
                        Log.i(TAG, "Deleting [" 
                                + fromId + ", " + toId + "]"); 

                        if( !mDbAdaptor.deleteRange(fromId, toId) )
                        {
                            Log.e(TAG, "Error deleting range [" 
                                    + fromId 
                                    + ", " + toId
                                    + "]");
                        }

                        /*
                        // Too inefficient   
                        for (int delId : keySet)
                        {
                            if( !mDbAdaptor.deleteEntry(delId) )
                            {
                                Log.e(TAG, "Error deleting row ID =" +
                                        delId);
                            }
                        }
                        */

                    }
                    else
                    {
                        Log.e(TAG, "Post failed");
                        failCount++;
                    }
                    keySet.clear();
                }
                while ((!postResult) && (failCount < MAX_FAIL_COUNT));

                if (failCount >= MAX_FAIL_COUNT)
                {
                    Log.e(TAG, "Too many post failiurs. "
                            + "Will try at another time");

                    c.close();
                    mDbAdaptor.close();
                    SystemLogWakeLock.releaseCpuLock();
                    return;
                }

            }

            c.close();
            mDbAdaptor.close();
            SystemLogWakeLock.releaseCpuLock();
            return;
            
        }
        catch (Exception e)
        {
            Log.e(TAG, "Exception", e);
            if (c != null)
            {
                c.close();
                mDbAdaptor.close();
            }

            SystemLogWakeLock.releaseCpuLock();
        }



    }


    private boolean doPost(String content, String dest) 
    {

        //Log.i(TAG, "Uploading: " + content);
        OutputStream out;
        byte[] buff;
        int respCode;
        String respMsg = "";
        HttpURLConnection con;
        URL url;
        try
        {
            url = new URL(dest);
        }
        catch (MalformedURLException e)
        {
            Log.e(TAG, "Exception", e);
            return false;
        }


        try
        {
            con = (HttpURLConnection) url.openConnection();
        }
        catch (IOException e)
        {
            Log.e(TAG, "Exception", e);
            return false;
        }


        try
        {
            con.setRequestMethod("POST");
        }
        catch (java.net.ProtocolException e)
        {
            Log.e(TAG, "Exception", e);
            return false;
        }
        con.setUseCaches(false);
        con.setDoOutput(true);
        con.setDoInput(true);
        con.setRequestProperty("Content-type", 
                "application/x-www-form-urlencoded");

        try
        {
            con.connect();
            out = con.getOutputStream();
            buff = content.getBytes("UTF8");
            out.write(buff);
            out.flush();


            respMsg = con.getResponseMessage();
            respCode = con.getResponseCode();
        }
        catch (IOException e)
        {
            Log.e(TAG, "Exception", e);
            con.disconnect();
            return false;
        }



        if (respCode == HttpURLConnection.HTTP_OK)
        {
            con.disconnect();
            return true;
        }
        else
        {
            Log.e(TAG, "post failed with error: " 
                    + respMsg);
            con.disconnect();
            return false;
        }
    }


}
