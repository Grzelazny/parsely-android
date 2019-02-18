/*
    Copyright 2016 Parse.ly, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.parsely.parselyandroid;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.util.Calendar;
import java.util.Formatter;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.StringWriter;

import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.Settings.Secure;
import android.support.annotation.NonNull;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;

import org.codehaus.jackson.map.ObjectMapper;

/*! \brief Tracks Parse.ly app views in Android apps
*
*  Accessed as a singleton. Maintains a queue of pageview events in memory and periodically
*  flushes the queue to the Parse.ly pixel proxy server.
*/
public class ParselyTracker {
    private static ParselyTracker instance = null;
    private static int DEFAULT_FLUSH_INTERVAL = 60;
    private static int DEFAULT_ENGAGEMENT_INTERVAL_MILLIS = 10500;
    private static String DEFAULT_URLREF = "parsely_mobile_sdk";

    protected ArrayList<Map<String, Object>> eventQueue;

    private String apikey, rootUrl, storageKey, uuidkey, urlref, adKey;
    private boolean isDebug, flushTimerRunning;
    private SharedPreferences settings;
    private int queueSizeLimit, storageSizeLimit;
    private Map<String, String> deviceInfo;
    private Context context;
    private Timer timer;
    private FlushManager flushManager;
    private EngagementManager engagementManager, videoEngagementManager;

    // Here for backwards compat
    public int flushInterval;

    protected ParselyTracker(String apikey, int flushInterval, String urlref, Context c){
        this.context = c.getApplicationContext();
        this.settings = this.context.getSharedPreferences("parsely-prefs", 0);

        this.apikey = apikey;
        this.uuidkey = "parsely-uuid";
        this.adKey = null;
        // get the adkey straight away on instantiation
        new GetAdKey(c).execute();
        this.storageKey = "parsely-events.ser";
        this.rootUrl = "https://srv.pixel.parsely.com/";
        this.urlref = urlref;
        this.queueSizeLimit = 50;
        this.storageSizeLimit = 100;
        this.deviceInfo = this.collectDeviceInfo();
        this.timer = new Timer();
        this.isDebug = false;
        this.flushInterval = flushInterval;

        this.eventQueue = new ArrayList<>();

        this.flushManager = new FlushManager(this.timer, flushInterval * 1000);

        if(this.getStoredQueue() != null && this.getStoredQueue().size() > 0){
            this.setFlushTimer();
        }
    }

    public double getEngagementInterval() {
        return DEFAULT_ENGAGEMENT_INTERVAL_MILLIS;
    }

    public boolean engagementIsActive() {
        return this.engagementManager != null;
    }

    public boolean videoIsActive() {
        return this.videoEngagementManager != null;
    }


    /*! \brief Getter for this.isDebug
     */
    public boolean getDebug() {
        return isDebug;
    }

    /*! \brief Set a debug flag which will prevent data from being sent to Parse.ly

        Use this flag when developing to prevent the SDK from actually sending requests
        to Parse.ly servers.

        @param debug Value to use for debug flag.
     */
    public void setDebug(boolean debug) {
        isDebug = debug;
        PLog("Debugging is now set to " + isDebug);
    }


    /*! \brief Register a pageview event using a canonical URL
    *
    *  @param url The canonical URL of the article being tracked
    *  (eg: "http://samplesite.com/some-old/article.html")
    */
    public void trackURL(String url, ParselyMetadata urlMetadata){
        this.enqueueEvent(this.buildEvent(url, "pageview", urlMetadata));
    }

    public void startEngagement(String url) {
        PLog("startEngagement called");

        // Cancel anything running
        this.stopEngagement();

        // Start a new EngagementTask
        Map<String, Object> event = this.buildEvent(url, "heartbeat", null);
        this.engagementManager = new EngagementManager(this.timer, DEFAULT_ENGAGEMENT_INTERVAL_MILLIS, event);
        this.engagementManager.start();
    }

    public void stopEngagement() {
        if(this.engagementManager == null) {
            PLog("No ongoing engagement to stop.");
            return;
        }
        PLog("stopEngagement called");
        this.engagementManager.stop();
        this.engagementManager = null;
    }

