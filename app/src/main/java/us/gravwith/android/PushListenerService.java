package us.gravwith.android;


import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.android.gms.gcm.GcmListenerService;

import us.gravwith.android.util.LogUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.gravwith.android.SQLiteDbContract.MessageEntry;
import us.gravwith.android.SQLiteDbContract.LiveReplies;
import us.gravwith.android.util.Utility;

/**
 * A service that listens to GCM notifications.
 */
public class PushListenerService extends GcmListenerService {

    private static final String LOG_TAG = PushListenerService.class.getSimpleName();

    // Intent action used in local broadcast
    public static final String ACTION_SNS_NOTIFICATION = "sns-notification";
    // Intent keys
    public static final String INTENT_SNS_NOTIFICATION_FROM = "from";
    public static final String INTENT_SNS_NOTIFICATION_DATA = "data";

    /**
     * Helper method to extract SNS message from bundle.
     *
     * @param data bundle
     * @return message string from SNS push notification
     */
    public static String getMessage(Bundle data) {
        // If a push notification is sent as plain text, then the message appears in "default".
        // Otherwise it's in the "message" for JSON format.
        return data.containsKey("default") ? data.getString("default") : data.getString(
                "message", "");
    }

    private static boolean isForeground(Context context) {
        // Gets a list of running processes.
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> tasks = am.getRunningAppProcesses();

        // On some versions of android the first item in the list is what runs in the foreground,
        // but this is not true on all versions.  Check the process importance to see if the app
        // is in the foreground.
        final String packageName = context.getPackageName();
        for (ActivityManager.RunningAppProcessInfo appProcess : tasks) {
            if (ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND == appProcess.importance
                    && packageName.equals(appProcess.processName)) {
                return true;
            }
        }
        return false;
    }

    private void displayNotification(final String message) {
        if (Constants.LOGD) Log.d(LOG_TAG,"entering displayNotification : " + message);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.putExtra(Constants.IS_FROM_NOTIFICATION,true);
        notificationIntent.setFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int requestID = (int) System.currentTimeMillis();
        PendingIntent contentIntent = PendingIntent.getActivity(this, requestID, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // Display a notification with an icon, message as content, and default sound. It also
        // opens the app when the notification is clicked.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.push_notification_title))
                .setContentText(message)
                .setDefaults(Notification.DEFAULT_SOUND)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setLights(0xffed1085, 250, 250)
                .setContentIntent(contentIntent);



        NotificationManager notificationManager = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);

        notificationManager.notify(0, builder.build());

        if (Constants.LOGD) Log.d(LOG_TAG,"exiting displayNotification");
    }

