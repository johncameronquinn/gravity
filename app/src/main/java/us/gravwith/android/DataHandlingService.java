package us.gravwith.android;

import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.mobile.AWSConfiguration;
import com.amazonaws.mobile.AWSMobileClient;
import com.amazonaws.mobile.content.ContentItem;
import com.amazonaws.mobile.content.UserFileManager;
import com.amazonaws.mobile.content.ContentProgressListener;
import com.amazonaws.mobile.user.IdentityManager;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.AnalyticsEvent;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.EventClient;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.MobileAnalyticsManager;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.channels.NotYetConnectedException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import us.gravwith.android.user.AuthenticationManager;
import us.gravwith.android.util.LogUtils;
import us.gravwith.android.SQLiteDbContract.StashEntry;

/**
 * Author/Copyright John C. Quinn, All Rights Reserved.
 * date 4/26/2015
 * <p/>
 * service 'DataHandlingService'
 * <p/>
 * asynchronously serves requests to download new images from the server
 * 'black boxed' - proves the server a gps location, and number of images requested
 * and receives requested number, then stores them locally
 * <p/>
 * when no images are stored, a higher priority is given to the request, due to the user
 * currently being at "0" images
 * <p/>
 * Communication is managed via a broadcastManager
 */
public class DataHandlingService extends Service implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, LocationListener, TransferListener,
        ContentProgressListener {
    private static final String TAG = "DataHandlingService";
    private static final boolean VERBOSE = true;
    private static final boolean ALLOW_DUPLICATES = false;

    /**
     * NETWORK COMMUNICATION
     */
    private static GoogleApiClient mGoogleApiClient;

    /**
     * DATA HANDLING
     */
    private static Location mLocation;
    private static List<String> imagesSeen = Collections.synchronizedList(new ArrayList<String>());
    private static final String IMAGESSEEN_KEY = "images";
    private final String ISFIRSTRUN_KEY = "firstrun";
    private final String UUID_KEY = "uuidkey";
    private final String ANDROID_ID = "androidid";
    private static final int OLDEST_ALLOWED_IMAGE = 3600 * 1000; // two hours

    /**
     * SECURITY
     */
    private AuthenticationManager authenticationManager;

    /**
     *  AWS (AMAZON WEB SERVICE S3)
     */
    static TransferUtility transferUtility;
    HashMap<Integer,Bundle> pendingMap = new HashMap<>();
    private final String REQUEST_TYPE = "what";

    private MobileAnalyticsManager mTracker;
    private String androidId;
    private UserFileManager userFileManager;

    /**
     * Thread management
     */
    // A queue of Runnables for the image download pool
    private final BlockingQueue<Runnable> mConnectionWorkQueue;

    // A queue of Runnables for the image decoding pool
    private final BlockingQueue<Runnable> mRequestWorkQueue;

    // A queue of PhotoManager tasks. Tasks are handed to a ThreadPool.
    private final Queue<ServerTask> mServerTaskWorkQueue;

    // A managed pool of background download threads
    private final ThreadPoolExecutor mConnectionThreadPool;

    // A managed pool of background decoder threads
    private final ThreadPoolExecutor mRequestThreadPool;

    // The time unit for "keep alive" is in seconds
    public static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

    // Sets the amount of time an idle thread will wait for a task before terminating
    private static final int KEEP_ALIVE_TIME = 1;

    // Sets the initial threadpool size to 8
    private static final int CORE_POOL_SIZE = 8;

    // Sets the maximum threadpool size to 8
    private static final int MAXIMUM_POOL_SIZE = 8;

    private final ServiceHandlerThread serviceThread = new ServiceHandlerThread(this);

    public DataHandlingService() {

        /*
         * Creates a work queue for the pool of Thread objects used for downloading, using a linked
         * list queue that blocks when the queue is empty.
         */
        mConnectionWorkQueue = new LinkedBlockingQueue<>();

        /*
         * Creates a work queue for the pool of Thread objects used for decoding, using a linked
         * list queue that blocks when the queue is empty.
         */
        mRequestWorkQueue = new LinkedBlockingQueue<>();

        /*
         * Creates a work queue for the set of of task objects that control downloading and
         * decoding, using a linked list queue that blocks when the queue is empty.
         */
        mServerTaskWorkQueue = new LinkedBlockingQueue<>();

        /*
         * Creates a new pool of Thread objects for the download work queue
         */
        mConnectionThreadPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT, mConnectionWorkQueue);

        /*
         * Creates a new pool of Thread objects for the decoding work queue
         */
        mRequestThreadPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT, mRequestWorkQueue);
    }

    /**
     * method 'onCreate'
     * <p/>
     * lifeCycle method, called when the service is created, runs in the main process
     */
    @Override
    public void onCreate() {
        if (VERBOSE) Log.v(TAG, "entering onCreate...");

        AWSMobileClient.initializeGravityMobileClientIfNecessary(this);

        mTracker = AWSMobileClient.defaultMobileClient().getMobileAnalyticsManager();
        authenticationManager = AWSMobileClient.defaultMobileClient().getAuthenticationManager();
        SharedPreferences settings = getSharedPreferences(TAG, MODE_PRIVATE);
        boolean isFirstRun = settings.getBoolean(ISFIRSTRUN_KEY, true);

        if (isFirstRun) {
            Log.i(TAG, "the app is opening for the first time");
            androidId = Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID);

            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean(ISFIRSTRUN_KEY, false);
            editor.putString(ANDROID_ID, androidId);
            editor.apply();

        } else {
            Log.d(TAG, "loading androidID from storage...");
            androidId = settings.getString(ANDROID_ID, null);
        }
        authenticationManager.loadUserID(this);

        if (!ALLOW_DUPLICATES) {
            if (VERBOSE) Log.v(TAG, "Duplicates are not allowed, loading imagesSeen");
            Set<String> loaded = settings.getStringSet(IMAGESSEEN_KEY, null);
            if (loaded == null) {
                imagesSeen = new ArrayList<>();
            } else {
                imagesSeen = new ArrayList<>(loaded);
            }
        } else {
            if (VERBOSE) Log.v(TAG, "Duplicates are allowed, not loaded imagesSeen");
            imagesSeen = new ArrayList<>();
        }

        if (VERBOSE) Log.v(TAG, "exiting onCreate...");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (VERBOSE) {
            Log.v(TAG, "entering onDestroy...");
            Log.v(TAG, "saving ImagesSeen to sharedPreferences, size: " + imagesSeen.size());
        }

        SharedPreferences settings = getSharedPreferences(TAG, MODE_PRIVATE);
        settings.edit().putStringSet(IMAGESSEEN_KEY, new HashSet<>(imagesSeen)).apply();
        cancelAll();

        Log.v(TAG, "exiting onDestroy...");
    }

    public void initializeTransferUtility() {

        if (VERBOSE) Log.v(TAG, "creating initializeTransferUtility...");
        AWSMobileClient.defaultMobileClient().
                createUserFileManager(new UserFileManager.BuilderResultHandler() {
                    @Override
                    public void onComplete(final UserFileManager userFileManager) {
                        Log.v(TAG, "ContentManager created!");
                        DataHandlingService.this.userFileManager = userFileManager;
                        userFileManager.setContentCacheSize(1024 * 1024 * 20);
                    }
                });


        if (VERBOSE) Log.v(TAG,"exiting initializeTransferUtility...");
    }

    public List<String> getImagesSeen() {
        return imagesSeen;
    }

    public void insert(Uri uri, ContentValues values) {
       getContentResolver().insert(uri, values);
    }

    public void delete(Uri uri, ContentValues values) {
        getContentResolver().delete(uri,null,null);
    }

    public void submitToConnectionPool(Runnable r) {
        mConnectionThreadPool.execute(r);
    }

    /***********************************************************************************************
     * SERVICE - ACTIVITY COMMUNICATION
     */

    class ServiceHandlerThread extends HandlerThread {
        private IncomingHandler incomingHandler;

        ServiceHandlerThread(DataHandlingService parent) {
            super("ServiceHandlerThread");
            start();
            incomingHandler = new IncomingHandler(getLooper()).setParent(parent);
        }

        public IncomingHandler getIncomingHandler() {
            return incomingHandler;
        }
    }

    private Messenger replyMessenger;

    public void setReplyMessenger(Messenger messenger) {
        replyMessenger = messenger;
    }

    static final int MSG_BUILD_CLIENT = 1;

    static final int MSG_CONNECT_CLIENT = 2;

    static final int MSG_DISCONNECT_CLIENT = 3;

    static final int MSG_REQUEST_CONSTANT_UPDATES = 4;

    static final int MSG_SEND_IMAGE = 5;

    static final int MSG_SEND_MESSAGE = 19;

    static final int MSG_REQUEST_LOCAL_POSTS = 6;

    static final int MSG_RESOLVE_HOST = 7;

    static final int MSG_DELETE_IMAGE = 8;

    static final int MSG_REQUEST_MESSAGES = 9;

    static final int MSG_BLOCK_USER = 10;

    static final int MSG_CREATE_THREAD = 11;

    static final int MSG_REPLY_TO_THREAD = 12;

    static final int MSG_REQUEST_LIVE_THREADS = 13;

    static final int MSG_REQUEST_REPLIES = 15;

    static final int MSG_SET_CALLBACK_MESSENGER = 16;

    static final int MSG_DOWNLOAD_IMAGE = 18;

    static final int MSG_REPORT_ANALYTICS= 100;

    static final int MSG_REPORT_ANALYTIC_TIMING = 101;

    static final int MSG_REPORT_ANALYTIC_ERROR = 102;

    static final int MSG_REPORT_ANALYTIC_SCREEN = 103;

    static final int MSG_UPLOAD_IMAGE = 19;

    static final int MSG_REPORT_CONTENT = 21;

    static final int MSG_AUTHORIZE_USER = 22;

    static final int MSG_SUBSCRIBE_TO_TOPIC = 23;

    static final int MSG_UNSUBSCRIBE_FROM_TOPIC = 24;


    /**
     * class 'IncomingHandler'
     * <p/>
     * Handler of incoming messages from clients.
     * location updates are managed in the current thread, sending and receiving images
     * is handled in a new thread
     * <p/>
     * references the parent service (weakly) in order to prevent memory leaks
     */
    class IncomingHandler extends Handler implements AuthenticationManager.LoginListener {
        WeakReference<DataHandlingService> irs;

        private boolean isAuthenticated = false;

        public IncomingHandler setParent(DataHandlingService parent) {
            irs = new WeakReference<>(parent);
            return this;
        }

        public IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "enter handleMessage");

            if (BuildConfig.FLAVOR.equals("sales") || Constants.client_only_mode) {
                Log.v(TAG,"Flavor outputted is : " + BuildConfig.FLAVOR);
                Log.w(TAG,"client only mode is enabled, discarding request...");

                super.handleMessage(msg);
                return;
            }

            ServerTask task = null;

            Bundle data = msg.getData();
            data.putInt(REQUEST_TYPE, msg.what);

            String key;
            File file;

            switch (msg.what) {
                case MSG_BUILD_CLIENT:
                    Log.d(TAG, "Received a message to request to build the client.");
                    Log.w(TAG, "Not implemented...");
                    //irs.get().buildGoogleApiClient();
                    break;

                case MSG_CONNECT_CLIENT:
                    Log.d(TAG, "Received a message to request to connect the client.");
                    Log.w(TAG, "Not implemented...");
                    /*if (mGoogleApiClient == null) {
                        irs.get().buildGoogleApiClient();
                    }
                    mGoogleApiClient.connect();*/
                    break;

                case MSG_DISCONNECT_CLIENT:
                    Log.d(TAG, "Received a message to request to disconnect the client.");
                    Log.w(TAG, "Not implemented...");
                    //mGoogleApiClient.disconnect();
                    break;

                case MSG_REQUEST_CONSTANT_UPDATES:
                    Log.d(TAG, "Received a message to request constant location updates.");
                    Log.w(TAG, "Not implemented...");
                    //irs.get().createLocationRequest(msg.arg1, msg.arg2);
                    break;

                case MSG_SET_CALLBACK_MESSENGER:
                    Log.d(TAG, "setting callback messenger...");
                    irs.get().setReplyMessenger(msg.replyTo);
                    break;
            }


            /* only receive and handle messages IF the service has authenticated */
            if (isAuthenticated) {

                switch (msg.what) {

                    case MSG_SEND_IMAGE:
                        Log.d(TAG, "request to send an image received");

                        String messageTarget = data.getString(Constants.MESSAGE_TARGET);
                        if (messageTarget == null) {
                            Log.d(TAG, "posting to local...");
                            data.putInt(REQUEST_TYPE, MSG_SEND_IMAGE);
                            data.putString(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_LOCAL_DIRECTORY);
                        } else {
                            Log.d(TAG, "sending Message to user: " + messageTarget);
                            data.putInt(REQUEST_TYPE, MSG_SEND_MESSAGE);
                            data.putString(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_MESSAGE_DIRECTORY);
                        }

                        uploadImageToS3(data);
                        break;

                    case MSG_CREATE_THREAD:
                        Log.d(TAG, "received a message to create a thread");
                    /*data.putInt(REQUEST_TYPE, MSG_CREATE_THREAD);
                    data.putString(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_LIVE_DIRECTORY);
                    uploadImageToS3(data);*/

                        key = data.getString(Constants.KEY_S3_KEY);
                        if (!key.contains("/")) {
                            if (VERBOSE) Log.v(TAG,
                                    "File does not contain a path separator, assuming cache directory...");
                            file = new File(getCacheDir(), key);
                        } else {
                            if (VERBOSE) Log.v(TAG,
                                    "file contains a path separator... uploading directly..");
                            file = new File(key);
                        }

                        data.putInt(REQUEST_TYPE, MSG_CREATE_THREAD);
                        data.putString(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_LIVE_DIRECTORY);
                        task = new SendLivePostTask();
                        ((SendLivePostTask)task).setFileManager(userFileManager);
                        ((SendLivePostTask)task).setImageFile(file);
                        break;

                    case MSG_DELETE_IMAGE:
                        Log.d(TAG, "received a message to delete image from database");
                        throw new NotYetConnectedException();

                    case MSG_BLOCK_USER:
                        Log.d(TAG, "received a message to block a user");
                        task = new SendLocalBlockTask();
                        break;

                    case MSG_REPLY_TO_THREAD:
                        Log.d(TAG, "received a message to reply to a thread");

                        data.putInt(REQUEST_TYPE, MSG_REPLY_TO_THREAD);
                        data.putString(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_REPLIES_DIRECTORY);

                        if (data.getString(Constants.KEY_S3_KEY, "").equals("")) {
                            if (Constants.LOGD) Log.d(TAG, "no image filepath was provided," +
                                    " this must be a text reply, so uploading straight to the server.");

                            if (Constants.LOGD) Log.d(TAG, "publishing first to GCM");
                            boolean success = true;
                            try {
                                //publish first to GCM
                                AWSMobileClient.defaultMobileClient().getPushManager().publishToTopic(data);
                            } catch (Exception e) {
                                Log.e(TAG, "Error in publishing reply to topic...", e);
                                success = false;
                            }

                            if (success) {
                                if (Constants.LOGD) Log.d(TAG, "now creating HTTP task");
                                //publishing reply to http
                                task = new SendReplyTask();
                            } else {
                                //todo give user feedback on failed post
                                if (Constants.LOGD)
                                    Log.d(TAG, "sending text reply to server anyway");
                                task = new SendReplyTask();
                            }

                        } else {
                            if (Constants.LOGD)
                                Log.d(TAG, "contained an image filepath, uploading " +
                                        "the image there to s3 first...");

                            data.putString(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_REPLIES_DIRECTORY);
                            uploadImageToS3(data);
                        }
                        break;

                    case MSG_REQUEST_LOCAL_POSTS:
                        if (Constants.LOGD) Log.d(TAG, "received a message to request local posts");
                        task = new RequestLocalTask();
                        if (mLocation != null) {
                            data.putDouble(SQLiteDbContract.LocalEntry.COLUMN_NAME_LONGITUDE,
                                    mLocation.getLongitude());
                            data.putDouble(SQLiteDbContract.LocalEntry.COLUMN_NAME_LATITUDE,
                                    mLocation.getLatitude());
                        }
                        break;

                    case MSG_REQUEST_MESSAGES:
                        Log.d(TAG, "received a message to message a user");

                        task = new RequestMessageTask();
                        break;

                    case MSG_REQUEST_LIVE_THREADS:
                        Log.d(TAG, "received a message to request the thread list");
                        task = new RequestLiveTask();
                        break;

                    case MSG_REQUEST_REPLIES:
                        Log.d(TAG, "received a message to request replies.");
                        task = new RequestRepliesTask();
                        break;

                    case MSG_DOWNLOAD_IMAGE:
                        Log.d(TAG, "received a message to download an image...");
                        downloadImageFromS3(data);
                        break;

                    case MSG_UPLOAD_IMAGE:
                        Log.d(TAG, "received a message to upload an image...");
                        uploadImageToS3(data);
                        break;

                    case MSG_REPORT_ANALYTICS:
                        if (Constants.LOGD)
                            Log.d(TAG, "Received a message to report analytics events");
                        reportAnalyticsEvent(msg.getData());
                        break;

                    case MSG_REPORT_ANALYTIC_TIMING:
                        if (Constants.LOGD)
                            Log.d(TAG, "Received a message to report timing events");
                        reportAnalyticsTimingEvent(msg.getData());
                        break;

                    case MSG_REPORT_ANALYTIC_ERROR:
                        if (Constants.LOGD) Log.d(TAG, "Received a message to report an error");
                        reportAnalyticsError(data);
                        break;

                    case MSG_REPORT_ANALYTIC_SCREEN:
                        if (Constants.LOGD)
                            Log.d(TAG, "Received a message to set the current screen");
                        setAnalyticsFragment(data);
                        break;

                    case MSG_REPORT_CONTENT:
                        if (Constants.LOGD) Log.d(TAG, "Received a message to report content.");
                        task = new ReportContentTask(msg.replyTo);
                        break;

                    case MSG_AUTHORIZE_USER:
                        if (Constants.LOGD) Log.d(TAG, "Received a message to initialize a user.");

                        Log.w(TAG, "users are no longer authorized using this method!");
                    /*task = new InitializeUserTask();
                    task.initializeTask(irs.get(), null, userID);
                    mConnectionThreadPool.execute(task.getServerConnectRunnable());*/
                        Log.d(TAG, "exit handleMessage...");
                        return;

                    case MSG_SUBSCRIBE_TO_TOPIC:
                        if (Constants.LOGD)
                            Log.d(TAG, "Received a message to subscribe to a topic.");

                        try {
                            AWSMobileClient.defaultMobileClient()
                                    .getPushManager()
                                    .subscribeToTopicByArn(
                                            data.getString(
                                                    SQLiteDbContract.LiveEntry.COLUMN_NAME_TOPIC_ARN
                                            )
                                    );
                        } catch (InvalidParameterException e) {
                            Log.e(TAG, "error subscribing to topic...", e);
                        } catch (AmazonClientException e) {
                            Log.e(TAG, "error subscribing to topic...", e);
                        }
                        return;

                    case MSG_UNSUBSCRIBE_FROM_TOPIC:
                        if (Constants.LOGD)
                            Log.d(TAG, "Received a message to unsubscribe from a topic.");

                        try {
                            AWSMobileClient.defaultMobileClient()
                                    .getPushManager()
                                    .unsubscribeFromTopicByTopicARN(
                                            data.getString(
                                                    SQLiteDbContract.LiveEntry
                                                            .COLUMN_NAME_TOPIC_ARN
                                            )
                                    );
                        } catch (InvalidParameterException e) {
                            Log.e(TAG, "error unsubscribing from topic...", e);
                        } catch (AmazonClientException e) {
                            Log.e(TAG, "error unsubscribing from topic...", e);
                        }

                        break;

                    default:
                        super.handleMessage(msg);
                }

                if (task != null && AuthenticationManager.getCurrentAccessToken() != null) {
                    task.initializeTask(irs.get(), data, AuthenticationManager.getCurrentAccessToken(), msg.what);

                    if (task.getUploadRunnable() != null) {
                        mConnectionThreadPool.execute(task.getUploadRunnable());
                    } else {
                        mConnectionThreadPool.execute(task.getServerConnectRunnable());
                    }
                }
            } else {
                Log.v(TAG,"client was not authenticated!");
            }


            Log.d(TAG, "exit handleMessage...");
        }

        @Override
        public void onLoginFailed() {
            if (VERBOSE) Log.v(TAG,"Login failed.");
            isAuthenticated = false;
        }

        @Override
        public void onLoginSuccess() {
            if (VERBOSE) Log.v(TAG,"successful login, now requesting live threads.");

            isAuthenticated = true;

            ServerTask task = new RequestLiveTask();
            task.initializeTask(irs.get(),
                    new Bundle(),
                    AuthenticationManager.getCurrentAccessToken(),
                    MSG_REQUEST_LIVE_THREADS
            );

            mConnectionThreadPool.execute(task.getServerConnectRunnable());
        }
    }

    final Messenger mMessenger = new Messenger(serviceThread.getIncomingHandler());

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "client is binding to the Service");

        initializeTransferUtility();

        AuthenticationManager.addLoginStatusListener(serviceThread.getIncomingHandler());
        AuthenticationManager.createNewUser(authenticationManager);

        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "client is unbinding from the Service");
        return super.onUnbind(intent);
    }

    /**********************************************************************************************
     * Task Callbacks and ThreadPooling
     *
     * these methods perform functions related to the threadpools and their respective servertasks
     */

    static final int CONNECTION_FAILED = 0;
    static final int CONNECTION_STARTED = 1;
    static final int CONNECTION_COMPLETED = 2;
    static final int REQUEST_FAILED = 3;
    static final int REQUEST_STARTED = 4;
    static final int REQUEST_COMPLETED = 5;
    static final int TASK_COMPLETED = 6;
    static final int TOPIC_CREATION_STARTED = 7;
    static final int TOPIC_CREATION_FAILED = 8;
    static final int TOPIC_CREATED = 9;
    static final int INITIALIZE_TASK_COMPLETED = 10;
    static final int IMAGE_UPLOAD_STARTED = 11;
    static final int IMAGE_UPLOAD_COMPLETED = 12;
    static final int IMAGE_UPLOAD_FAILED= 13;

    /**
     * method 'handleDownloadState'
     *
     * handles the responses passed upwards from each respective serverTask
     *
     * @param state the state of the content being downloaded
     */
    public void handleDownloadState(int state, ServerTask task) {
        int responseCode = -5;
        Message responseMessage;

        switch (state) {
            case IMAGE_UPLOAD_FAILED:
                if (Constants.LOGD) Log.d(TAG, "Image Upload Failed...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                break;

            case IMAGE_UPLOAD_STARTED:
                if (Constants.LOGD) Log.d(TAG, "Image Upload Started...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                break;

            case IMAGE_UPLOAD_COMPLETED:
                if (Constants.LOGD) Log.d(TAG, "Image Upload Completed...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                mConnectionThreadPool.execute(task.getOtherRunnable());
                break;

            case CONNECTION_FAILED:
                if (Constants.LOGD) Log.d(TAG, "Connection failed...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, task.getResponseCode());
                recycleTask(task);
                break;

            case CONNECTION_STARTED:
                if (Constants.LOGD) Log.d(TAG, "Connection started...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                break;

            case CONNECTION_COMPLETED:
                if (Constants.LOGD) Log.d(TAG, "Connection completed...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                mRequestThreadPool.execute(task.getRequestRunnable());
                break;

            case REQUEST_FAILED:
                if (Constants.LOGD) Log.d(TAG, "Request failed...");

                responseCode = task.getResponseCode();
                Log.i(TAG,"Task failed, grabbed responseCode is: " + responseCode);

                responseMessage = Message.obtain(null, task.getResponseWhat(), state, responseCode);

                sendErrorBroadcast(responseCode,
                        getResources().getString(R.string.authorization_error)
                );

                recycleTask(task);
                break;

            case REQUEST_STARTED:
                if (Constants.LOGD) Log.d(TAG,"Request started...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                break;

            case REQUEST_COMPLETED:
                if (Constants.LOGD) Log.d(TAG,"Upload completed...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                mRequestThreadPool.execute(task.getResponseRunnable());
                break;

            case TOPIC_CREATION_STARTED:
                if (Constants.LOGD) Log.d(TAG,"Topic creation stared...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);
                break;

            case TOPIC_CREATION_FAILED:
                if (Constants.LOGD) Log.d(TAG,"Topic failed to create...");

                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);

                sendErrorBroadcast(-1,"Topic failed to create");

                recycleTask(task);
                break;

            case TOPIC_CREATED:
                if (Constants.LOGD) Log.d(TAG,"Topic successfully created...");
                responseMessage = Message.obtain(null, task.getResponseWhat(), state, 0);

                //now run the http request, which is stored in otherRunnable in this case
                mConnectionThreadPool.execute(task.getServerConnectRunnable());
                break;

            case TASK_COMPLETED:

                responseCode = task.getResponseCode();
                if (VERBOSE) Log.v(TAG,"Task complete, grabbed responseCode is: " + responseCode);

                responseMessage = Message.obtain(null, task.getResponseWhat(), state, responseCode);
                recycleTask(task);

                if (task instanceof RequestLiveTask) {
                    if (Constants.LOGD) Log.d(TAG, "Completed live refresh task, " +
                            "now requesting replies for the thread at position 0");
                    requestRepliesforFirstThread();
                }

                if (Constants.LOGD) Log.d(TAG, "Task completed... by name: " + task.toString());
                break;


            default:
                throw new RuntimeException("Invalid State received in DataHandlingService");
        }

        if (responseMessage != null && replyMessenger != null) {
            try {
                replyMessenger.send(responseMessage);
            } catch (RemoteException e) {
                Log.e(TAG, "error responding to UI listeners...", e);
            } catch (Exception e) {
            }
                /* catch (NullPointerException e) {
                Log.e(TAG, "replyMessenger was null... this is bad. Handle this.", e);
                //// FIXME: 11/29/15 this nullpointerexception should never, ever, occur
            }*/
        }

    }

    /**
     * Recycles tasks by calling their internal recycle() method and then putting them back into
     * the task queue.
     * @param task The task to recycle
     */
    void recycleTask(ServerTask task) {
        if (Constants.LOGV)  Log.v(TAG,"entering recycleTask...");
        // Frees up memory in the task
        task.recycle();

        // Puts the task object back into the queue for re-use.
        mServerTaskWorkQueue.offer(task);

        if (Constants.LOGV)  Log.v(TAG,"exiting recycleTask...");
    }

    public void cancelAll() {
              /*
         * Creates an array of tasks that's the same size as the task work queue
         */
        ServerTask[] taskArray = new ServerTask[mConnectionWorkQueue.size()];

        // Populates the array with the task objects in the queue
        mConnectionWorkQueue.toArray(taskArray);

        // Stores the array length in order to iterate over the array
        int taskArraylen = taskArray.length;

        /*
         * Locks on the singleton to ensure that other processes aren't mutating Threads, then
         * iterates over the array of tasks and interrupts the task's current Thread.
         */
        synchronized (this) {

            // Iterates over the array of tasks
            for (int taskArrayIndex = 0; taskArrayIndex < taskArraylen; taskArrayIndex++) {

                // Gets the task's current thread
                Thread thread = taskArray[taskArrayIndex].getTaskThread();

                // if the Thread exists, post an interrupt to it
                if (null != thread) {
                    thread.interrupt();
                }
            }
        }

        /*
         * Clears all waiting photoTasks
         */
        mServerTaskWorkQueue.clear();
    }



    /***********************************************************************************************
     * LOCATION HANDLING
     *
     */
    /**
     * method 'buildGoogleApiClient'
     * <p/>
     * convenience method
     */
    protected synchronized void buildGoogleApiClient() {
        Log.d(TAG, "enter buildGoogleApiClient...");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    /**
     * method 'createLocationRequest'
     * <p/>
     * convenience method
     */
    protected void createLocationRequest(int interval, int fastest) {
        if (interval == 0 && fastest == 0) {
            removeLocationRequest();
            return;
        }

        Log.d("Location Services", "setting location updates with high accuracy.");
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(interval);
        mLocationRequest.setFastestInterval(fastest);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        if (mGoogleApiClient != null) {
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this);
        }
    }

    public void removeLocationRequest() {
        Log.d("Location Services", "removing location updates...");
        if (mGoogleApiClient != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
        }
    }

    /**
     * method 'onConnected'
     * <p/>
     * called upon successful connection to google maps api client
     *
     * @param connectionHint //todo no idea
     */
    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(TAG, "connected successfully...");
        mLocation = LocationServices.FusedLocationApi.getLastLocation(
                mGoogleApiClient);

        if (VERBOSE) {
            if (mLocation != null) {
                Toast.makeText(this, "Latitude is: " + mLocation.getLatitude() + " Longitude is"
                        + mLocation.getLongitude(), Toast.LENGTH_LONG).show();
            } else {
                Log.d("Location Services", "a null Location was returned");
            }
        }

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(TAG, "connection failed...");
        int result = connectionResult.getErrorCode();

        //todo this could be better
        if (result == ConnectionResult.SERVICE_MISSING ||
                result == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ||
                result == ConnectionResult.SERVICE_UPDATING) {
            Toast.makeText(this, "Google play services is missing or out of date",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "connection to location services failed... " + result,
                    Toast.LENGTH_LONG).show();
        }

    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(TAG, "connection suspended...");

        if (VERBOSE) {
            Toast.makeText(this, "Location connection suspended", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.d(TAG, "new location aquired...");
        mLocation = location;
        if (VERBOSE) {
            Log.v(TAG, "new location coordinates: " + location.getLatitude() + " " + location.getLongitude());
            //Toast.makeText(getApplicationContext(), "onLocationChanged called", Toast.LENGTH_SHORT).show();
        }
    }



    /***************************************************************************************************
     * ANALYTICS
     */
    private void recordSignOn(Bundle dataBundle) {
        String androidID = dataBundle.getString(Constants.ANALYTICS_ATTRIBUTE_DEVICE_ID);
        String userID = dataBundle.getString(Constants.ANALYTICS_ATTRIBUTE_USER_ID);
        String token = dataBundle.getString(Constants.ANALYTICS_ATTRIBUTE_SESSION_TOKEN);
        Log.i(TAG, "recording userID " + userID + " : deviceID" + androidID + " : token " + token);

        mTracker.getEventClient().addGlobalAttribute(Constants.ANALYTICS_ATTRIBUTE_USER_ID, userID);
        mTracker.getEventClient().addGlobalAttribute(Constants.ANALYTICS_ATTRIBUTE_DEVICE_ID, androidID);
        mTracker.getEventClient().addGlobalAttribute(Constants.ANALYTICS_ATTRIBUTE_SESSION_TOKEN, token);
    }


    /**
     * method 'reportAnalyticsEvent'
     * <p/>
     * reports an event to Google analytics.
     * <p/>
     * {@link LiveFragment.onLiveFragmentInteractionListener}
     * {@link LocalFragment.onLocalFragmentInteractionListener}
     * Called by these linked interface callbacks.
     *
     */
    public void reportAnalyticsEvent(Bundle data) {
        if (VERBOSE) {
            Log.v(TAG, "entering reportAnalyticsEvent");
            LogUtils.printBundle(data, TAG);
        }

        AnalyticsEvent event = mTracker.getEventClient()
                .createEvent(data.getString(Constants.KEY_ANALYTICS_CATEGORY));

        event.withAttribute(Constants.KEY_ANALYTICS_ACTION,
                data.getString(Constants.KEY_ANALYTICS_ACTION));

        if (data.containsKey(Constants.KEY_ANALYTICS_RESOURCE)) {
            event.withAttribute(Constants.KEY_ANALYTICS_RESOURCE,
                    data.getString(Constants.KEY_ANALYTICS_RESOURCE)
            );
        }

        if (data.containsKey(Constants.KEY_ANALYTICS_VALUE)) {
            event.withAttribute(Constants.KEY_ANALYTICS_VALUE,
                    data.getString(Constants.KEY_ANALYTICS_VALUE)
            );
        }

        mTracker.getEventClient().recordEvent(event);

        Log.v(TAG, "exiting reportAnalyticsEvent");
    }


    /**
     * method 'reportAnalyticsTimingEvent'
     * <p/>
     * reports a timing event to Google analytics.
     * <p/>
     * {@link LiveFragment.onLiveFragmentInteractionListener}
     * {@link LocalFragment.onLocalFragmentInteractionListener}
     * Called by these linked interface callbacks.
     *
     */
    public void reportAnalyticsTimingEvent(Bundle data) {
        if (VERBOSE) {
            Log.v(TAG, "entering reportAnalyticsEvent");
            LogUtils.printBundle(data, TAG);
        }
        Log.w(TAG, "not implemented...");

        //mTracker.send(event.build()); todo fix this

        Log.v(TAG, "exiting reportAnalyticsEvent");
    }

    /**
     * method 'reportAnalyticsTimingEvent'
     * <p/>
     * reports a timing event to Google analytics.
     * <p/>
     * {@link LiveFragment.onLiveFragmentInteractionListener}
     * {@link LocalFragment.onLocalFragmentInteractionListener}
     * Called by these linked interface callbacks.
     *
     */
    public void reportAnalyticsError(Bundle data) {
        if (VERBOSE) {
            Log.v(TAG, "entering reportAnalyticsError");
            LogUtils.printBundle(data, TAG);
        }

        EventClient client = mTracker.getEventClient();

        AnalyticsEvent event =
                client.createEvent(Constants.ANALYTICS_CATEGORY_ERROR);

        event.addAttribute(Constants.ANALYTICS_ERROR_METHOD,
                data.getString(Constants.ANALYTICS_ERROR_METHOD));

        event.addAttribute(Constants.ANALYTICS_ERROR_MESSAGE,
                data.getString(Constants.ANALYTICS_ERROR_MESSAGE));

        client.recordEvent(event);

        if (VERBOSE) Log.v(TAG, "exiting reportAnalyticsError");
    }

    public void setAnalyticsFragment(Bundle data) {
        if (VERBOSE) {
            Log.v(TAG, "entering reportAnalyticsScreen");
            LogUtils.printBundle(data, TAG);
        }

        mTracker.getEventClient()
                .addGlobalAttribute(Constants.SCREEN_TITLE, data.getString(Constants.SCREEN_TITLE)
                );

        if (VERBOSE) Log.v(TAG, "exiting reportAnalyticsScreen");
    }

/***************************************************************************************************
  * AWS INTEGRATION
  */

    public void uploadImageToS3(Bundle b) {
        if (VERBOSE) {
            Log.v(TAG,"entering uploadImageToS3...");
            LogUtils.printBundle(b, TAG);
        }

        if (transferUtility == null) {
            transferUtility =
                    new TransferUtility(
                            new AmazonS3Client(AuthenticationManager
                                    .getCredentialsProvider()), this);
        }

        String directory = b.getString(Constants.KEY_S3_DIRECTORY,"");

        String key = b.getString(Constants.KEY_S3_KEY,"");

        File file;
        if (!key.contains("/")) {
            if (VERBOSE) Log.v(TAG,
                    "File does not contain a path separator, assuming cache directory...");
            file = new File(getCacheDir(), key);
        } else {
            if (VERBOSE) Log.v(TAG,
                    "file contains a path separator... uploading directly..");
            file = new File(key);
        }

        if (Constants.LOGD)
            Log.d(TAG,"uploading image to s3 with key: " + b.getString(Constants.KEY_S3_KEY));
        //Log.d(TAG, "uploading image from file" + file.getPath());

        TransferObserver observer;
        if (BuildConfig.DEBUG) {
            observer = transferUtility.upload(
                    AWSConfiguration.AMAZON_CONTENT_DELIVERY_SANDBOX_S3_BUCKET,     /* The bucket to upload to */
                    b.getString(Constants.KEY_S3_KEY), /* The key for the uploaded object */
                    file /* The file where the data to upload exists */
            );
        } else {
            observer = transferUtility.upload(
                    AWSConfiguration.AMAZON_CONTENT_DELIVERY_S3_BUCKET,     /* The bucket to upload to */
                    b.getString(Constants.KEY_S3_KEY), /* The key for the uploaded object */
                    file /* The file where the data to upload exists */
            );
        }

        pendingMap.put(observer.getId(), b);
        observer.setTransferListener(this);

        if (VERBOSE) {
            Log.v(TAG,"exiting uploadImageToS3...");
        }
    }

    public void downloadImageFromS3(Bundle b) {
        if (VERBOSE) {
            Log.v(TAG, "entering downloadImageFromS3...");
            LogUtils.printBundle(b, TAG);
        }

        String directory = b.getString(Constants.KEY_S3_DIRECTORY, "");
        String key = b.getString(Constants.KEY_S3_KEY,"");

        if (key.equals("") || directory.equals("")){
            Log.e(TAG,"missing required parameters...");
            return;
            //throw new RuntimeException("downloadImageFromS3 is missing parameters...");
        }

        File file = new File(getCacheDir(),key);

        if (Constants.LOGD) {
            Log.d(TAG, "downloading image from  s3 path:  " + directory + " / " + key);
            Log.d(TAG, "storing downloaded imaged at: " + file.getPath());
        }

        if (userFileManager == null) {

            Log.v(TAG, "ContentManager was null...");
            //initializeTransferUtility();
        } else {
            userFileManager.getContent(key, this);
        }

        if (VERBOSE) {
            Log.v(TAG,"exiting downloadImageFromS3...");
        }
    }

    /**
     * method 'onStateChanged'
     *
     * callback from the high level AWS TransferObserver
     *
     * currently, sending and receiving are handled very differently in regards to static content
     *
     * for sending, the dynamic content is only sent to the server upon success confirmation from
     * AWS
     *
     * for receiving, as soon as the dynamic content is received, the static is also received.
     *
     * @param id
     * @param state
     */
    @Override
    public void onStateChanged(int id, TransferState state) {
        if (VERBOSE) {
            Log.v(TAG, "AWS TransferState: " + state.name() + " for id " + id);
        }
        Bundle data;

        switch (state) {

            case CANCELED:
            case UNKNOWN:
            case FAILED:
                data = pendingMap.get(id);
                switch (data.getInt(REQUEST_TYPE,-1)) {
                    case MSG_DOWNLOAD_IMAGE:
                        if (VERBOSE) Log.d(TAG,"an image failed to download. notifying photoManager..");

                        Message msg = Message.obtain(null,PhotoManager.DOWNLOAD_FAILED);
                        msg.setData(data);

                        try {
                            replyMessenger.send(msg);
                        } catch (RemoteException e) {
                            Log.e(TAG,"error responding that an image has finished downloading...");
                        }
                        break;

                    default:
                        Toast.makeText(getApplicationContext(),
                                "s3 transfer failed... removing content",
                                Toast.LENGTH_SHORT)
                                .show();
                }

                //remove pending dynamic data
                pendingMap.remove(id);

                break;

            case COMPLETED:
                if (VERBOSE) {
                    Log.v(TAG,"static content successfully transferred");
                }
                //get the data pending data for this transfer ID
                data = pendingMap.get(id);
                //Depending on what the pending post was... handle it
                if (data == null) {
                    Log.e(TAG,"incoming bundle was null");
                    break;
                } else if (VERBOSE){
                    LogUtils.printBundle(data,TAG);
                }

                ContentValues values = new ContentValues();
                values.put(StashEntry.IMAGE_URL_COLUMN,data.getString(Constants.KEY_S3_KEY));
                values.put(StashEntry.IMAGE_THUMBURL_COLUMN,data.getString(Constants.KEY_S3_KEY));
                values.put(StashEntry.IMAGE_PICTURENAME_COLUMN,data.getString(Constants.KEY_S3_KEY));
                values.put(StashEntry.IMAGE_THUMBNAME_COLUMN,data.getString(Constants.KEY_S3_KEY));
                getContentResolver().insert(FireFlyContentProvider
                        .PICTUREURL_TABLE_CONTENTURI,values);

                ServerTask task = null;

                int requestType = data.getInt(REQUEST_TYPE,-1);
                switch (requestType) {
                    case MSG_SEND_IMAGE:
                        task = new SendLocalPostTask();
                        break;

                    case MSG_SEND_MESSAGE:
                        task = new SendMessageTask();
                        break;

                    case MSG_REPLY_TO_THREAD:

                        if (Constants.LOGD) Log.d(TAG,"publishing first to GCM");
                        boolean success = true;
                        try {
                            //publish first to GCM

                            final Bundle datacopy = data;

                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    AWSMobileClient.defaultMobileClient()
                                            .getPushManager()
                                            .publishToTopic(datacopy);
                                }
                            }).start();

                        } catch (Exception e) {
                            Log.e(TAG,"Error in publishing reply to topic...",e);
                            success = false;
                        }
                        if (success) {
                            if (Constants.LOGD) Log.d(TAG,"Now creating HTTP task...");
                            task = new SendReplyTask();
                        } else {
                            //todo notify server to delete image
                            if (Constants.LOGD) Log.d(TAG,"sending text reply to server anyway");
                        }
                        break;

                    case MSG_REQUEST_LIVE_THREADS:
                        break;

                    case MSG_REQUEST_LOCAL_POSTS:
                        break;

                    case MSG_REQUEST_REPLIES:
                        break;

                    case MSG_REQUEST_MESSAGES:
                        break;

                    case MSG_DOWNLOAD_IMAGE:
                        if (VERBOSE) Log.d(TAG,"an image successfully downloaded. notifying photoManager..");

                        Message msg = Message.obtain(null,PhotoManager.DOWNLOAD_COMPLETE);
                        msg.setData(data);

                        if (replyMessenger != null) {
                            try {
                                replyMessenger.send(msg);
                            } catch (RemoteException e) {
                                Log.e(TAG, "error responding that an image has finished downloading...");
                            }
                        } else {
                            Log.e(TAG,"imageResponseMessenger was null, cannot send.");
                        }
                        break;

                    default:
                        Log.e(TAG, "no dynamic transfer type was provided... discarding...");
                        pendingMap.remove(id);

                }

                if (task != null && AuthenticationManager.getCurrentAccessToken() != null) {
                    task.initializeTask(this, data, AuthenticationManager.getCurrentAccessToken(), requestType);
                    mConnectionThreadPool.execute(task.getServerConnectRunnable());
                }
                pendingMap.remove(id);

                break;
        }

        // do something
    }

    @Override
    public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
        int percentage = (int) (bytesCurrent / bytesTotal * 100);
        if (VERBOSE) {
            Log.v(TAG,id + " : " + percentage + "%");
        }

        //Display percentage transfered to user
    }

    @Override
    public void onError(int id, Exception ex) {
        Toast.makeText(getApplicationContext(), "error occured for transfer: " + id,Toast.LENGTH_SHORT).show();
        Log.e(TAG, "entering onError...", ex);
        Bundle b = pendingMap.remove(id);


        Log.i(TAG,"now discarding data for id : " + id);
        LogUtils.printBundle(b,TAG);

        Log.e(TAG, "exiting onError...", ex);
    }

    @Override
    public void onProgressUpdate(String filePath, boolean isWaiting, long bytesCurrent,
                                 long bytesTotal) {


    }

    @Override
    public void onError(String filePath, Exception ex) {
        Log.e(TAG,"error with filepath: " + filePath,ex);
        ex.printStackTrace();
        Bundle b = new Bundle();
        b.putString(Constants.KEY_S3_KEY, filePath);
        Message msg = Message.obtain(null,PhotoManager.REQUEST_FAILED);
        msg.setData(b);

        if (replyMessenger != null) {
            try {
                replyMessenger.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "error responding that an image that failed to download...");
            }
        } else {
            Log.e(TAG, "imageResponseMessenger was null, cannot send.");
        }


    }

    @Override
    public void onSuccess(ContentItem contentItem) {
        Log.i(TAG, "success for filepath " + contentItem.getFile().getAbsolutePath());

        Log.v(TAG, "successful path name " + contentItem.getFile().getName());
        Bundle b = new Bundle();
        b.putString(Constants.KEY_S3_KEY, contentItem.getFile().getName());
        Message msg = Message.obtain(null,PhotoManager.REQUEST_COMPLETE);
        msg.setData(b);

        if (replyMessenger != null) {
            try {
                if (VERBOSE) Log.v(TAG, "Sending success message.");
                replyMessenger.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "error responding that an image has finished downloading...");
            }
        } else {
            Log.e(TAG,"imageResponseMessenger was null, cannot send.");
        }

    }

    private void sendErrorBroadcast(int responseCode, String message) {
        if (VERBOSE) Log.v(TAG,"sendingErrorBroadcast : " + responseCode + " , " + message);
        Intent intent = new Intent();
        intent.setAction(Constants.ACTION_SERVER_ERROR);
        intent.putExtra(Constants.RESPONSE_CODE, responseCode);
        intent.putExtra(Constants.RESPONSE_MESSAGE, message);
        sendBroadcast(intent);
    }

    private void sendErrorBroadcast(int responseCode, Bundle dataBundle) {

        Intent intent = new Intent(Constants.ACTION_SERVER_ERROR);
        intent.putExtra(Constants.RESPONSE_CODE,responseCode);
        intent.putExtra(Constants.RESPONSE_BUNDLE,dataBundle);
        sendBroadcast(intent);

    }

    private void requestRepliesforFirstThread() {
        if (VERBOSE) Log.v(TAG,"entering requestRepliesForFirstThread");

        String[] projection = {SQLiteDbContract.LiveEntry.COLUMN_NAME_THREAD_ID};

        /* String selection =  "0 LIKE ?";
        String[] selectionArgs = {SQLiteDbContract.LiveEntry.COLUMN_ID};*/

        Cursor c = getContentResolver().query(FireFlyContentProvider.CONTENT_URI_LIVE,
                projection,
                null,
                null,
                SQLiteDbContract.LiveEntry.COLUMN_ID);


        if (c!=null && c.getCount() != 0) {
            c.moveToFirst();

            String s = c.getString(
                    c.getColumnIndexOrThrow(
                            SQLiteDbContract.LiveEntry.COLUMN_NAME_THREAD_ID
                    )
            );

            Log.i(TAG, "now requesting replies for thread : " + s);

            c.close();

            Bundle b = new Bundle();
            b.putString(SQLiteDbContract.LiveEntry.COLUMN_NAME_THREAD_ID,s);

            ServerTask task = new RequestRepliesTask();
            task.initializeTask(this,
                    b,
                    AuthenticationManager.getCurrentAccessToken(),
                    MSG_REQUEST_REPLIES
            );
            mConnectionThreadPool.execute(task.getServerConnectRunnable());

        } else {
            Log.e(TAG,"no threads returned, is the server down?"); //todo disable replies
        }

        if (VERBOSE) Log.v(TAG,"exiting requestRepliesForFirstThread");
    }

}