    public void trackPlay(String url, @NonNull ParselyVideoMetadata videoMetadata) {
        PLog("trackPlay called");
        if (videoMetadata == null) {
            throw new NullPointerException("videoMetadata cannot be null.");
        }

        // If there is already an engagement manager for this video make sure it is started.
        if (this.videoEngagementManager != null) {
            if (this.videoEngagementManager.isSameVideo(url, videoMetadata)) {
                if (!this.videoEngagementManager.isRunning()) {
                    this.videoEngagementManager.start();
                }
                return; // all done here. early exit.
            } else {
                // Different video. Stop and remove it so we can start fresh.
                this.videoEngagementManager.stop();
                this.videoEngagementManager = null;
            }
        }

        // Enqueue the videostart
        this.enqueueEvent(this.buildEvent(url, "videostart", videoMetadata));

        // Start a new engagement manager for the video.
        Map<String, Object> hbEvent = this.buildEvent(url, "vheartbeat", videoMetadata);
        // TODO: Can we remove some metadata fields from this request?
        this.videoEngagementManager = new EngagementManager(this.timer, DEFAULT_ENGAGEMENT_INTERVAL_MILLIS, hbEvent);
        this.videoEngagementManager.start();
    }

    public void trackPause() {
        if(this.videoEngagementManager == null) {
            PLog("No ongoing video to stop.");
            return;
        }
        PLog("trackPause called");
        this.videoEngagementManager.stop();
    }

    public void resetVideo() {
        if(this.videoEngagementManager == null) {
            PLog("No ongoing video to reset.");
            return;
        }
        this.videoEngagementManager.stop();
        this.videoEngagementManager = null;
    }



    /*! \brief Create an event Map
     *
     *  @param url The canonical URL identifying the pageview/heartbeat
     *  @param action Action kind to use (e.g. pageview, heartbeat)
     */
    private Map<String, Object> buildEvent(String url, String action, ParselyMetadata metadata) {
        PLog("buildEvent called for %s", url);

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

        // Main event info
        Map<String, Object> event = new HashMap<>();
        event.put("url", url);
        event.put("urlref", this.urlref);
        event.put("idsite", this.apikey);
        event.put("action", action);
        event.put("ts", now.getTimeInMillis() / 1000);
        event.put("parsley_site_uuid", this.deviceInfo.get("uuid"));


        // Extra data Map
        Map<String, Object> data = new HashMap<>();
        data.put("manufacturer", this.deviceInfo.get("manufacturer"));
        data.put("os", this.deviceInfo.get("os"));
        data.put("os_version", this.deviceInfo.get("os_version"));
        event.put("data", data);

        if(metadata != null) {
            event.put("metadata", metadata.toMap());
        }

        return event;
    }

    /*! \brief Add an event Map to the queue.
     *
     *  Place a data structure representing the event into the in-memory queue for later use
     *
     *  **Note**: Events placed into this queue will be discarded if the size of the persistent queue
     *  store exceeds `storageSizeLimit`.
     */

    private void enqueueEvent(Map<String, Object> event){
        // Push it onto the queue
        PLog("%s", event);
        this.eventQueue.add(event);
        new QueueManager().execute();
        if(this.flushManager.isRunning() == false) {
            this.setFlushTimer();
            PLog("Flush flushTimer set to %ds", (this.flushManager.getIntervalMillis()/1000));
        }
    }

    /*!  \brief Generate pixel requests from the queue
     *
     *  Empties the entire queue and sends the appropriate pixel requests.
     *  Called automatically after a number of seconds determined by `flushInterval`.
     */
    public void flush() {
        // needed for call from MainActivity
        new FlushQueue().execute();
    }



    /*!  \brief Send the entire queue as a single request
    *
    *   Creates a large POST request containing the JSON encoding of the entire queue.
    *   Sends this request to the proxy server, which forwards requests to the pixel server.
    *
    *   @param queue The list of event dictionaries to serialize
    */
    private void sendBatchRequest(ArrayList<Map<String, Object>> events){
        PLog("Sending batched request of %d events", events.size());
        if (events == null || events.size() == 0) {
            return;
        }

        // Put in a Map for the proxy server
        Map<String, Object> batchMap = new HashMap<>();
        batchMap.put("events", events);

        PLog("Setting API connection");
        if (this.isDebug == true) {
            PLog("Debug mode on. Not sending to Parse.ly");
            this.eventQueue.clear();
            this.purgeStoredQueue();
        } else {
            new ParselyAPIConnection().execute(this.rootUrl + "mobileproxy", this.JsonEncode(batchMap));
            PLog("Requested %s", this.rootUrl);
        }
        PLog("POST Data %s", this.JsonEncode(batchMap));
    }