    private void broadcast(final String from, final Bundle data) {
        if (Constants.LOGD) Log.d(LOG_TAG,"entering broadcast");

        Intent intent = new Intent(ACTION_SNS_NOTIFICATION);
        intent.putExtra(INTENT_SNS_NOTIFICATION_FROM, from);
        intent.putExtra(INTENT_SNS_NOTIFICATION_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        if (Constants.LOGD) Log.d(LOG_TAG,"exiting broadcast");
    }

    /**
     * Called when message is received.
     *
     * @param from SenderID of the sender.
     * @param data Data bundle containing message data as key/value pairs. For Set of keys use
     * data.keySet().
     */
    @Override
    public void onMessageReceived(final String from, final Bundle data) {
        if (Constants.LOGD) Log.d(LOG_TAG, "entering onMessageReceived... From : " + from);

        if (Constants.LOGV) LogUtils.printBundle(data, LOG_TAG);

        /*
        public static final String COLUMN_ID = "_ID";
        public static final String COLUMN_NAME_TIME = "time";
        public static final String COLUMN_FROM_USER = "fromUser";
        public static final String COLUMN_NAME_TEXT = "text";
        public static final String COLUMN_NAME_FILEPATH = "url";
         */

        /*
         * Get the message String, parse to json, and read as a map.
         */
        String message = getMessage(data);


        Map<String,String> jsonMap = new HashMap<>();
        try {
            JsonFactory jsonFactory = new JsonFactory();
            JsonParser jsonParser = jsonFactory.createParser(message);

            ObjectMapper objectMapper = new ObjectMapper();
            jsonMap = objectMapper.readValue(jsonParser,Map.class);

        } catch (com.fasterxml.jackson.core.JsonParseException pe) {
            Log.e(LOG_TAG,"failed to parse incoming json... ",pe);
        } catch (IOException e) {
            Log.e(LOG_TAG,"IOException parsing JSON...",e);
        }
        if (jsonMap != null) {
            if (Constants.LOGV) {
                Log.v(LOG_TAG, "mapping succeeded... continuing...");
                LogUtils.printStringMapToVerbose(jsonMap, LOG_TAG);
            }

            switch (jsonMap.get(Constants.KEY_S3_DIRECTORY)) {

                case Constants.KEY_S3_MESSAGE_DIRECTORY:

                    if (jsonMap.containsKey(MessageEntry.COLUMN_NAME_FILEPATH)) {
                        Log.i(LOG_TAG,"received incoming message, storing...");

                        ContentValues values = new ContentValues(data.size());
                        //values.put(SQLiteDbContract.LiveReplies.COLUMN_ID, data.getString("id"));
                        values.put(MessageEntry.COLUMN_NAME_TIME, jsonMap.get("time"));
                        values.put(MessageEntry.COLUMN_FROM_USER, jsonMap.get("fromUser"));
                        values.put(MessageEntry.COLUMN_NAME_TEXT, jsonMap.get("text"));
                        values.put(MessageEntry.COLUMN_NAME_FILEPATH, jsonMap.get("url"));
                        values.put(MessageEntry.COLUMN_RESPONSE_ARN, jsonMap.get("arn"));
                        //values.put("url", jsonMap.get("default"));

                        getContentResolver().insert(FireFlyContentProvider.CONTENT_URI_MESSAGE, values);

                        if (isForeground(this)) {
                            // broadcast notification, then store
                            broadcast(from, data);
                        } else {
                            //just store and display
                            displayNotification("You've got mail!");
                        }
                    } else {
                        Log.i(LOG_TAG, "received read receipt, removing column for pending images");
                        String[] selectionArgs = { jsonMap.get(MessageEntry.COLUMN_RESPONSE_ARN) };

                        int rows = getContentResolver().
                                delete(FireFlyContentProvider.CONTENT_URI_MESSAGE,
                                        MessageEntry.COLUMN_RESPONSE_ARN + " LIKE ?",
                                        selectionArgs);

                        if (Constants.LOGD) Log.v(LOG_TAG,"Rows deleted : " + rows);

                        if (isForeground(this)) {
                            // broadcast notification, then store
                            //broadcast(from, data);
                        }
                    }

                    break;

                case Constants.KEY_S3_REPLIES_DIRECTORY :

                    Log.i(LOG_TAG,"received incoming reply, storing...");

                    ContentValues values = new ContentValues(data.size());
                    //values.put(SQLiteDbContract.LiveReplies.COLUMN_ID, data.getString("id"));
                    values.put(LiveReplies.COLUMN_ID, jsonMap.get(LiveReplies.COLUMN_ID));
                    values.put(LiveReplies.COLUMN_NAME_DESCRIPTION,
                            jsonMap.get(LiveReplies.COLUMN_NAME_DESCRIPTION));
                    values.put(LiveReplies.COLUMN_NAME_THREAD_ID, jsonMap.get(LiveReplies.COLUMN_NAME_THREAD_ID));
                    values.put(LiveReplies.COLUMN_NAME_TIME,
                            jsonMap.get(LiveReplies.COLUMN_NAME_TIME));
                    values.put(LiveReplies.COLUMN_NAME_NAME,
                            jsonMap.get(LiveReplies.COLUMN_NAME_NAME));
                    values.put(LiveReplies.COLUMN_NAME_FILEPATH,
                            jsonMap.get(LiveReplies.COLUMN_NAME_FILEPATH));

                    if (jsonMap.containsKey(LiveReplies.COLUMN_NAME_FROM_ARN)) { //todo, remove this
                        values.put(LiveReplies.COLUMN_NAME_FROM_ARN,
                                jsonMap.get(LiveReplies.COLUMN_NAME_FROM_ARN));
                    }

                    getContentResolver().insert(FireFlyContentProvider.CONTENT_URI_REPLY_LIST,
                            values);
                    if (isForeground(this)) {
                        // broadcast notification, then store
                        //broadcast(from, data);
                    } else {
                        //just store and display
                        displayNotification("Someone has replied to your thread!");
                    }

                    break;

                default:

                    Log.e(LOG_TAG,"no s3 directory object provided!");

            }


        }
        // Display a notification in the notification center if the app is in the background.
        // Otherwise, send a local broadcast to the app and let the app handle it.
       /* if (isForeground(this)) {
            // broadcast notification, then store
            broadcast(from, data);
        } else {
            //just store and display
            displayNotification(message);
        }*/

        if (Constants.LOGD) Log.d(LOG_TAG, "exiting onMessageReceived...");
    }
}