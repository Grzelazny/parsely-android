package com.parsely.parselyandroid;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;

import android.os.AsyncTask;

public class ParselyAPIConnection extends AsyncTask<String, Exception, URLConnection> {

    public Exception exception;

    @Override
    protected URLConnection doInBackground(String... data) {
        URLConnection connection = null;
        try{
            if(data.length == 1){  // non-batched (since no post data is included)
                connection = new URL(data[0]).openConnection();
                InputStream response = connection.getInputStream();
            } else if(data.length == 2){  // batched (post data included)
                connection = new URL(data[0]).openConnection();
                connection.setDoOutput(true);  // Triggers POST (aka silliest interface ever)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                OutputStream output = connection.getOutputStream();

                String query = String.format("rqs=%s", URLEncoder.encode(data[1]));
                output.write(query.getBytes());
                output.close();
                
                InputStream response = connection.getInputStream();
            }
            
        } catch (Exception ex){
            this.exception = ex;
            return null;
        }
        return connection;
    }

    protected void onPostExecute(URLConnection conn){
        if(this.exception != null){
            ParselyTracker.PLog("Pixel request exception");
            ParselyTracker.PLog(this.exception.toString());
        } else {
            ParselyTracker.PLog("Pixel request success");
            
            // only purge the queue if the request was successful
            ParselyTracker.sharedInstance().eventQueue.clear();
            ParselyTracker.sharedInstance().purgeStoredQueue();
            
            if(ParselyTracker.sharedInstance().queueSize() == 0 && ParselyTracker.sharedInstance().storedEventsCount() == 0){
                ParselyTracker.PLog("Event queue empty, flush timer cleared.");
                ParselyTracker.sharedInstance().stopFlushTimer();
            }
        }
    }
}