    private boolean isReachable(){
        ConnectivityManager cm = (ConnectivityManager)this.context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }

    private void persistQueue(){
        PLog("Persisting event queue");
        ArrayList<Map<String, Object>> storedQueue = this.getStoredQueue();
        if (storedQueue == null) {
            storedQueue = new ArrayList<>();
        }
        HashSet<Map<String, Object>> hs = new HashSet<>();
        hs.addAll(storedQueue);
        hs.addAll(this.eventQueue);
        storedQueue.clear();
        storedQueue.addAll(hs);
        this.persistObject(storedQueue);
    }

    private ArrayList<Map<String, Object>> getStoredQueue() {
        ArrayList<Map<String, Object>> storedQueue = new ArrayList<>();
        try{
            FileInputStream fis = this.context.getApplicationContext().openFileInput(
                    this.storageKey);
            ObjectInputStream ois = new ObjectInputStream(fis);
            //noinspection unchecked
            storedQueue = (ArrayList<Map<String, Object>>)ois.readObject();
            ois.close();
        } catch(EOFException ex) {
            PLog("");
        } catch (FileNotFoundException ex) {
            // Nothing to do here. Means there was no saved queue.
        } catch(Exception ex) {
            PLog("Exception thrown during queue deserialization: %s", ex.toString());
        }

        assert storedQueue != null;
        return storedQueue;
    }

    protected void purgeStoredQueue(){
        this.persistObject(null);
    }

    private void expelStoredEvent(){
        ArrayList<Map<String, Object>> storedQueue = this.getStoredQueue();
        storedQueue.remove(0);
    }

    @TargetApi(Build.VERSION_CODES.CUPCAKE)
    private void persistObject(Object o){
        try{
            FileOutputStream fos = this.context.getApplicationContext().openFileOutput(
                                       this.storageKey,
                                       android.content.Context.MODE_PRIVATE
                                   );
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(o);
            oos.close();
        } catch (Exception ex){
            PLog("Exception thrown during queue serialization: %s", ex.toString());
        }
    }

    private String JsonEncode(Map<String, Object> map){
        ObjectMapper mapper = new ObjectMapper();
        String ret = null;
        try {
            StringWriter strWriter = new StringWriter();
            mapper.writeValue(strWriter, map);
            ret = strWriter.toString();
          } catch (IOException e) {
            e.printStackTrace();
          }
        return ret;
    }

    /*! \brief Allow Parsely to send pageview events
    *
    *  Instantiates the callback flushTimer responsible for flushing the events queue.
    *  Can be called before of after `stop`, but has no effect if used before instantiating the
    *  singleton
    */
    public void setFlushTimer(){
        this.flushManager.start();
    }

    /*! \brief Is the callback flushTimer running
    *
    *  @return `true` if the callback flushTimer is currently running, `false` otherwise
    */
    public boolean flushTimerIsActive(){
        return this.flushManager.isRunning();
    }

    /*! \brief Disallow Parsely from sending pageview events
    *
    *  Invalidates the callback flushTimer responsible for flushing the events queue.
    *  Can be called before or after `start`, but has no effect if used before instantiating the
    *  singleton
    */
    public void stopFlushTimer(){
        this.flushManager.stop();
    }



    private String generateSiteUuid() {
        String uuid = Secure.getString(this.context.getApplicationContext().getContentResolver(),
                Secure.ANDROID_ID);
        PLog(String.format("Generated UUID: %s", uuid));
        return uuid;
    }

    private String getSiteUuid(){
        String uuid = "";
        try{
            uuid = this.settings.getString(this.uuidkey, "");
            if(uuid.equals("")){
                uuid = this.generateSiteUuid();
            }
        } catch(Exception ex){
            PLog("Exception caught during site uuid generation: %s", ex.toString());
        }
        return uuid;
    }

    private Map<String, String> collectDeviceInfo(){
        Map<String, String> dInfo = new HashMap<>();

        // TODO: screen dimensions (maybe?)
        PLog("adkey is: %s, uuid is %s", this.adKey, this.getSiteUuid());
        String uuid = (this.adKey != null) ? this.adKey : this.getSiteUuid();
        dInfo.put("parsely_site_uuid", uuid);
        dInfo.put("manufacturer", android.os.Build.MANUFACTURER);
        dInfo.put("os", "android");
        dInfo.put("os_version", String.format("%d", android.os.Build.VERSION.SDK_INT));

        // FIXME: Not passed in event or used anywhere else.
        CharSequence txt = this.context.getPackageManager().getApplicationLabel(context.getApplicationInfo());
        dInfo.put("appname", txt.toString());

        return dInfo;
    }

    /*! \brief Singleton instance accessor. Note: This must be called after
    sharedInstance(String, Context)
    *
    *  @return The singleton instance
    */
    public static ParselyTracker sharedInstance(){
        if(instance == null){
            return null;
        }
        return instance;
    }

    /*! \brief Singleton instance factory Note: this must be called before `sharedInstance()`
    *
    *  @param apikey The Parsely public API key (eg "samplesite.com")
    *  @param c The current Android application context
    *  @return The singleton instance
    */
    public static ParselyTracker sharedInstance(String apikey, Context c){
        return ParselyTracker.sharedInstance(apikey, DEFAULT_FLUSH_INTERVAL, DEFAULT_URLREF, c);
    }

    /*! \brief Singleton instance factory Note: this must be called before `sharedInstance()`
    *
    *  @param apikey The Parsely public API key (eg "samplesite.com")
    *  @param flushInterval The interval at which the events queue should flush, in seconds
    *  @param c The current Android application context
    *  @return The singleton instance
    */
    public static ParselyTracker sharedInstance(String apikey, int flushInterval, Context c){
        if(instance == null){
            instance = new ParselyTracker(apikey, flushInterval, DEFAULT_URLREF, c);
        }
        return instance;
    }

    /*! \brief Singleton instance factory Note: this must be called before `sharedInstance()`
    *
    *  @param apikey The Parsely public API key (eg "samplesite.com")
    *  @param flushInterval The interval at which the events queue should flush, in seconds
    *  @param urlref The referrer string to send with pixel requests
    *  @param c The current Android application context
    *  @return The singleton instance
    */
    public static ParselyTracker sharedInstance(String apikey, int flushInterval, String urlref, Context c){
        if(instance == null){
            instance = new ParselyTracker(apikey, flushInterval, urlref, c);
        }
        return instance;
    }

    public int queueSize(){ return this.eventQueue.size(); }
    public int storedEventsCount(){
        ArrayList<Map<String, Object>> ar = this.getStoredQueue();
        if(ar != null){
            return ar.size();
        }
        return 0;
    }

    protected static void PLog(String logstring, Object... objects){
        if (logstring.equals("")) {
            return;
        }
        System.out.println(new Formatter().format("[Parsely] " + logstring, objects).toString());
    }

    @TargetApi(Build.VERSION_CODES.CUPCAKE)
    public class QueueManager extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            ArrayList<Map<String, Object>> storedQueue = getStoredQueue();
            // if event queue is too big, push to persisted storage
            if (eventQueue.size() >= queueSizeLimit + 1) {
                PLog("Queue size exceeded, expelling oldest event to persistent memory");
                persistQueue();
                eventQueue.remove(0);
                // if persisted storage is too big, expel one
                if (storedQueue != null) {
                    if (storedEventsCount() > storageSizeLimit) {
                        expelStoredEvent();
                    }
                }
            }
            return null;
        }
    }

    public class FlushQueue extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            ArrayList<Map<String, Object>> storedQueue = getStoredQueue();
            PLog("%d events in queue, %d stored events", eventQueue.size(), storedEventsCount());
            // in case both queues have been flushed and app quits, don't crash
            if ((eventQueue == null || eventQueue.size() == 0) &&
                (storedQueue == null || storedQueue.size() == 0))
            {
                stopFlushTimer();
                return null;
            }
            if(!isReachable()){
                PLog("Network unreachable. Not flushing.");
                return null;
            }
            HashSet<Map<String, Object>> hs = new HashSet<>();
            ArrayList<Map<String, Object>> newQueue = new ArrayList<>();

            hs.addAll(eventQueue);
            if(storedQueue != null){
                hs.addAll(storedQueue);
            }
            newQueue.addAll(hs);
            PLog("Flushing queue");
            sendBatchRequest(newQueue);
            return null;
        }
    }

    public class GetAdKey extends AsyncTask<Void, Void, String> {
        private Context mContext;

        public GetAdKey(Context context) {
            mContext = context;
        }

        @Override
        protected String doInBackground(Void... params) {
            AdvertisingIdClient.Info idInfo = null;
            String advertId = null;
            try {
                idInfo = AdvertisingIdClient.getAdvertisingIdInfo(mContext);
            }
            catch (GooglePlayServicesRepairableException | IOException | GooglePlayServicesNotAvailableException e) {
                PLog("No Google play services or error! falling back to device uuid");
                // fall back to device uuid on google play errors
                advertId = getSiteUuid();
            }
            try {
                advertId = idInfo.getId();
            }
            catch (NullPointerException e) {
                advertId = getSiteUuid();
            }
            return advertId;
        }

        @Override
        protected void onPostExecute(String advertId) {
            adKey = advertId;
            deviceInfo.put("parsely_site_uuid", adKey);
        }

    };


    /*! \brief Manager for the event flush timer.
     *
     * Handles stopping and starting the flush timer. The flush timer
     * controls how often we send events to Parse.ly servers.
     */
    private class FlushManager {

        private Timer parentTimer;
        private long intervalMillis;
        private TimerTask runningTask;

        public FlushManager(Timer parentTimer, long intervalMillis) {
            this.parentTimer = parentTimer;
            this.intervalMillis = intervalMillis;
        }

        public void start() {
            if (this.runningTask != null) {
                return;
            }

            this.runningTask = new TimerTask(){
                public void run(){ flush(); }
            };
            this.parentTimer.scheduleAtFixedRate(this.runningTask, intervalMillis, intervalMillis);
        }

        public boolean stop() {
            if (this.runningTask == null) {
                return false;
            } else {
                boolean output = this.runningTask.cancel();
                this.runningTask = null;
                return output;
            }
        }

        public boolean isRunning() {
            return this.runningTask != null;
        }

        public long getIntervalMillis() {
            return this.intervalMillis;
        }
    }

    /*! \brief Engagement manager for article and video engagement.
     *
     * Implemented to handle its own queuing of future executions to accomplish
     * two things:
     *
     * 1. Flushing any engaged time before canceling.
     * 2. Progressive backoff for long engagements to save data.
     */
    private class EngagementManager {

        public Map<String, Object> baseEvent;
        private boolean started;
        private Timer parentTimer;
        private TimerTask waitingTimerTask;
        private long latestDelayMillis, totalTime;


        public EngagementManager(Timer parentTimer, long intervalMillis, Map<String, Object> baseEvent) {
            this.baseEvent = baseEvent;
            this.parentTimer = parentTimer;
            this.latestDelayMillis = intervalMillis;
            this.totalTime = 0;
        }

        public boolean isRunning() { return this.started; }

        public void start() {
            this.scheduleNextExecution(this.latestDelayMillis);
            this.started = true;
        }

        public void stop() {
            this.waitingTimerTask.cancel();
            this.started = false;

        }

        public boolean isSameVideo(String url, ParselyVideoMetadata metadata) {
            Map<String, Object> baseMetadata = (Map<String, Object>) baseEvent.get("metadata");
            return (baseEvent.get("url") == url &&
                    baseMetadata.get("canonical_url") == metadata.canonical_url &&
                    (int) (baseMetadata.get("duration")) == metadata.durationSeconds);
        }

        private void scheduleNextExecution(long delay) {
            TimerTask task = new TimerTask(){
                public void run(){
                    doEnqueue(this.scheduledExecutionTime());
                    updateLatestInterval();
                    scheduleNextExecution(latestDelayMillis);
                }

                public boolean cancel() {
                    doEnqueue(this.scheduledExecutionTime());
                    return super.cancel();
                }
            };
            PLog(String.format("latestDelayMillis: %d", delay));
            this.latestDelayMillis = delay;
            this.parentTimer.schedule(task, delay);
            this.waitingTimerTask = task;
        }

        private void doEnqueue(long scheduledExecutionTime) {
            // Create a copy of the base event to enqueue
            Map<String, Object> event = new HashMap(this.baseEvent);
            PLog(String.format("Enqueuing %s event.", event.get("action")));

            // Adjust inc by execution time in case we're late or early.
            long executionDiff = (System.currentTimeMillis() - scheduledExecutionTime);
            long inc = (this.latestDelayMillis + executionDiff) / 1000;
            this.totalTime += inc;
            event.put("inc", inc);
            event.put("tt", this.totalTime);

            enqueueEvent(event);
        }

        private void updateLatestInterval() {
            // Update latestDelayMillis to be used for next execution. The interval
            // increases by 25% for each successive call, up to a max of 90s, to cut down on
            // data use for very long engagements (e.g. streaming video).
            this.latestDelayMillis = (int) Math.min(90000, this.latestDelayMillis * 1.25);
        }
    }
}
