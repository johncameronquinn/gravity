package com.jokrapp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Camera;
import android.location.LocationManager;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.app.Fragment;
import android.support.v13.app.FragmentStatePagerAdapter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.amazonaws.AmazonClientException;
import com.amazonaws.mobile.AWSMobileClient;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.MobileAnalyticsManager;
import com.amazonaws.mobile.user.IdentityManager;
import com.jokrapp.android.dev.ContentDeliveryDemoFragment;
import com.jokrapp.android.dev.PushDemoFragment;
import com.jokrapp.android.util.ImageUtils;
import com.jokrapp.android.util.LogUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Author/Copyright John C. Quinn All Rights Reserved.
 * created: 3-26-2015
 * date last modified: 2015-06-17
 *
 * this is the main activity for the app Proxi
 *
 * there is only one activity, and all the other features are managed as fragments
 */
public class MainActivity extends Activity implements CameraFragment.OnCameraFragmentInteractionListener,
LocalFragment.onLocalFragmentInteractionListener, LiveFragment.onLiveFragmentInteractionListener,
        ViewPager.OnPageChangeListener, PhotoFragment.onPreviewInteractionListener,
        ListView.OnItemClickListener{
    private static String TAG = "MainActivity";
    private static final boolean VERBOSE = true;

    //UI
    private static CustomViewPager mPager;
    private static MainAdapter mAdapter;
    private ListView mSettingsListView;

    /** FRAGMENT MANAGEMENT
     */
    private static final int MESSAGE_LIST_POSITION = 3;
    private static final int LOCAL_LIST_POSITION = 4;
    private static final int CAMERA_LIST_POSITION = 0;
    private static final int LIVE_LIST_POSITION = 1;
    private static final int REPLY_LIST_POSITION = 2;

    private static final String MESSAGE_PAGER_TITLE = "Message";
    private static final String LOCAL_PAGER_TITLE = "Local";
    private static final String LIVE_PAGER_TITLE = "Live";
    private static final String CAMERA_PAGER_TITLE = "Camera";
    private static final String REPLY_PAGER_TITLE = "Reply";

    private static final int NUMBER_OF_FRAGMENTS = 2;

    private static WeakReference<MessageFragment> MessageFragReference = new WeakReference<>(null);
    private static WeakReference<CameraFragment> CameraFragReference = new WeakReference<>(null);
    private static WeakReference<LiveFragment> LiveFragReference = new WeakReference<>(null);
    private static WeakReference<ReplyFragment> ReplyFragReference = new WeakReference<>(null);
    private static WeakReference<LocalFragment> LocalFragReference = new WeakReference<>(null);

    /** CAMERA MANAGEMENT
     */
    private boolean isCamera = false;
    Messenger cameraMessenger;
   // private CameraHandler cameraHandler;

    static CameraHandler sHandler;

    private static final int CAMERA_POSITION_BACK = 0;
    private static final int CAMERA_POSITION_FRONT = 1;
    private static int currentCamera = CAMERA_POSITION_BACK;

    private String messageTarget;

    /** SERVICE MANAGEMENT
     */
    private boolean isBound = false;

    private Messenger mService;

    private final UIHandler uiHandler = new UIHandler();
    final Messenger messageHandler = new Messenger(uiHandler);

    /** AWS CONNECTION
     */
    private IdentityManager identityManager;


    /** ANALYTICS
     */
    private MobileAnalyticsManager mTracker;


    /**IMAGE FULLSCREEN*/

    // A handle to the main screen view
    View mMainView;


    // Tracks whether Fragments are displaying side-by-side
    boolean mSideBySide;

    // Tracks whether navigation should be hidden
    boolean mHideNavigation;

    // Tracks whether the app is in full-screen mode
    boolean mFullScreen;

    // Instantiates a new broadcast receiver for handling Fragment state
    private FragmentDisplayer mFragmentDisplayer = new FragmentDisplayer();

    private final BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Received notification from local broadcast. Display it in a dialog.");

            Bundle data = intent.getBundleExtra(PushListenerService.INTENT_SNS_NOTIFICATION_DATA);
            String message = PushListenerService.getMessage(data);
            if (BuildConfig.FLAVOR.equals("dev")) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.push_demo_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } else {
                Log.i(TAG, "Message Notification Received");//todo create "NEW MESSAGE" notification

                final TextView textView = new TextView(context);

                FrameLayout.LayoutParams textviewlayoutParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
                textView.setLayoutParams(textviewlayoutParams);

                textView.setGravity(Gravity.CENTER);
                textView.setText("A message has been received!");
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                textView.setTextColor(getResources().getColor(R.color.black_overlay));
                textView.setBackgroundColor(getResources().getColor(R.color.cyber_light_blue));

                final FrameLayout layout = (FrameLayout)findViewById(R.id.rootlayout);
                layout.addView(textView,layout.getChildCount()-1);

                Runnable removeTextView = new Runnable() {
                    @Override
                    public void run() {
                        layout.removeView(textView);
                    }
                };

                uiHandler.postDelayed(removeTextView,5000);
            }
        }
    };


    /**
     * ********************************************************************************************
     * <p>
     * LIFECYCLE MANAGEMENT
     * <p>
     * /**
     * Handles the initial loading of the app. Splash is defined in AndroidManifest
     *
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "enter onCreate...");
        if (savedInstanceState != null) {
           //Restore the camerafragment's instance
        }

        // Obtain a reference to the mobile client. It is created in the Splash Activity.
        AWSMobileClient awsMobileClient = AWSMobileClient.defaultMobileClient();

        if (awsMobileClient == null) {
            // In the case that the activity is restarted by the OS after the application
            // is killed we must redirect to the splash activity to handle initialization.
            Intent intent = new Intent(this, SplashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return;
        }

        super.onCreate(savedInstanceState);

        uiHandler.setParent(this);

        HandlerThread handlerThread = new HandlerThread("CameraHandlerThread", Process.THREAD_PRIORITY_FOREGROUND);
        handlerThread.start();
        sHandler = new CameraHandler(handlerThread.getLooper());
        sHandler.setParent(this);
        cameraMessenger = new Messenger(sHandler);

        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            Window window = getWindow();

            // clear FLAG_TRANSLUCENT_STATUS flag:
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

            // add FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS flag to the window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(getResources().getColor(R.color.jpallete_neutral_blue));
        }*/

        checkForLocationEnabled(this);

        initializeAWS();

        initializeUI();

        initializeAnalytics();

        Log.d(TAG, "exit onCreate...");
    }
/***************************************************************************************************
* INITIALIZATION METHODS
 *
 *
 * These methods all occur within onCreate, and exist for nicencess
**/
    /**
     * method 'initializeUI'
     *
     * creates the UI, sets the 5 fragment viewpaging, etc
     */
    private void initializeUI() {
        setContentView(R.layout.activity_main);

        //mSettingsListView = (ListView)findViewById(R.id.content);
        //mSettingsListView.setOnItemClickListener(this);

        int numberOfFragments;
        if (BuildConfig.FLAVOR.equals("dev")) {
            Log.i(TAG,"Developer mode enabled, creating all fragments");
            numberOfFragments = getResources().getInteger(R.integer.number_of_fragments_dev);
        } else {
            Log.i(TAG,"setting count to five fragments...");
            numberOfFragments =  getResources().getInteger(R.integer.number_of_fragments);
        }
        Log.d(TAG,"Number of fragments = " + numberOfFragments);
        mAdapter = new MainAdapter(getFragmentManager(),numberOfFragments);
        mPager = (CustomViewPager) findViewById(R.id.pager);
        mPager.setAdapter(mAdapter);
        mPager.setCurrentItem(CAMERA_LIST_POSITION);
        mPager.addOnPageChangeListener(this);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        int STASH_POSTION = 0;
        if (position == STASH_POSTION) {

            final int LOCAL_SETTINGS = 0;
            final int GALLERY = 1;
            final int LIVE_SETTINGS = 2;

            int startPage = GALLERY;
            switch (mPager.getCurrentItem()) {

                case MESSAGE_LIST_POSITION:
                case LOCAL_LIST_POSITION:
                    Toast.makeText(getApplicationContext(),"Opening Local Settings...",Toast.LENGTH_SHORT).show();
                    startPage = LOCAL_SETTINGS;
                    break;

                case CAMERA_LIST_POSITION:
                    Toast.makeText(getApplicationContext(),"Opening Stash Gallery...",Toast.LENGTH_SHORT).show();
                    startPage = GALLERY;
                    break;

                case LIVE_LIST_POSITION:
                case REPLY_LIST_POSITION:
                    Toast.makeText(getApplicationContext(),"Opening Live Settings...",Toast.LENGTH_SHORT).show();
                    startPage = LIVE_SETTINGS;
                    break;
            }
            Intent stashActivtyIntent = new Intent(getApplicationContext(),StashActivity.class);
            stashActivtyIntent.putExtra(StashActivity.STARTING_PAGE_POSITION_KEY, startPage);
            startActivity(stashActivtyIntent);

        }
    }

    /**
     * method 'initializeAWS'
     *
     * initializes the connection to AWS
     */
    private void initializeAWS() {
        // Obtain a reference to the mobile client. It is created in the Splash Activity.
        AWSMobileClient awsMobileClient = AWSMobileClient.defaultMobileClient();
        if (awsMobileClient == null) {
            // In the case that the activity is restarted by the OS after the application
            // is killed we must redirect to the splash activity to handle initialization.
            Intent intent = new Intent(this, SplashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return;
        }

        // Obtain a reference to the identity manager.
        identityManager = awsMobileClient.getIdentityManager();
    }

    /**
     * method 'initializeAnalytics'
     *
     * initializes the connection to the analytics services
     */
    private void initializeAnalytics() {
        // [START shared_tracker]
        //obtain the shared Tracker instance
        AnalyticsApplication application = (AnalyticsApplication)getApplication();
        mTracker = application.getAnalyticsManager();
        // [END shared_tracker]
    }

    public Messenger getMessenger() {
        return mService;
    }

    /***************************************************************************************************
      * LIFECYCLE METHODS
     **/

    @Override
    protected void onStart() {
        Log.d(TAG, "enter onStart...");
        super.onStart();

        Log.d(TAG, "binding the service to this class, creating if necessary");
        Intent intent = new Intent(this, DataHandlingService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        try {
            Message msg = Message.obtain(null, MSG_CONNECT_CAMERA, currentCamera, 0);
            cameraMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to connect camera");
        }


        // One filter is for the action ACTION_VIEW_IMAGE
        IntentFilter displayerIntentFilter = new IntentFilter(
                Constants.ACTION_VIEW_IMAGE);

        // Registers the receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mFragmentDisplayer,
                displayerIntentFilter);

        // Creates a second filter for ACTION_ZOOM_IMAGE
        displayerIntentFilter = new IntentFilter(Constants.ACTION_ZOOM_IMAGE);

        // Registers the receiver
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(
                        mFragmentDisplayer,
                        displayerIntentFilter
                );


        if (isBound) {
            Log.d(TAG, "sending message to connect GoogleApiClient...");
            // Create and send a message to the service, using a supported 'what' value
            Message msg1 = Message.obtain(null, DataHandlingService.MSG_CONNECT_CLIENT, 0, 0);
            try {
                mService.send(msg1);
             } catch (RemoteException e) {
                  e.printStackTrace();
            }
        }

        Log.d(TAG, "exit onStart...");
    }

    @Override
    protected void onRestart() {
        Log.d(TAG, "entering onRestart...");
        super.onRestart();
        Log.d(TAG, "exiting onRestart...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "enter onResume...");
        //mTracker.getSessionClient().resumeSession();

        // Obtain a reference to the mobile client. It is created in the Splash Activity.
        final AWSMobileClient awsMobileClient = AWSMobileClient.defaultMobileClient();

        if (awsMobileClient == null) {
            // At this point, the app is resuming after being shut down and the onCreate
            // method has already fired an intent to launch the splash activity. The splash
            // activity will refresh the user's credentials and re-initialize all required
            // components. We bail out here, because without that initialization, the steps
            // that follow here would fail. Note that if you don't have any features enabled,
            // then there may not be any steps here to skip.
            return;
        }

        // pause/resume Mobile Analytics collection
        awsMobileClient.handleOnResume();

        // register notification receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver,
                new IntentFilter(PushListenerService.ACTION_SNS_NOTIFICATION));

        // pause/resume Mobile Analytics collection
        awsMobileClient.handleOnResume();

        // register notification receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver,
                new IntentFilter(PushListenerService.ACTION_SNS_NOTIFICATION));
        Log.d(TAG, "exit onResume...");
    }


    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (VERBOSE) Log.v(TAG,"entering onPostResume...");


        if (VERBOSE) Log.v(TAG,"exiting onPostResume...");
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "enter onPause...");
//        mTracker.getSessionClient().pauseSession();
//        mTracker.getEventClient().submitEvents();

        // Obtain a reference to the mobile client.
        final AWSMobileClient awsMobileClient = AWSMobileClient.defaultMobileClient();

        if (awsMobileClient != null) {
            // pause/resume Mobile Analytics collection
            awsMobileClient.handleOnPause();
        }

        // unregister notification receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver);

        Log.d(TAG, "exit onPause...");
    }

    @Override
    protected void onStop() {
        super.onStop();

        Log.d(TAG, "enter onStop...");

        try {
            cameraMessenger.send(Message.obtain(null, MSG_DISCONNECT_CAMERA));
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to connect camera");
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(this.mFragmentDisplayer);

        //unbind the service now
        if (isBound) {

            Log.d(TAG, "sending message to disconnect GoogleApiClient...");
            Message msg1 = Message.obtain(null, DataHandlingService.MSG_DISCONNECT_CLIENT, 0, 0);

            try {
                mService.send(msg1);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "unbinding the service...");
            unbindService(mConnection);
            isBound = false;
        }

        Log.d(TAG, "exit onStop...");
    }

    protected void onDestroy() {
        Log.d(TAG, "enter onDestroy...");

        Bundle b = new Bundle();
        b.putString(Constants.KEY_ANALYTICS_CATEGORY, Constants.ANALYTICS_CATEGORY_LIFECYCLE);
        b.putString(Constants.KEY_ANALYTICS_ACTION, "destroyed");
        b.putString(Constants.KEY_ANALYTICS_LABEL, "Last open fragment");
        b.putString(Constants.KEY_ANALYTICS_VALUE, String.valueOf(mAdapter.getPageTitle(mPager.getCurrentItem())));

        try {
            cameraMessenger.send(Message.obtain(null, MSG_ACTIVITY_DESTROYED));
        } catch (RemoteException e) {
            Log.e(TAG,"error notifying that fragment was destroyed",e);
        }


        // Sets the main View to null
        mMainView = null;

        super.onDestroy();
        Log.d(TAG, "exit onDestroy...");
    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(TAG, "enter onSavedInstanceState...");

        Log.d(TAG, "exit onSavedInstanceState...");
    }

    /**
     * method 'onTrimMemory'
     *
     * called when the android system sends out various broadcasts regarding memory recovery
     * @param level the type of broadcast sent out
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        switch (level) {
            case TRIM_MEMORY_UI_HIDDEN:
                Log.d(TAG,"Trim memory UI Hidden called");
                break;

        }

    }

    /***********************************************************************************************
     *
     * USER INTERACTION
     *
     */
    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return super.onCreateView(name, context, attrs);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        if (VERBOSE) Log.v(TAG,"entering onRestoreInstanceState...");

        super.onRestoreInstanceState(savedInstanceState);

        if (VERBOSE) Log.v(TAG,"exiting onRestoreInstanceState...");
    }


    public void onNotImplemented(View v) {
        Toast.makeText(this,"Not yet implemented...",Toast.LENGTH_SHORT).show();
    }

    public void localMessagePressed(String arn) {
        if (VERBOSE) {
            Toast.makeText(this,"Message pressed: " + arn,Toast.LENGTH_LONG).show();
        }

        Bundle b = new Bundle();
        b.putString(Constants.KEY_ANALYTICS_CATEGORY,Constants.ANALYTICS_CATEGORY_LOCAL);
        b.putString(Constants.KEY_ANALYTICS_ACTION,"message");
        b.putString(Constants.KEY_ANALYTICS_LABEL,(String)arn);
        sendMsgReportAnalyticsEvent(b);

        CameraFragment.setMessageTarget(arn);
        CameraFragReference.get().startMessageMode((String)arn);
        mPager.setCurrentItem(CAMERA_LIST_POSITION);
        mPager.setPagingEnabled(false);
    }

    private Runnable mLongPressed = new Runnable() {
        public void run() {
            final int LOCAL_SETTINGS = 0;
            final int GALLERY = 1;
            final int LIVE_SETTINGS = 2;

            int startPage = GALLERY;
            switch (mPager.getCurrentItem()) {

                /*case MESSAGE_LIST_POSITION:
                case LOCAL_LIST_POSITION:
                    Toast.makeText(getApplicationContext(),"Opening Local Settings...",Toast.LENGTH_SHORT).show();
                    startPage = LOCAL_SETTINGS;
                    break;*/

                case CAMERA_LIST_POSITION:
                    Toast.makeText(getApplicationContext(),"Opening Stash Gallery...",Toast.LENGTH_SHORT).show();
                    startPage = GALLERY;
                    break;

                case LIVE_LIST_POSITION:
                case REPLY_LIST_POSITION:
                    Toast.makeText(getApplicationContext(),"Opening Live Settings...",Toast.LENGTH_SHORT).show();
                    startPage = LIVE_SETTINGS;
                    break;
            }
            Intent stashActivtyIntent = new Intent(getApplicationContext(),StashActivity.class);
            stashActivtyIntent.putExtra(StashActivity.STARTING_PAGE_POSITION_KEY, startPage);
            startActivity(stashActivtyIntent);
        }
    };


 //   private float xpos = 0;
 //   private float ypos = 0;

    /**
     * method 'dispatchTouchEvent'
     *
     * all incoming touchEvents from the window are first checked here before being dispatched
     * to their respective view's.
     *
     * in this method, we test for a long click and handle it accordingly.
     *
     * @param
     * @return
     */
   /* @Override
    public boolean dispatchTouchEvent(MotionEvent event) {


        if(event.getAction() == MotionEvent.ACTION_DOWN) {
            uiHandler.postDelayed(mLongPressed, 2000);
            xpos = event.getX();
            ypos = event.getY();
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            uiHandler.removeCallbacks(mLongPressed);
        }


        return super.dispatchTouchEvent(event);
    }*/

    public void onMessageRefresh(View v) {
        Bundle b = new Bundle();
        b.putString(Constants.KEY_ANALYTICS_CATEGORY,Constants.ANALYTICS_CATEGORY_MESSAGE);
        b.putString(Constants.KEY_ANALYTICS_ACTION, "refresh");
        sendMsgReportAnalyticsEvent(b);

        sendMsgRequestLocalMessages();
    }

    public void onLocalRefresh(View v) {
        Bundle b = new Bundle();
        b.putString(Constants.KEY_ANALYTICS_CATEGORY,Constants.ANALYTICS_CATEGORY_LOCAL);
        b.putString(Constants.KEY_ANALYTICS_ACTION, "refresh");
        sendMsgReportAnalyticsEvent(b);

        sendMsgRequestLocalPosts(3);
    }

    public static void hide_keyboard(Activity activity) {
        InputMethodManager inputMethodManager = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        //Find the currently focused view, so we can grab the correct window token from it.
        View view = activity.getCurrentFocus();
        //If no view currently has focus, create a new one, just so we can grab a window token from it
        if(view == null) {
            view = new View(activity);
        }
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public void hideSoftKeyboard() {
        MainActivity.hide_keyboard(this);
    }

    /**
     * method 'saveToStash'
     *
     * this method saves an image to our internal stash directory
     * uses a provided photoView, as this object should contain all the information necessary.
     * this method is chosen over a "copyFile" implemenation, because in the case of Local,
     * files are never normally written to disk.
     *
     * translation: this works in all cases
     *
     * @param photoView the photoView object containing the image to be saved
     */
    public void saveToStash(PhotoView photoView) {
        if (VERBOSE) Log.v(TAG, "entering onSaveToStash with key " + photoView.getImageKey());

        String strDirectory = getCacheDir().getAbsolutePath();
        File outFile = new File(strDirectory + File.separator + photoView.getImageKey());
        File thumbFile = new File(strDirectory + File.separator + photoView.getImageKey() + "s");

        if (photoView.getDrawable() instanceof BitmapDrawable) {
            Toast.makeText(this,"Saving...",Toast.LENGTH_SHORT).show();

        Bitmap b = ((BitmapDrawable) photoView.getDrawable()).getBitmap();

            FileOutputStream fOut;
            FileOutputStream thumbOut;

        /*
         * If neither the thumbnail nor the file exists, create and save both
         */
        if (!outFile.exists() && !thumbFile.exists()) {
            if (VERBOSE) Log.v(TAG,"neither the image nor the thumbnail existed...");

            try {

                fOut = new FileOutputStream(outFile);
                thumbOut = new FileOutputStream(thumbFile);


                if (VERBOSE) Log.v(TAG, "writing image to: " + outFile);
                if (VERBOSE) Log.v(TAG, "writing thumbnail to: " + thumbFile);

                Bitmap thumbNail = ThumbnailUtils.extractThumbnail(b,200,200);


                /**Compress image**/
                b.compress(Bitmap.CompressFormat.JPEG, 100, fOut);
                fOut.flush();
                fOut.close();

                /**Compress thumbnail**/
                thumbNail.compress(Bitmap.CompressFormat.JPEG, 100, thumbOut);
                thumbOut.flush();
                thumbOut.close();

                /*String url = MediaStore.Images.Media.insertImage(
                        getContentResolver(),
                        outFile.getAbsolutePath(),
                        outFile.getName(),
                        outFile.getName()
                );*/

                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();

            } catch (IOException e) {
                Log.e(TAG, "ioException", e);
                Toast.makeText(this, "error saving image to gallery", Toast.LENGTH_LONG).show();
                return;
            }
        } else if (!thumbFile.exists() && outFile.exists()) {
            if (VERBOSE) Log.v(TAG,"the thumbnail didn't exist, but the image did...");


            try {
                thumbOut = new FileOutputStream(thumbFile);

                Bitmap thumbNail = ThumbnailUtils.extractThumbnail(b, 200, 200);


                /**Compress thumbnail and save**/
                thumbNail.compress(Bitmap.CompressFormat.JPEG, 100, thumbOut);
                thumbOut.flush();
                thumbOut.close();
            } catch (IOException e) {
                Log.e(TAG, "ioException saving thumbnail", e);
                Toast.makeText(this, "error saving image to gallery", Toast.LENGTH_LONG).show();
                return;
            }
        } else if (!outFile.exists() && thumbFile.exists()) {
            if (VERBOSE) Log.v(TAG,"the image didn't exist, but the thumbnail did...");

            try {
                fOut = new FileOutputStream(outFile);

                /**Compress image and save**/
                b.compress(Bitmap.CompressFormat.JPEG, 100, fOut);
                fOut.flush();
                fOut.close();
            } catch (IOException e) {
                Log.e(TAG, "ioException saving image", e);
                Toast.makeText(this, "error saving image to gallery", Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            if (VERBOSE) Log.v(TAG,"both the image and the thumbnail existed, just insert...");
        }


            ContentValues values = new ContentValues();
            values.put(SQLiteDbContract.StashEntry.IMAGE_URL_COLUMN,
                    photoView.getImageKey()
            );
            values.put(SQLiteDbContract.StashEntry.IMAGE_THUMBURL_COLUMN,
                    photoView.getImageKey() + "s"
            );
            values.put(SQLiteDbContract.StashEntry.IMAGE_PICTURENAME_COLUMN,
                    outFile.getName()
            );
            values.put(SQLiteDbContract.StashEntry.IMAGE_THUMBNAME_COLUMN,
                    thumbFile.getName()
            );

            getContentResolver().insert(FireFlyContentProvider.PICTUREURL_TABLE_CONTENTURI,
                    values);

            Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show();

        } else {
            if (VERBOSE) Log.v(TAG,"drawable was not a bitmap drawable, do nothing...");
        }



        if (VERBOSE) Log.v(TAG,"exiting onSaveToStash...");
        /*String filePath = (String)v.getTag();
        if (VERBOSE) Log.v(TAG,"entering onSaveToStash with filePath : " + filePath);

        String root = Environment.getExternalStorageDirectory().toString();
        File myDir = new File(root + File.separator + Constants.STASH_GALLERY_DIRECTORY);
        myDir.mkdirs();


        try {
            ImageUtils.copyFile(
                    new File(getCacheDir(), filePath),
                    new File(myDir + File.separator + filePath)
            );
        } catch (IOException e) {
            Log.e(TAG,"error saving file to external gallery",e);
        }*/
    }

    public void exportImage(PhotoView photoView) {

        if (VERBOSE) Log.v(TAG, "entering exportImage with key " + photoView.getImageKey());

        String strDirectory = Environment.getExternalStorageDirectory().toString();
        File outFile = new File(strDirectory + File.separator +
                Constants.STASH_GALLERY_DIRECTORY, photoView.getImageKey());

        Bitmap b = ((BitmapDrawable) photoView.getDrawable()).getBitmap();

        FileOutputStream fOut;

        if (outFile.exists()) {
            Log.i(TAG, "file already existed... not saving...");
            Toast.makeText(this, "File already existed in stash...", Toast.LENGTH_SHORT).show();
        } else {

            try {
                fOut = new FileOutputStream(outFile);

                /**Compress image**/
                b.compress(Bitmap.CompressFormat.JPEG, 100, fOut);
                fOut.flush();
                fOut.close();

                String url = MediaStore.Images.Media.insertImage(
                        getContentResolver(),
                        outFile.getAbsolutePath(),
                        outFile.getName(),
                        outFile.getName()
                );



                Toast.makeText(this, "Export Complete", Toast.LENGTH_SHORT).show();

            } catch (IOException e) {
                Log.e(TAG, "ioException", e);
                Toast.makeText(this, "error saving image to gallery", Toast.LENGTH_LONG).show();
            }

        }

        if (VERBOSE) Log.v(TAG, "exiting exportImage with key " + photoView.getImageKey());
    }

    public void onLocalReplyPressed(View view) {
        if (VERBOSE) {
            Toast.makeText(this,"Message pressed: " + view.getTag(),Toast.LENGTH_LONG).show();
        }
        CameraFragment.setMessageTarget((String) view.getTag());
        mPager.setCurrentItem(CAMERA_LIST_POSITION);
        mPager.setPagingEnabled(false);

    }

    public void onMessageReplyPressed(View view) {
        if (VERBOSE) {
            Toast.makeText(this,"Message pressed: " + view.getTag(),Toast.LENGTH_LONG).show();
        }
        CameraFragment.setMessageTarget((String) view.getTag());
        mPager.setCurrentItem(CAMERA_LIST_POSITION);
        mPager.setPagingEnabled(false);

    }

    public void onLiveTakeReplyPicture(View view) {
        takeReplyPicture();
    }


    /**
     * method 'onLocalBlockPressed'
     *
     * called when a user pressed the block button on an image in local
     *
     * @param view the view of the button that was pressed.
     */
    public void onLocalBlockPressed(View view) {
        if (VERBOSE) Log.v(TAG,"Block button pressed.");

        Toast.makeText(this,"Blocking user " + view.getTag(),Toast.LENGTH_SHORT).show();

        Bundle b = new Bundle();
        b.putString(Constants.KEY_ANALYTICS_CATEGORY,Constants.ANALYTICS_CATEGORY_LOCAL);
        b.putString(Constants.KEY_ANALYTICS_ACTION,"block");
        b.putString(Constants.KEY_ANALYTICS_LABEL, (String) view.getTag());
        sendMsgReportAnalyticsEvent(b);

        if (isBound) {
            if (VERBOSE) Log.v(TAG,"sending message to block ");
            Message msg = Message.obtain(null, DataHandlingService.MSG_BLOCK_USER);
            b = new Bundle();
            b.putString(Constants.MESSAGE_TARGET, (String) view.getTag());
            msg.setData(b);

            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to block user",e);
            }
        }
    }

    @Override
    public void createLiveThread() {
        mPager.setCurrentItem(LIVE_LIST_POSITION);
    }

    public void takeLivePicture() {
        mPager.setCurrentItem(CAMERA_LIST_POSITION);
        CameraFragReference.get().startLiveMode();
    }

    public void takeReplyPicture() {
        mPager.setCurrentItem(CAMERA_LIST_POSITION);
        CameraFragReference.get().startReplyMode();
    }

    public void sendMsgRequestReplies(int threadNumber) {
        Log.i(TAG, "refreshing replies for thread: " + threadNumber);

        if (isBound) {
            try {
                mService.send(Message.obtain(null,DataHandlingService.MSG_REQUEST_REPLIES, threadNumber, 0));
            } catch (RemoteException e) {
                Log.e(TAG, "error sending message to background service...", e);
            }
        }
    }

    /**
     * method 'sendImageToLocal'
     *
     * {@link com.jokrapp.android.CameraFragment.OnCameraFragmentInteractionListener}
     *
     * notifys the mainActivity that an image was just saved, so that the activity
     * can message the service to send this image to the server
     *
     * @param filePath the file path of the image saved
     * @param currentCamera the camera that the image was taken with
     */
    public void sendImageToLocal(String filePath, int currentCamera, String text) {
        if (VERBOSE) Log.v(TAG,"entering sendImageToLocal...");

        if (BuildConfig.FLAVOR.equals("sales") || Constants.client_only_mode) {
            Random randomgen = new Random(System.currentTimeMillis());

            Log.w(TAG, "client only mode is enabled... saving internally");
            ContentValues values = new ContentValues();
            values.put(SQLiteDbContract.LocalEntry.COLUMN_ID, randomgen.nextInt());
            values.put(SQLiteDbContract.LocalEntry.COLUMN_NAME_FILEPATH, filePath);
            values.put(Constants.KEY_TEXT, text);
            values.put(SQLiteDbContract.LocalEntry.COLUMN_FROM_USER, "client-only-mode enabled");
            values.put(SQLiteDbContract.LocalEntry.COLUMN_NAME_TIME, randomgen.nextInt());
            values.put(SQLiteDbContract.LocalEntry.COLUMN_NAME_RESPONSE_ARN,
                    AWSMobileClient.defaultMobileClient().getPushManager().getEndpointArn());

            if (messageTarget != null) {
                Log.d(TAG, "Sending message internally : " + messageTarget);

                getContentResolver().insert(FireFlyContentProvider.CONTENT_URI_MESSAGE, values);
                messageTarget = null;
            } else {
                Log.d(TAG, "Saving local post...");

                values.put(SQLiteDbContract.LocalEntry.COLUMN_NAME_WEIGHT, randomgen.nextInt());
                values.put(SQLiteDbContract.LocalEntry.COLUMN_NAME_LATITUDE, String.valueOf(randomgen.nextDouble()));
                values.put(SQLiteDbContract.LocalEntry.COLUMN_NAME_LONGITUDE, String.valueOf(randomgen.nextDouble()));
                values.put(SQLiteDbContract.LocalEntry.COLUMN_NAME_RESPONSE_ARN,
                        AWSMobileClient.defaultMobileClient().getPushManager().getEndpointArn());

                getContentResolver().insert(FireFlyContentProvider.CONTENT_URI_LOCAL,values);
            }

            return;
        }


        if (isBound) {
            Log.d(TAG, "sending message to server containing filepath to load...");
            Message msg = Message.obtain(null, DataHandlingService.MSG_SEND_IMAGE,currentCamera,0);
            Bundle b = new Bundle();
            b.putString(SQLiteDbContract.LocalEntry.COLUMN_NAME_RESPONSE_ARN,
                    AWSMobileClient.defaultMobileClient().getPushManager().getEndpointArn());
            b.putString(SQLiteDbContract.LocalEntry.COLUMN_NAME_FILEPATH, filePath);
            b.putString(SQLiteDbContract.LocalEntry.COLUMN_NAME_TEXT, text);
            b.putString(Constants.KEY_S3_KEY, filePath);
            if (messageTarget != null) {
                Log.d(TAG, "Sending message to user : " + messageTarget);
                b.putString(SQLiteDbContract.MessageEntry.COLUMN_RESPONSE_ARN, messageTarget);
                b.putString(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_MESSAGE_DIRECTORY);
                msg = Message.obtain(null, DataHandlingService.MSG_UPLOAD_IMAGE);
                msg.setData(b);

                //DOING SOMETHING ELSE NOW, BECAUSE TESTING AND LEARNING REASONS
                //todo reimplement proper systems
                try {
                    if (VERBOSE) Log.v(TAG, "sending message to upload static content...");
                    mService.send(msg);

                    if (VERBOSE) Log.v(TAG, "now sending dynamic content via Amazon SNS...");
                    AWSMobileClient.defaultMobileClient().getPushManager().publishMessage(b);

                    if (VERBOSE) Log.v(TAG, "now logging that this message was sent...");

                    String[] projection = {SQLiteDbContract.MessageEntry.COLUMN_RESPONSE_ARN};
                    String[] arn = {messageTarget};
                    Cursor c = getContentResolver().query(
                            FireFlyContentProvider.CONTENT_URI_MESSAGE,
                            projection,
                            SQLiteDbContract.MessageEntry.COLUMN_RESPONSE_ARN + "= ?",
                            arn,
                            null);

                    if (c == null) {
                        if (VERBOSE) Log.v(TAG, "Cursor was null... not storing pending row...");

                        ContentValues values = new ContentValues();
                        values.put(SQLiteDbContract.MessageEntry.COLUMN_NAME_TIME,
                                String.valueOf(System.currentTimeMillis()));
                        values.put(SQLiteDbContract.MessageEntry.COLUMN_FROM_USER,messageTarget);
                        values.put(SQLiteDbContract.MessageEntry.COLUMN_NAME_TEXT, text);
                        getContentResolver().insert(FireFlyContentProvider.CONTENT_URI_MESSAGE,
                                values);
                    } else if (c.getCount() > 0) {
                        if (VERBOSE) Log.v(TAG, "Reply was already received... " +
                                "not storing pending row...");
                        c.close();
                    } else {
                        if (VERBOSE) Log.v(TAG, "Storing pending row... : arn " + messageTarget);

                        ContentValues values = new ContentValues();
                        values.put(SQLiteDbContract.MessageEntry.COLUMN_NAME_TIME,
                                String.valueOf(System.currentTimeMillis()));
                        values.put(SQLiteDbContract.MessageEntry.COLUMN_RESPONSE_ARN,messageTarget);
                        getContentResolver().insert(FireFlyContentProvider.CONTENT_URI_MESSAGE,
                                values);
                        c.close();
                    }

                } catch (AmazonClientException e) {
                    Log.e(TAG,"AmazonClientException when sending message to user...",e);
                } catch (RemoteException e) {
                    Log.e(TAG,"error sending message to upload image...",e);
                }


                messageTarget = null;
                if (VERBOSE) Log.w(TAG,"remove this strange system sah");
                if (VERBOSE) Log.v(TAG,"exiting sendImageToLocal...");
                return;

            } else {
                Log.d(TAG, "Sending local broadcast...");
            }
            msg.setData(b);
            try {
                msg.replyTo = messageHandler;
                mService.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        messageTarget = null;
        if (VERBOSE) Log.v(TAG,"exiting sendImageToLocal...");
    }


    /**
     * class 'MainAdapter'
     * <p>
     * handles the fragment instantiations, instantiating all of the fragments
     * permanently, and allows their navigation to be swipeable
     * <p>
     * makes use of the android support library
     */
    public static class MainAdapter extends FragmentStatePagerAdapter {

        private int count;

        public MainAdapter(FragmentManager fm, int count) {
            super(fm);
            this.count = count;
        }

        @Override
        public int getCount() {
            return count;
        }

        /**
         * instantiates each fragment, and places at its individual placement
         *f
         * uses weakreferences to each fragment to ensure calls are handled properly
         *
         * @param position current position the pager to place the fragment 0 through 2
         * @return the fragment to be instantiated
         */
        @Override
        public Fragment getItem(int position) {
            if (VERBOSE) {
                Log.v(TAG,"getting item at position " + position);
            }

            Fragment out;

            switch (position) {
                case MESSAGE_LIST_POSITION:
                    if (MessageFragReference.get() == null){
                        MessageFragReference = new WeakReference<>(new MessageFragment());
                    }

                    out = MessageFragReference.get();
                    break;

                case LOCAL_LIST_POSITION:
                    if (LocalFragReference.get() == null) {
                        LocalFragReference = new WeakReference<>(new LocalFragment());
                    }

                    out = LocalFragReference.get();
                    break;

                case CAMERA_LIST_POSITION:
                    if (CameraFragReference.get() == null){
                        CameraFragReference = new WeakReference<>(CameraFragment.newInstance(0));
                    }

                    out = CameraFragReference.get();
                    break;

                case LIVE_LIST_POSITION:
                    if (LiveFragReference.get() == null){
                        LiveFragReference = new WeakReference<>(LiveFragment.newInstance("a","a"));
                    }

                    out = LiveFragReference.get();
                    break;

                case REPLY_LIST_POSITION:
                    if (ReplyFragReference.get() == null) {
                        ReplyFragment f = ReplyFragment.newInstance(LiveFragReference.get()
                                .getCurrentThread());
                        ReplyFragReference = new WeakReference<>(f);
                        out = f;
                    } else {
                        out = ReplyFragReference.get();
                    }

                    break;

                case 5:
                    out = new PushDemoFragment();
                    break;

                case 6:
                    out = new ContentDeliveryDemoFragment();
                    break;

                default:
                    out = new PhotoFragment();
            }

            return out;
        }

        /**
         * method 'getPageTitle'
         *
         * called by the PagerTitleStrip within the ViewPager
         * used to title the various tabs of the ViewPager
         *
         * @param position of the page who's title needs to be returned
         * @return String of the title
         */
        @Override
        public CharSequence getPageTitle(int position) {
                    switch (position) {
               //     case MESSAGE_LIST_POSITION:
//                        return MESSAGE_PAGER_TITLE;
  //                  case LOCAL_LIST_POSITION:
    //                    return LOCAL_PAGER_TITLE;
                    case CAMERA_LIST_POSITION:
                        return CAMERA_PAGER_TITLE;
                    case LIVE_LIST_POSITION:
                        return LIVE_PAGER_TITLE;
                    case REPLY_LIST_POSITION:
                        return REPLY_PAGER_TITLE;
                }

                return null;
            }
    }

    @Override
    public void onPageScrollStateChanged(int state) {

    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

    }

    private final int TAB_TRANSITION_DURATION = 300;

    @Override
    public void onPageSelected(int position) {

        //View tabStrip = mPager.findViewById(R.id.pager_tab_strip);
        //View settingsDrawer = findViewById(R.id.drawer_settings);

        switch (position) {
            case CAMERA_LIST_POSITION:
                setAnalyticsScreenName(("Fragment :" + CAMERA_PAGER_TITLE));
                //tabStrip.animate().scaleY(0).setDuration(TAB_TRANSITION_DURATION);
                //tabStrip.setVisibility(View.VISIBLE);
                //settingsDrawer.setVisibility(View.VISIBLE);
                break;

          /*  case LOCAL_LIST_POSITION:
                setAnalyticsScreenName(("Fragment :" + LOCAL_PAGER_TITLE));
                //tabStrip.setVisibility(View.VISIBLE);
                //settingsDrawer.setVisibility(View.VISIBLE);
                break;

            case MESSAGE_LIST_POSITION:
                setAnalyticsScreenName(("Fragment :" + MESSAGE_PAGER_TITLE));
                //tabStrip.setVisibility(View.GONE);
                //settingsDrawer.setVisibility(View.GONE);
                break;*/

            case LIVE_LIST_POSITION:
                setAnalyticsScreenName(("Fragment :" + LIVE_PAGER_TITLE));
                //tabStrip.setVisibility(View.VISIBLE);
                //settingsDrawer.setVisibility(View.VISIBLE);
                break;

            case REPLY_LIST_POSITION:
                setAnalyticsScreenName(("Fragment :" + REPLY_PAGER_TITLE));
                //tabStrip.setVisibility(View.GONE);
                //settingsDrawer.setVisibility(View.GONE);
                break;
        }
    }


    /**
     * This class uses the broadcast receiver framework to detect incoming broadcast Intents
     * and change the currently-visible fragment based on the Intent action.
     * It adds or replaces Fragments as necessary, depending on how much screen real-estate is
     * available.
     */
    private class FragmentDisplayer extends BroadcastReceiver {

        // Default null constructor
        public FragmentDisplayer() {

            // Calls the constructor for BroadcastReceiver
            super();
        }
        /**
         * Receives broadcast Intents for viewing or zooming pictures, and displays the
         * appropriate Fragment.
         *
         * @param context The current Context of the callback
         * @param intent The broadcast Intent that triggered the callback
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.LOGV) Log.v(TAG,"received intent to fullscreen or zoom...");

            // Declares a local FragmentManager instance
            FragmentManager fragmentManager1;

            // Declares a local instance of the Fragment that displays photos
            PhotoFragment photoFragment;

            // Stores a string representation of the URL in the incoming Intent
            String urlString;

            // Stores a directory representation, which shows which directory to fetch this image from
            String directory;

            //is this a preview or just a fullscreen?
            boolean isPreview;

            // If the incoming Intent is a request is to view an image
            if (intent.getAction().equals(Constants.ACTION_VIEW_IMAGE)) {
                if (VERBOSE) Log.v(TAG,"Fullscreen intent received...");

                // Gets an instance of the support library fragment manager
                fragmentManager1 = getFragmentManager();

                // Gets a handle to the Fragment that displays photos
                photoFragment =
                        (PhotoFragment) fragmentManager1.findFragmentByTag(
                                Constants.PHOTO_FRAGMENT_TAG
                        );

                // Gets the URL of the picture to display
                urlString = intent.getExtras().getString(Constants.KEY_S3_KEY,"");
                directory = intent.getExtras().getString(Constants.KEY_S3_DIRECTORY,"");
                isPreview = intent.getExtras().getBoolean(Constants.KEY_PREVIEW_IMAGE,false);


                if (Constants.LOGV) Log.v(TAG,"Received string is: " + urlString);
                if (Constants.LOGV) Log.v(TAG,"Received directory string is: " + urlString);

                // If the photo Fragment exists from a previous display
                if (null != photoFragment) {
                    if (VERBOSE) Log.v(TAG,"PhotoFragment already existed...");

                    // If the incoming URL is not already being displayed
                    if (!urlString.equals(photoFragment.getImageKeyString())) {

                        // Sets the Fragment to use the URL from the Intent for the photo
                        photoFragment.setPhoto(directory,urlString,isPreview);

                        Log.w(TAG,"for now, replies is assumed for fullscreened images...");

                        // Loads the photo into the Fragment
                        photoFragment.loadPhoto();
                    }

                    // If the Fragment doesn't already exist
                } else {
                    if (VERBOSE) Log.v(TAG,"PhotoFragment didn't exist, creating new..");
                    // Instantiates a new Fragment
                    photoFragment = new PhotoFragment();

                    // Sets the Fragment to use the URL from the Intent for the photo
                    photoFragment.setPhoto(directory, urlString,isPreview);
                    Log.w(TAG,"for now, replies is assumed for fullscreened images...");

                    // Starts a new Fragment transaction
                    FragmentTransaction localFragmentTransaction2 =
                            fragmentManager1.beginTransaction();

                    // If the fragments are side-by-side, adds the photo Fragment to the display
                    if (mSideBySide) {
                        if (VERBOSE) Log.v(TAG,"side-by-side enabled, adding to display...");

                        localFragmentTransaction2.add(
                                R.id.fragmentHost,
                                photoFragment,
                                Constants.PHOTO_FRAGMENT_TAG
                        );
                    /*
                     * If the Fragments are not side-by-side, replaces the current Fragment with
                     * the photo Fragment
                     */
                    } else {
                        if (VERBOSE) Log.v(TAG,"side-by-side disabled, replacing display...");
                        localFragmentTransaction2.replace(
                                R.id.fragmentHost,
                                photoFragment,
                                Constants.PHOTO_FRAGMENT_TAG);

                    }

                    // Don't remember the transaction (sets the Fragment backstack to null)
                    localFragmentTransaction2.addToBackStack(null);

                    // Commits the transaction
                    localFragmentTransaction2.commit();

                }

                // If not in side-by-side mode, sets "full screen", so that no controls are visible
              //  if (!mSideBySide) setFullScreen(true);

            /*
             * If the incoming Intent is a request to zoom in on an existing image
             * (Notice that zooming is only supported on large-screen devices)
             */
            } else if (intent.getAction().equals(Constants.ACTION_ZOOM_IMAGE)) {
                if (VERBOSE) Log.v(TAG,"Zoom intent received...");

                // If the Fragments are being displayed side-by-side
                if (mSideBySide) {

                    // Gets another instance of the FragmentManager
                    FragmentManager localFragmentManager2 = getFragmentManager();

                    // Gets a thumbnail Fragment instance
                    PhotoThumbnailFragment localThumbnailFragment =
                            (PhotoThumbnailFragment) localFragmentManager2.findFragmentByTag(
                                    Constants.THUMBNAIL_FRAGMENT_TAG);

                    // If the instance exists from a previous display
                    if (null != localThumbnailFragment) {

                        // if the existing instance is visible
                        if (localThumbnailFragment.isVisible()) {

                            // Starts a fragment transaction
                            FragmentTransaction localFragmentTransaction2 =
                                    localFragmentManager2.beginTransaction();

                            /*
                             * Hides the current thumbnail, clears the backstack, and commits the
                             * transaction
                             */
                            localFragmentTransaction2.hide(localThumbnailFragment);
                            localFragmentTransaction2.addToBackStack(null);
                            localFragmentTransaction2.commit();

                            // If the existing instance is not visible, display it by going "Back"
                        } else {

                            // Pops the back stack to show the previous Fragment state
                            localFragmentManager2.popBackStack();
                        }
                    }

                    // Removes controls from the screen
               //     setFullScreen(true);
                }
            }
        }
    }


    /***********************************************************************************************
     * INITIALIZE
     *
     */
    /**
     * method checkForLocationEnabled
     * <p>
     * tests if the location services are enables
     *
     * @param context context of the location to be testing
     */
    public static void checkForLocationEnabled(final Context context) {

        LocationManager lm = null;
        boolean gps_enabled = false, network_enabled = false;
        if (lm == null)
            lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        try {
            network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        if (!gps_enabled && !network_enabled) {
            final AlertDialog.Builder dialog = new AlertDialog.Builder(context); //todo make this nicer
            dialog.setTitle(R.string.gps_network_not_enabled_title);
            dialog.setMessage(R.string.gps_network_not_enabled_message);
            dialog.setPositiveButton(R.string.open_location_settings, new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface paramDialogInterface, int paramInt) {
                    Intent myIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    context.startActivity(myIntent);
                }
            });
            dialog.setNegativeButton(R.string.Cancel, new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface paramDialogInterface, int paramInt) {
                    paramDialogInterface.dismiss();
                }
            });
            dialog.show();

        }
    }

    /**
     * method 'sendMsgRequestLocalPosts'
     *
     * requests images from the service
     *
     * @param num of images to request from server
     */
    public void sendMsgRequestLocalPosts(int num) {

        if (isBound) {
            Log.d(TAG, "sending message to request " + num + " images");
            Message msg = Message.obtain(null, DataHandlingService.MSG_REQUEST_LOCAL_POSTS,num, 0);
            Bundle data = new Bundle();
            data.putInt(Constants.IMAGE_COUNT, num);
            msg.setData(data);

            try {
                msg.replyTo = messageHandler;
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "error sending message to request images", e);
            }
        }
    }

    /**
     * method 'sendMsgRequestMessages'
     *
     * sends a message to the imageRequestService to request messages
     */
    public void sendMsgRequestLocalMessages() {
        if (isBound) {
            Log.d(TAG, "sending message to request Local messages");
            Message msg = Message.obtain(null, DataHandlingService.MSG_REQUEST_MESSAGES);
            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "error sending message to request images", e);
            }
        }
    }

    /** LIVE POST and REPLY HANDLING
     */
    Bundle liveData;
    private boolean cancelPost = false;

    Bundle replyData;
    private boolean cancelReply = false;

    /**
     * method 'sendMsgCreateThread'
     *
     * send a message to the service to pass the data on to the server
     *
     */
    public void sendMsgCreateThread(Bundle liveData) {
        if (VERBOSE) Log.v(TAG,"entering sendMsgCreateThread...");

        if (BuildConfig.FLAVOR.equals("sales") || Constants.client_only_mode) {
            Random randomgen = new Random(System.currentTimeMillis());

            Log.w(TAG, "client only mode is enabled... saving internally");
            ContentValues values = new ContentValues();
            values.put(SQLiteDbContract.LiveEntry.COLUMN_ID, randomgen.nextInt());
            values.put(SQLiteDbContract.LiveEntry.COLUMN_NAME_THREAD_ID,randomgen.nextInt());
            values.put(SQLiteDbContract.LiveEntry.COLUMN_NAME_TIME, randomgen.nextInt());
            values.put(SQLiteDbContract.LiveEntry.COLUMN_NAME_NAME,
                    liveData.getString("name", ""));
            values.put(SQLiteDbContract.LiveEntry.COLUMN_NAME_TITLE,
                    liveData.getString("title",""));
            values.put(SQLiteDbContract.LiveEntry.COLUMN_NAME_FILEPATH,
                    liveData.getString(Constants.KEY_S3_KEY,""));
            values.put(SQLiteDbContract.LiveEntry.COLUMN_NAME_DESCRIPTION,
                    liveData.getString("description", ""));

            getContentResolver().insert(FireFlyContentProvider.CONTENT_URI_LIVE, values);


            if (VERBOSE) Log.v(TAG,"exiting sendMsgCreateThread... saved locally");
            return;
        }


        if (isBound) {
            Message msg = Message.obtain(null, DataHandlingService.MSG_CREATE_THREAD);
            msg.setData(liveData);
            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to create live thread",e);
            }
        }

        if (VERBOSE) Log.v(TAG,"exiting sendMsgCreateThread...");
    }

    private void sendMsgCreateReply(Bundle replyData) {
        if (VERBOSE) {
            Log.v(TAG,"entering sendMsgCreateReply");
            LogUtils.printBundle(replyData,TAG);
        }

        if (BuildConfig.FLAVOR.equals("sales") || Constants.client_only_mode) {
            Random randomgen = new Random(System.currentTimeMillis());

            Log.w(TAG, "client only mode is enabled... saving internally");
            ContentValues values = new ContentValues();
            values.put(SQLiteDbContract.LiveReplies.COLUMN_ID, randomgen.nextInt());
            values.put(SQLiteDbContract.LiveReplies.COLUMN_NAME_TIME, randomgen.nextInt());

            values.put(SQLiteDbContract.LiveReplies.COLUMN_NAME_NAME,
                    replyData.getString("name", ""));
            values.put(SQLiteDbContract.LiveReplies.COLUMN_NAME_FILEPATH,
                    replyData.getString(Constants.KEY_S3_KEY));
            values.put(SQLiteDbContract.LiveReplies.COLUMN_NAME_DESCRIPTION,
                    replyData.getString("description", ""));
            values.put(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID,
                    replyData.getInt("threadID"));

                    getContentResolver()
                            .insert(FireFlyContentProvider
                                    .CONTENT_URI_REPLY_LIST, values);

            if (VERBOSE) Log.v(TAG,"exiting sendMsgCreateThread... saved locally");
            return;
        }

        if (isBound) {
            Message msg = Message.obtain(null, DataHandlingService.MSG_REPLY_TO_THREAD);
            msg.setData(replyData);
            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to reply to thread",e);
            }
        }
        if (VERBOSE) Log.v(TAG,"exiting sendMsgCreateReply");
    }

    /**
     * method 'setLiveCreateThreadInfo'
     *
     * add the thread info to the iveData info bundle,
     * when the bundle posses all 4 attributes, send the message
     *
     * @param name the name the user used to create the thread with
     * @param title the title that was provided
     * @param description the description text
     * */
    public void setLiveCreateThreadInfo(String name, String title, String description) {
        if (liveData == null) {
            liveData = new Bundle(3);
        }
        liveData.putString(SQLiteDbContract.LiveEntry.COLUMN_NAME_NAME,name);
        liveData.putString(SQLiteDbContract.LiveEntry.COLUMN_NAME_TITLE,title);
        liveData.putString(SQLiteDbContract.LiveEntry.COLUMN_NAME_DESCRIPTION,description);

        Log.d(TAG, "size is " + liveData.size());

        if (liveData.size() >= 4) {
            sendMsgCreateThread(liveData);
            liveData.clear();
            //liveData = null;
        }
    }

    /**
     * method 'setLiveCreateInfo'
     *
     * overload, this version loads name from sharedPreferences and passes to main version
     *
     * @param title title of thread
     * @param description description for thread
     */
    public void setLiveCreateThreadInfo(String title, String description) {
        String name = getSharedPreferences(Constants.SHARED_PREFERENCES_NAME,MODE_PRIVATE).
                getString(StashLiveSettingsFragment.LIVE_NAME_KEY,"jester");
        setLiveCreateThreadInfo(name, title, description);
    }

    public void setLiveCreateReplyInfo(String comment, int threadID) {
        if (VERBOSE) Log.v(TAG,"enter setLiveCreateReplyInfo with "  + comment + ", " + threadID);

        String name = getSharedPreferences(Constants.SHARED_PREFERENCES_NAME,MODE_PRIVATE).
                getString(StashLiveSettingsFragment.LIVE_NAME_KEY,"jester");
        setLiveCreateReplyInfo(name, comment, threadID);


        if (VERBOSE) Log.v(TAG,"exiting setLiveCreateReplyInfo");
    }


    public void setLiveCreateReplyInfo(String comment) {
        if (VERBOSE) Log.v(TAG,"enter setLiveCreateReplyInfo with "  + comment);

        String name = getSharedPreferences(Constants.SHARED_PREFERENCES_NAME,MODE_PRIVATE).
                getString(StashLiveSettingsFragment.LIVE_NAME_KEY,"jester");
        setLiveCreateReplyInfo(name, comment, replyData.
                getInt(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID));


        if (VERBOSE) Log.v(TAG,"exiting setLiveCreateReplyInfo");
    }


    public void setLiveCreateReplyInfo(String name, String comment, int threadID) {
        if (VERBOSE) Log.v(TAG,"enter setLiveCreateReplyInfo with " + name + ", " + comment + ", " + threadID);

        if (replyData == null) {
            replyData = new Bundle(3);
        }

        replyData.putString(SQLiteDbContract.LiveReplies.COLUMN_NAME_NAME,name);
        replyData.putString(SQLiteDbContract.LiveReplies.COLUMN_NAME_DESCRIPTION, comment);
        replyData.putInt(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID, threadID);

        if (replyData.size() >= 4) {
            sendMsgCreateReply(replyData);
            replyData = null;
        }

        if (VERBOSE) Log.v(TAG,"exiting setLiveCreateReplyInfo");
    }

    public void clearReplyInfo() {
        if (VERBOSE) Log.v(TAG,"reply picture mode was cancelled, clearing info.");
        replyData.clear();
    }

    private static final int UPLOAD_TO_LIVE = 1;

    private static final int UPLOAD_TO_REPLY = 2;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//        super.onActivityResult(requestCode, resultCode, data);

        /*
         * Creates a new Intent to get the full picture for the thumbnail that the user clicked.
         * The full photo is loaded into a separate Fragment
         */

        String selectedImagePath;
        Uri selectedImageUri;
        if (resultCode == RESULT_OK) {

            selectedImageUri = data.getData();
            selectedImagePath = ImageUtils.getPath(this, selectedImageUri);

            String key = UUID.randomUUID().toString();

            File destFile = new File(getCacheDir(),key);
            try {
                ImageUtils.copyFile(new File(selectedImagePath),destFile);
            } catch (IOException e) {
                Log.e(TAG,"error copying file...",e);
           Toast.makeText(this,"error copying file to internal cache...",Toast.LENGTH_SHORT).show();
                return;
            }

            if (requestCode == UPLOAD_TO_REPLY) {
                Bitmap image = BitmapFactory.decodeFile(destFile.toString());
                Bitmap thumbnail = ThumbnailUtils.extractThumbnail(image, 250, 250);

                try {
                    FileOutputStream fos = new FileOutputStream(destFile.toString() + "s");
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                } catch (FileNotFoundException e) {
                    Log.e(TAG,"file not found when writing thumbnail...",e);
                    Toast.makeText(this,"Error saving thumbnail... quitting..",Toast.LENGTH_SHORT)
                            .show(); //todo handle this better
                    return;
                }
            }


            Intent localIntent =
                    new Intent(Constants.ACTION_VIEW_IMAGE)
                            .putExtra(Constants.KEY_S3_KEY, key)
                            .putExtra(Constants.KEY_PREVIEW_IMAGE,true);
            //disableScrolling();

            switch (requestCode) {
                case UPLOAD_TO_LIVE:
                   setLiveFilePath(key);
                  localIntent.putExtra(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_LIVE_DIRECTORY);
                    break;

                case UPLOAD_TO_REPLY:
                    setReplyFilePath(key);
               localIntent.putExtra(Constants.KEY_S3_DIRECTORY, Constants.KEY_S3_REPLIES_DIRECTORY);
                    break;
            }

            mFragmentDisplayer.onReceive(this,localIntent);



        } else if(resultCode == RESULT_CANCELED) {
            switch (requestCode) {
                case UPLOAD_TO_LIVE:
                    liveData.clear();
                    break;

                case UPLOAD_TO_REPLY:
                    replyData.clear();
                    break;
            }
        }

    }

    /**
     * method 'loadFromStash'
     *
     * starts a chooser activity to select an image and send it to the server
     *
     * @param v the view of the button which called this method
     */
    public void loadFromStash(View v) {

        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        int requestCode;
        switch (v.getId()) {
            //case R.id.button_live_load:
              //  requestCode = UPLOAD_TO_LIVE;
//                break;

            case R.id.button_reply_load:
                requestCode = UPLOAD_TO_REPLY;

                if (replyData == null) replyData = new Bundle();

                replyData.putInt(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID,
                        ReplyFragReference.get().getCurrentThread());
                break;
            default:
                /* If you're not live load or reply load, then why are you calling this method?*/
                throw new RuntimeException("Invalid Source button");
        }


        startActivityForResult(Intent.createChooser(intent,
                "Select Picture"), requestCode);

    }

    @Override
    public void dismissPreview(PhotoFragment me) {
        getFragmentManager().beginTransaction().remove(me).commit();
    }

    /**
     * method 'setLiveFilePath'
     *
     * add the filepath to the liveData info bundle,
     * when the bundle posses all 4 attributes, send the message
     *
     * if the user has aborted the live post before this method is called, it will instead
     * use the file path to delete the image from internal storabge
     *
     * @param filePath path to where the image is
     */
    public void setLiveFilePath(String filePath) {
        if (cancelPost) { //true if the user aborted posting to live
            new File(filePath).delete();
            cancelPost = false;
            liveData.clear();
            return;
        }
        if (liveData == null) {
            liveData = new Bundle(1);
        }
        liveData.putString(Constants.KEY_S3_KEY, filePath);
        liveData.putString(SQLiteDbContract.LiveEntry.COLUMN_NAME_FILEPATH, filePath);
        if (liveData.size() >= 5) {
            sendMsgCreateThread(liveData);
            liveData.clear();
        }
    }


    public void setReplyFilePath(String filePath) {
        if (VERBOSE) Log.v(TAG,"entering setReplyFilepath" + filePath);

        if (cancelReply) {
            new File(filePath).delete();
            cancelPost = false;
            replyData.clear();
            return;
        }

        if (replyData == null) {
            replyData = new Bundle(1);
        }
        replyData.putString(Constants.KEY_S3_KEY,filePath);
        replyData.putString(SQLiteDbContract.LiveReplies.COLUMN_NAME_FILEPATH,filePath);
        if (replyData.size() >= 4) {
            sendMsgCreateReply(replyData);
            replyData.clear();
        }
        if (VERBOSE) Log.v(TAG, "exiting setReplyFilepath" + filePath);
    }

    public void setCurrentThread(String thread) {
        if (ReplyFragReference.get()!=null) {
            ReplyFragReference.get().setCurrentThread(thread);
        }
    }

    public void setReplyComment(String comment) {
        replyData.putString("description",comment);
    }

    public String getReplyComment() {
        return replyData.getString("description","");
    }

    public void removePendingLiveImage() {
        //todo, cancel image saving process if it has not saved it
       if (liveData!=null) { //if the image has already been saved and set, delete it
           String path = liveData.getString(Constants.KEY_S3_KEY);
           if (path!=null) {
               new File(path).delete();
           }
           liveData.clear();
       } else { //if not, make sure that when it is, its deleted.
           cancelPost = true;
       }
    }

    public void sendMsgRequestLiveThreads() {
        Log.i(TAG,"requesting a new live thread list...");
        if (isBound) {
            Message msg = Message.obtain(null, DataHandlingService.MSG_REQUEST_LIVE_THREADS);
            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to create request live threads",e);
            }
        }
    }

    public void sendMsgDownloadImage(String s3Directory, String s3Key) {
        Log.i(TAG,"send a message to download image from: " + s3Directory + " / " + s3Key);
        if (isBound) {
            Message msg = Message.obtain(null, DataHandlingService.MSG_DOWNLOAD_IMAGE);
            Bundle b = new Bundle(2);
            b.putString(Constants.KEY_S3_KEY,s3Key);
            b.putString(Constants.KEY_S3_DIRECTORY,s3Directory);
            msg.setData(b);

            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to create request live threads",e);
            }
        } else {
            Log.e(TAG,"service was not bound!");
        }
    }

    /***********************************************************************************************
     * CAMERA HANDLING
     *
     */
    /**
     * Create a file Uri for saving an image or video
     */

    /** CAMERA HANDLER COMMUNICATION - HANDLER THREAD
     */
    public static final int MSG_CONNECT_CAMERA = 0;
    public static final int MSG_DISCONNECT_CAMERA = 1;
    public static final int MSG_START_PREVIEW = 2;
    public static final int MSG_STOP_PREVIEW = 3;
    public static final int MSG_ACTIVITY_DESTROYED = 4;
    public static final int MSG_TAKE_PICTURE = 5;
    public static final int MSG_SAVE_PICTURE = 6;
    public static final int MSG_SWITCH_CAMERA = 7;
    public static final int MSG_FLASH_ON = 8;
    public static final int MSG_FLASH_OFF = 9;
    public static final int MSG_AUTOFOCUS = 10;

    /**
     * class 'CameraHanderThread'
     *
     * this handler thread, an implementation of looper, serves as the background thread
     * to receive messages for the camera.
     *
     * the camera connection, image capturing, etc, is managed in this thread and this thread only
     */
    /*class CameraHandlerThread extends HandlerThread {
        private CameraHandler cameraHandler;



        CameraHandlerThread(MainActivity parent) {
            super("CameraHandlerThread");
            start();
            cameraHandler = new CameraHandler(getLooper());
            cameraHandler.setParent(parent);
        }

        public CameraHandler getCameraHandler() {
            return cameraHandler;
        }

    }*/

   // private static WeakReference<CameraHandler> cameraHandlerWeakReference = new WeakReference<>(null);

   /* public synchronized CameraHandler getCameraHandlerSingleton(MainActivity activity) {
        if (cameraHandlerWeakReference.get() == null) {
            if (VERBOSE) Log.v(TAG,"reference to camera handler did not exist... creating new cameraHandler...");

            HandlerThread handlerThread = new HandlerThread("CameraHandlerThread");

            //must start handlerthread prior to getLooper() else it will return null
            handlerThread.start();
            CameraHandler handler = new CameraHandler(handlerThread.getLooper());
            handler.setParent(activity);
            Log.i(TAG, "New CameraHandler created and set: " + handler.toString());

            cameraHandlerWeakReference = new WeakReference<>(handler);
        } else {
            if (VERBOSE)  Log.v(TAG,"reference to camera handler did already existed... grabbing existing...");
        }

        return cameraHandlerWeakReference.get();
    }*/

    private static SurfaceTexture mSurface;
    private static Camera mCamera; //static to prevent garbage collection during onStop

    /**
     * class 'CameraHandler'
     *
     * Handles camera operation requests from other threads.  Necessary because the Camera
     * must only be accessed from one thread.
     *
     * Draws to a TextureView, which is stored in {@link CameraFragment}
     *
     * It is possible that a SurfaceView would be a more effective implementation //todo look into this
     */
    class CameraHandler extends Handler implements TextureView.SurfaceTextureListener, Camera.PictureCallback, Camera.ShutterCallback {
        private static final String TAG = "MainCameraHandler";
        private boolean isConnected = false;

        private static final int CAMERA_POSITION_BACK = 0;
        private static final int CAMERA_POSITION_FRONT = 1;

        /** SHARED PREFERENCES
         */
        private static final String FRONT_CAMERA_PARAMETERS = "fParams";
        private static final String BACK_CAMERA_PARAMETERS = "bParams";

        private Camera.CameraInfo cameraInfo;
        private Camera.Parameters parameters;

        private byte[] theData;

        private boolean meteringAreaSupported = false;

        private int width;
        private int height;


        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<MainActivity> mWeakActivity;

        public void setParent(MainActivity parent) {
            mWeakActivity = new WeakReference<>(parent);

            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(parent);
            /*
            if (currentCamera == CAMERA_POSITION_FRONT) {
                if (p.getString(FRONT_CAMERA_PARAMETERS,null)!=null){
                    parameters.unflatten(p.getString(FRONT_CAMERA_PARAMETERS,null));
                }
            } else {
                if (p.getString(BACK_CAMERA_PARAMETERS,null)!=null){
                    parameters.unflatten(p.getString(BACK_CAMERA_PARAMETERS,null));
                }
            }*/
        }

        public CameraHandler(Looper looper) {
            super(looper);
        }

        /**
         * method 'onSurfaceTextureAvailable'
         *
         * called when the textureView in {@link CameraFragment} is ready to use
         *
         * @param surface the SurfaceTexture that is ready to be used
         * @param width the width of the surface texture
         * @param height the height of the surface texture
         */
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (VERBOSE) {
                Log.v(TAG,"enter onSurfaceTextureAvailable");
            }
            mSurface = surface;
            this.width = width;
            this.height = height;

            if (mSurface != null) {
                Log.i(TAG, "surface was created and saved");
            }

                if (mCamera != null) {
                    Log.d(TAG, "Camera is connected and is available, setting and starting " +
                            "preview.");
                            try {
                                mCamera.setPreviewTexture(mSurface);
                                mCamera.startPreview();
                            } catch (Exception e) {
                                Log.e(TAG, "error setting preview texture to camera", e);
                                mWeakActivity.get().mTracker.getEventClient()
                                        .recordEvent(mWeakActivity.get().mTracker
                                                .getEventClient()
                                                .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                                .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                                        e.getMessage() + ":" + "" +
                       "onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height)"));
                            }
                } else {
                    Log.d(TAG, "camera was not available, saving surface...");
                    mSurface = surface;
                }

            if (VERBOSE) {
                Log.v(TAG,"exit onSurfaceTextureAvailable");
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            if (VERBOSE) Log.v(TAG,"enter onSurfaceTextureSizeChanged");

            if (mCamera == null ){
                mCamera = getCameraInstance(0);
            } else {
                mSurface = surface;
                if (parameters.getMaxNumMeteringAreas() > 0) {
                    this.meteringAreaSupported = true;
                }
                    this.width = width;
                    this.height = height;
            }

            if (VERBOSE) Log.v(TAG,"exit onSurfaceTextureSizeChanged");
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            if (VERBOSE) Log.v(TAG, "enter onSurfaceTextureDestroyed");

            if (mCamera != null) {
                if (isConnected) {
                    Log.d(TAG,"mCamera was not null, stopping preview");
                    mCamera.stopPreview();

                    Log.d(TAG,"abandoning bufferqueue");
                    mCamera.setPreviewCallbackWithBuffer(null);
                }
                if (mSurface != null) {
                    surface.release();
                    mSurface.release();
                    mSurface = null;
                }
            } else {
                Log.d(TAG, "mCamera was null, no action taken");
            }

            if (VERBOSE) Log.v(TAG,"exit onSurfaceTextureDestroyed");
            return true;
        }

        /**
         * method 'onSurfaceTextureUpdated'
         *
         * called every time the surface texture is redrawn
         *
         * @param surface the surface that was updated
         */
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler() {
            if (VERBOSE) {
                Log.v(TAG, "clearing weakreference to activity");
            }
            mWeakActivity.clear();
        }

        /**
         * method 'handleMessage'
         *
         * performs tasks based on incoming messages
         *
         * @param inputMessage the message that was sent
         */
        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            if (getLooper() == Looper.getMainLooper()) {
                Log.d(TAG,"this handler runs on the UI thread");
            }

            MainActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_CONNECT_CAMERA: //0
                    cameraInfo = new Camera.CameraInfo();
                    //Camera.getCameraInfo(currentCamera,cameraInfo);

                    Log.d(TAG,"connecting to camera, setting all attributes...");
                    mCamera = getCameraInstance(inputMessage.arg1);

                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        mCamera.setDisplayOrientation(cameraInfo.orientation-180);
                    } else {
                        mCamera.setDisplayOrientation(cameraInfo.orientation);
                    }

                    /*    SharedPreferences.Editor editor = PreferenceManager.
                                getDefaultSharedPreferences(mWeakActivity.get()).edit();
                        if (currentCamera == CAMERA_POSITION_FRONT) {
                            editor.putString(FRONT_CAMERA_PARAMETERS,parameters.flatten());
                        } else {
                            editor.putString(BACK_CAMERA_PARAMETERS,parameters.flatten());
                        }
                        Log.i(TAG,"storing calculated camera parameters to SharedPreferences");
                        editor.commit();
                    */
                    break;
                case MSG_DISCONNECT_CAMERA: //1
                    if (mCamera!=null) {
                        releaseCamera(mCamera);
                    }
                    mCamera = null;
                    break;

                case MSG_START_PREVIEW: //2
                    try {
                          Log.d(TAG,"setting and starting preview...");
                          Log.e(TAG,"this is disabled...");
                        mCamera.startPreview();
                    } catch (Exception e) {
                        Log.e(TAG, "generic error setting and starting preview", e);
                        mWeakActivity.get().mTracker.getEventClient()
                                .recordEvent(mWeakActivity.get().mTracker
                                        .getEventClient()
                                        .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                        .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                                e.getMessage() + ":" + "msg_start_preview"));
                    }
                    break;
                case MSG_STOP_PREVIEW: //3
                    if (mCamera != null) {
                        mCamera.stopPreview();
                    }
                    break;

                case MSG_TAKE_PICTURE: //5
                    mCamera.takePicture(this, null, this);
                    break;

                case MSG_SAVE_PICTURE: //6

                    if (theData == null) {
                        Log.e(TAG,"camera byte[] was null when it should not have been...");
                    } else {
                        saveImage(theData,
                                inputMessage.getData().getString(Constants.KEY_TEXT),
                                0,
                                inputMessage.arg2); // local vs live
                        theData = null;
                    }
                    break;
                case MSG_SWITCH_CAMERA: //7

                    Log.d(TAG,"switching to camera: " + inputMessage.arg1);

                    if (mCamera!=null) {
//                        Log.d(TAG,"abandoning bufferqueue");
                        mCamera.stopPreview();
                        mCamera.setPreviewCallbackWithBuffer(null);
                        releaseCamera(mCamera);
                    }
                    isConnected = false;
                    mCamera = getCameraInstance(inputMessage.arg1);

                    if (isConnected) {
                        Log.d(TAG,"camera isConnected");
                        try {
                            Log.d(TAG,"setting preview texture to mCamera...");
                            mCamera.setPreviewTexture(mSurface);
                        } catch (IOException e) {
                            Log.e(TAG, "error setting preview texture to camera", e);
                            mWeakActivity.get().mTracker.getEventClient()
                                    .recordEvent(mWeakActivity.get().mTracker
                                            .getEventClient()
                                            .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                            .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                                    e.getMessage() + ":" + "msg_switch_camera"));
                        }
                        mCamera.startPreview();
                    } else {
                        Log.e(TAG,"mCamera is not connected...");
                    }

                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        mCamera.setDisplayOrientation(cameraInfo.orientation-180);
                    } else {
                        mCamera.setDisplayOrientation(cameraInfo.orientation);
                    }
                    break;

                case MSG_FLASH_ON: //8

                    if (parameters.getSupportedFlashModes() != null) {
                        if (parameters.getSupportedFlashModes().
                                contains(Camera.Parameters.FLASH_MODE_ON)) {
                            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
                            mCamera.setParameters(parameters);
                        }
                    }
                    break;

                case MSG_FLASH_OFF: //9

                    if (parameters.getSupportedFlashModes() != null) {
                        if (parameters.getSupportedFlashModes().
                                contains(Camera.Parameters.FLASH_MODE_OFF)) {
                            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                            mCamera.setParameters(parameters);
                        }
                    }
                    break;

                case MSG_ACTIVITY_DESTROYED: //4
                    invalidateHandler();
                    break;

                case MSG_AUTOFOCUS:
                        focusOnTouch(inputMessage.getData().getFloat("x"),inputMessage.getData().getFloat("y"));
                    break;

                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }

        /**
         * convenience class to ease debugging
         */
        private void startPreview() {
            if (isConnected) {
                if (VERBOSE) {
                    Log.v(TAG, "starting camera preview");
                }
                mCamera.startPreview();
            } else {
                Log.d(TAG, "attempted to start preview but camera was not connected");
            }
        }


        /** A safe way to get an instance of the Camera object. */
        public Camera getCameraInstance(int whichCamera){
            if (VERBOSE) Log.v(TAG,"connecting to camera " + whichCamera);


            cameraInfo = new Camera.CameraInfo();

            Camera c = null;
            try {
                c = Camera.open(whichCamera); // attempt to get a Camera instance
                Camera.getCameraInfo(whichCamera,cameraInfo);
            }
            catch (Exception e){
                // Camera is not available (in use or does not exist)
                Log.e(TAG, "Error opening camera - dialog should show", e);
                new AlertDialog.Builder(mWeakActivity.get()).setTitle("Camera Failed to Open")
                        .setMessage("We couldn't connect to your Camera. Make sure no other " +
                                        "applications are currently using the Camera. You may need" +
                                        "to restart your phone.").setPositiveButton("Ok",
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });

                mWeakActivity.get().mTracker.getEventClient()
                        .recordEvent(mWeakActivity.get().mTracker
                                        .getEventClient()
                                        .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                        .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                                e.getMessage() + ":" + "getCameraInstance(int whichCamera)")
                        );
            }

            if (c != null) {
                isConnected = true;
                
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                c.setDisplayOrientation(cameraInfo.orientation - 180);

                mWeakActivity.get().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((CheckBox)findViewById(R.id.switch_camera))
                                .setBackgroundResource(R.drawable.switch_camera_selfie);

                    }
                });

            } else {
                c.setDisplayOrientation(cameraInfo.orientation);

                mWeakActivity.get().runOnUiThread(new Runnable() {
                    @Override
                    public void run() { //todo random crashes occur - sometimes the view isn't ready
                        CheckBox v = ((CheckBox)findViewById(R.id.switch_camera));
                        if (v != null) v.setBackgroundResource(R.drawable.switch_camera_default);
                    }
                });
            }

            int width = mWeakActivity.get().getResources().getDisplayMetrics().widthPixels;
            int height = mWeakActivity.get().getResources().getDisplayMetrics().heightPixels;

            parameters = c.getParameters();
            Camera.Size mPreviewSize = calculateOptimalPreviewSize(
                    parameters.getSupportedPreviewSizes(),width,height);
            Camera.Size mDisplaySize = calculateOptimalCameraResolution(
                    parameters.getSupportedPictureSizes());
            parameters.setPictureSize(mDisplaySize.width, mDisplaySize.height);
            parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);

                c.setParameters(parameters);

                if (mSurface != null) {
                    try {
                        c.setPreviewTexture(mSurface);
                        c.startPreview();
                    } catch (IOException e) {
                        Log.e(TAG, "error setting mSurface as surfacetexture", e);

                        mWeakActivity.get().mTracker.getEventClient()
                           .recordEvent(mWeakActivity.get().mTracker
                                   .getEventClient()
                                   .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                   .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                           e.getMessage()+":"+ "getCameraInstance(int whichCamera)"));
               }
           } else {
               Log.e(TAG,"The camera surface texture has yet to be created");
           }

            } else {
                Log.i(TAG, "getCameraInstance failed to connect to camera");
            }

            return c; // returns null if camera is unavailable
        }

        /* convenience method to ease debugging */
        private void releaseCamera(Camera c) {
            if (VERBOSE) Log.v(TAG,"disconnecting from camera " + c.toString());
            isConnected = false;
            c.release();
        }

        @Override
        public void onShutter() {
            if (VERBOSE) Log.v(TAG,"entering onShutter");

            Intent intent = new Intent(CameraFragment.ACTION_PICTURE_TAKEN);
            LocalBroadcastManager.getInstance(mWeakActivity.get().getApplicationContext())
                    .sendBroadcastSync(intent);

            if (VERBOSE) Log.v(TAG,"exiting onShutter");
        }

        public void onPictureTaken(final byte[] data, android.hardware.Camera camera) {
            if (VERBOSE) Log.v(TAG, "enter onPictureTaken... ");

            camera.stopPreview();
            theData = data;

            if (VERBOSE) Log.v(TAG, "exit onPictureTaken... ");
        }

        /**
         * method 'saveImage'
         *
         * @param data image data to be saved
         * @param commentText text of the comment to be recreated
         * @param height positioning of the comment to be recreated
         */
        public void saveImage(byte[] data, String commentText, int height, int callBack) {
            int TEXT_BOX_HEIGHT = 55;
            int COMPRESSION_QUALITY = 80;

            Log.d(TAG, "saving image...");
            Log.d(TAG,"incoming string comment " + commentText);

            UUID name = UUID.nameUUIDFromBytes(data);
            String key = name.toString();

            File file = new File(mWeakActivity.get().getCacheDir(),key);

            String filePath = file.getAbsolutePath();

            Log.d(TAG, "Saving file to... " + filePath);
            Log.d(TAG, "The size of the image before compression: " + data.length);

            //create bitmap from data
            Bitmap image;
            BitmapFactory.Options options = new BitmapFactory.Options();

            options.inMutable = true;
            image = BitmapFactory.decodeByteArray(data, 0, data.length, options);

            Matrix matrix = new Matrix();
            matrix.postRotate(cameraInfo.orientation);
            image = Bitmap.createBitmap(image, 0, 0, image.getWidth(),
                    image.getHeight(), matrix, true);

            Log.d(TAG, "comment was null...");

            try {
                image.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY,
                        new FileOutputStream(filePath));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "error compressing bitmap to filepath" + filePath, e);

                mWeakActivity.get().mTracker.getEventClient()
                        .recordEvent(mWeakActivity.get().mTracker
                                .getEventClient()
                                .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                        e.getMessage() + ":" + "saveImage(byte[] data, String" +
                                                " commentText, int height, int callBack)"));
            }

             Log.d(TAG, "The size of the image after: " + data.length);

            if (callBack == CameraFragment.CAMERA_REPLY_MODE) {
                Log.d(TAG,"now extracting and saving thumbnail");

                try {
                    image = ThumbnailUtils.extractThumbnail(image,250,250);
                    image.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY,
                            new FileOutputStream(filePath + "s"));
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "error compressing bitmap to filepath" + filePath, e);

                    mWeakActivity.get().mTracker.getEventClient()
                            .recordEvent(mWeakActivity.get().mTracker
                                    .getEventClient()
                                    .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                    .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                            e.getMessage() + ":" + "saveImage(byte[] data, " +
                                                    "String commentText, int height, int callBack)")
                            );
                }
            }

            switch (callBack) {
                case CameraFragment.CAMERA_MESSAGE_MODE:
                case CameraFragment.CAMERA_DEFAULT_MODE:
                    if (Constants.LOGD) Log.d(TAG, "notifying MainActivity to post image to Local");
                    mWeakActivity.get().sendImageToLocal(key, 2, commentText);
                    break;

                case CameraFragment.CAMERA_LIVE_MODE:
                    if (Constants.LOGD) Log.d(TAG, "notifying MainActivity image " +
                            "is ready to post to Live...");
                    mWeakActivity.get().setLiveFilePath(key);
                    break;

                case CameraFragment.CAMERA_REPLY_MODE:
                    if (Constants.LOGD) Log.d(TAG, "notifying MainActivity" +
                            " image is ready to post to Reply...");
                    mWeakActivity.get().setReplyFilePath(key);
                    break;
            }

            if (VERBOSE) Log.v(TAG, "exit savePicture...");
        }

        /**
         * method 'focusOnTouch'
         *
         * from http://stackoverflow.com/questions/18460647/android-setfocusarea-and-auto-focus
         *
         * @param x the x coord of the touchevent
         * @param y the y coord of the touchevent
         */
        protected void focusOnTouch(float x, float y) {
            if (mCamera != null) {

                mCamera.cancelAutoFocus();
                Rect focusRect = calculateTapArea(x, y, 1f);
                Rect meteringRect = calculateTapArea(x, y, 1.5f);


                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

                ArrayList<Camera.Area> cameraAreas = new ArrayList<>();
                cameraAreas.add(new Camera.Area(focusRect, 1000));
                parameters.setFocusAreas(cameraAreas);

                if (meteringAreaSupported) {
                    ArrayList<Camera.Area> meterAreas = new ArrayList<>();
                    meterAreas.add(new Camera.Area(meteringRect, 1000));
                    parameters.setMeteringAreas(meterAreas);
                }

                Log.d(TAG,parameters.flatten());

                try {
                    mCamera.setParameters(parameters);
                    mCamera.autoFocus((CameraFragment) mAdapter.getItem(CAMERA_LIST_POSITION));
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to set camera parameters...", e);

                    mWeakActivity.get().mTracker.getEventClient()
                            .recordEvent(mWeakActivity.get().mTracker
                                            .getEventClient()
                                            .createEvent(Constants.ANALYTICS_CATEGORY_ERROR)
                                            .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,
                                                    e.getMessage() + ":" + "focusOnTouch(float x, float y)")
                            );

                    ((CameraFragment) mAdapter.getItem(CAMERA_LIST_POSITION)).onAutoFocus(false,mCamera);
                }
            }
        }

        Matrix matrix = new Matrix();
        int focusAreaSize = 50;

        /**
         * Convert touch position x:y to {@link Camera.Area} position -1000:-1000 to 1000:1000.
         */
        private Rect calculateTapArea(float x, float y, float coefficient) {
            int areaSize = Float.valueOf(focusAreaSize * coefficient).intValue();
            int left = clamp((int) x - areaSize / 2, 0, width - areaSize);
            int top = clamp((int) y - areaSize / 2, 0, height - areaSize);
            RectF rectF = new RectF(left, top, left + areaSize, top + areaSize);
            matrix.mapRect(rectF);
            return new Rect(Math.round(rectF.left), Math.round(rectF.top), Math.round(rectF.right), Math.round(rectF.bottom));
        }

        private int clamp(int x, int min, int max) {
            if (x > max) {
                return max;
            }
            if (x < min) {
                return min;
            }
            return x;
        }

        /**
         * method 'getOptionalPreviewSize'
         *
         * finds the best preview size to aspect ratio and returns the size
         *
         * @param sizes camera sizes available
         * @param w width to aim for
         * @param h height to aim for
         * @return the optinal Camera.size for the preview
         */
        private Camera.Size calculateOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
            if (VERBOSE) {
                Log.v(TAG, "enter calculateOptimalPreviewSize...");
            }

            final double ASPECT_TOLERANCE = 0.1;
            double targetRatio = (double) h / w;

            if (sizes == null) return null;

            Camera.Size optimalSize = null;
            double minDiff = Double.MAX_VALUE;

            int targetHeight = h;

            for (Camera.Size size : sizes) {
                double ratio = (double) size.width / size.height;
                if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }

            if (optimalSize == null) {
                minDiff = Double.MAX_VALUE;
                for (Camera.Size size : sizes) {
                    if (Math.abs(size.height - targetHeight) < minDiff) {
                        optimalSize = size;
                        minDiff = Math.abs(size.height - targetHeight);
                    }
                }
            }

            if (VERBOSE) {
                Log.d(TAG,"optimal" +
                        "preview size found is " + optimalSize.height + ", " + optimalSize.width);
                Log.v(TAG,"exit calculateOptimalPreviewSize...");
            }
            return optimalSize;
        }

        /**
         * method 'calculateOptimalResolution'
         *
         * tests available resolutions of the provided camera, first checks if it supports
         * the optimal resolution. Should that resolution not be available, it then attempts to
         * calculate the resolution which is closest to the optimal.
         *
         * Uses the distance formula in {@link SQLiteDbHelper}
         *
         * @param sizes avaiable camera resolution sizes
         * @return best resolution available
         */
        private Camera.Size calculateOptimalCameraResolution(List<Camera.Size> sizes) {
            if (VERBOSE) Log.v(TAG,"enter calculateOptimalResolution...");


            if (VERBOSE) {
                Log.v(TAG, "viable image sizes: ");
                for(int i = 0; i < sizes.size(); i++) {
                    Log.v(" ", " " + sizes.get(i).width + sizes.get(i).height);
                }
            }

            //for each size, first, do they have it? - try each size
            for (Camera.Size s : sizes) {
                if (s.width == Constants.IDEAL_WIDTH) {
                    if (s.height == Constants.IDEAL_HEIGHT) {
                        //found it
                        Log.d(TAG,"perfect resolution found! " + s.width + ", " + s.height);
                        return s;
                    }
                }
            }

            //guess it failed, calculation time
            Log.d(TAG,"perfect resolution not found.");

            int closestDistance = 9000;
            Camera.Size closestSize = sizes.get(sizes.size()-1);

            for (Camera.Size s : sizes) {
                Double currentDistance = SQLiteDbHelper.distanceFormula(s.width,s.height,
                        Constants.IDEAL_WIDTH,Constants.IDEAL_HEIGHT);

                if (currentDistance < closestDistance) {
                    closestDistance = currentDistance.intValue();
                    closestSize = s;

                }
            }
            Log.d(TAG,"closestSize found is: " + closestSize.width + ", " + closestSize.height);

            if (VERBOSE) Log.v(TAG, "exit calculateOptimalResolution...");
            return closestSize;
        }

    }

    public void sendMsgStartPreview() {
        if (VERBOSE) Log.v(TAG,"entering sendMsgStartPreview");

        try {
            cameraMessenger.send(Message.obtain(null, MSG_START_PREVIEW));
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to start Preview");
        }

        if (VERBOSE) Log.v(TAG,"exiting sendMsgStartPreview()");
    }

    public void sendMsgStopPreview() {
        if (VERBOSE) Log.v(TAG, "entering sendMsgStopPreview...");

        try {
            cameraMessenger.send(Message.obtain(null, MSG_STOP_PREVIEW));
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to stop Preview");
        }

        if (VERBOSE) Log.v(TAG, "exiting sendMsgStopPreview...");
    }

    public void sendMsgTakePicture() {
        if (VERBOSE) Log.v(TAG, "entering sendMsgTakePicture...");

        try {
            Message msg = Message.obtain(null,MSG_TAKE_PICTURE);
            cameraMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to take picture");
        }

        if (VERBOSE) Log.v(TAG,"exiting sendMsgTakePicture...");
    }

    /**
     * method 'sendMsgSaveImage'
     *
     * send message to the camera event thread to save the current image
     *
     * @param comment if there is a comment, it is passed here
     */
    public void sendMsgSaveImage(EditText comment, int where) {
        if (VERBOSE) Log.v(TAG, "entering sendMsgSaveImage...");

        Message msg = Message.obtain(null, MSG_SAVE_PICTURE);

        //APPLYING SETTINGS
        msg.arg2 = where;

        if (!comment.getText().toString().trim().matches("")) {
            Log.d(TAG, "Text retrieved was not null, attempting to send");
            Bundle b = new Bundle();
            b.putString(Constants.KEY_TEXT,comment.getText().toString());
            msg.setData(b);
        } else {
            Log.d(TAG, "comment was null...");
        }

        //NOW SENDING
        try {
            cameraMessenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to save image");
        }

        if (VERBOSE) Log.v(TAG, "exiting sendMsgSaveImage...");
    }

    /**
     * method 'sendMsgSaveImage'
     *
     * send message to the camera event thread to save the current image
     *
     * @param comment if there is a comment, it is passed here
     */
    public void sendMsgSaveImage(EditText comment, int where, String messageTarget) {
        if (VERBOSE) {
            Log.v(TAG, "entering sendMsgSaveImageWrapper...");
            Log.v(TAG, "printing arguments: ");
            Log.v(TAG, "comment " + comment.getText().toString());
            Log.v(TAG, "where " + where);
            Log.v(TAG, "messageTarget " + messageTarget);
        }

        this.messageTarget = messageTarget;
        sendMsgSaveImage(comment,where);
        if (VERBOSE) Log.v(TAG, "exiting sendMsgSaveImageWrapper...");
    }

    public void sendMsgSwitchCamera() {
        if (VERBOSE) Log.v(TAG, "entering sendMsgSaveImage...");

        if (currentCamera == CAMERA_POSITION_FRONT) {
            currentCamera = CAMERA_POSITION_BACK;
        } else {
            currentCamera = CAMERA_POSITION_FRONT;
        }

        try {
            cameraMessenger.send(Message.obtain(null, MSG_SWITCH_CAMERA, currentCamera, 0));
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to save image");
        }

        if (VERBOSE) Log.v(TAG, "exiting sendMsgSaveImage...");
    }

    public void setFlash(View view) {
        if (VERBOSE) Log.v(TAG, "entering setFlash...");

        CheckBox flashBox = (CheckBox)view;
        if (flashBox.isChecked()) {
            try {
                cameraMessenger.send(Message.obtain(null, MSG_FLASH_ON));
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to save image");
            }
        } else {
            try {
                cameraMessenger.send(Message.obtain(null, MSG_FLASH_OFF));
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to save image");
            }
        }

        if (VERBOSE) Log.v(TAG, "exiting setFlash...");
    }

    public int sendMsgAutoFocus(MotionEvent event) {
        if (VERBOSE) Log.v(TAG, "entering sendMsgAutoFocus...");

        int success;

        try {
            Bundle b = new Bundle(2);
            b.putFloat("x",event.getX());
            b.putFloat("y",event.getY());

            Message msg = Message.obtain(null,MSG_AUTOFOCUS);
            msg.setData(b);
            cameraMessenger.send(msg);

            success = 0;
        } catch (RemoteException e) {
            Log.e(TAG,"error sending message to take picture");
            success = -1;
        }

        if (VERBOSE) Log.v(TAG,"exiting sendMsgAutoFocus...");

        return success;
    }

    /**
     * tags for replyHandler's various tasks
     */

    static final int MSG_UPLOAD_PROGRESS = 1;

    static final int MSG_DATABASE_CLEARED= 2;

    static final int MSG_PICTURE_TAKEN = 3;

    static final int MSG_TOO_MANY_REQUESTS = 51;

    static final int MSG_LIVE_REFRESH_DONE = 52;

    static final int MSG_UNAUTHORIZED = 53;

    static final int MSG_NOT_FOUND = 54;

    static final int MSG_NOTHING_RETURNED = 55;

    /**
     * class 'replyHandler'
     *
     * handles responses from the service
     *
     * msg.arg1 -> where to forward the error code to
     */
    class UIHandler extends Handler {

        WeakReference<MainActivity> activity;

        public Handler setParent(MainActivity parent) {
            activity = new WeakReference<>(parent);
            return this;
        }

        public void handleMessage(Message msg) {
            Log.d(TAG,"enter handleMessage");
            int respCode = msg.what;

            switch (respCode) {
                case MSG_UPLOAD_PROGRESS:
                    ProgressBar uploadBar = (ProgressBar)activity.get().findViewById(R.id.uploadProgress);
                    TextView progressText = (TextView)activity.get().findViewById(R.id.uploadProgressText);
                    Log.i(TAG,"received upload progress from the background service...");
                    switch (msg.arg1) {

                        case DataHandlingService.CONNECTION_STARTED:
                            uploadBar.setVisibility(View.VISIBLE);
                            progressText.setVisibility(View.VISIBLE);
                            progressText.setText("Opening Connection...");
                            break;
                        case DataHandlingService.CONNECTION_FAILED:
                            uploadBar.setVisibility(View.INVISIBLE);
                            progressText.setText("Connection Failed!");
                            progressText.setVisibility(View.INVISIBLE);
                            uploadBar.setVisibility(View.INVISIBLE);
                            break;
                        case DataHandlingService.CONNECTION_COMPLETED:
                            uploadBar.setVisibility(View.VISIBLE);
                            uploadBar.setProgress(20);
                            progressText.setText("Connected");
                            break;
                        case DataHandlingService.REQUEST_STARTED:
                            progressText.setText("Starting Request");
                            break;
                        case DataHandlingService.REQUEST_FAILED:
                            progressText.setText("Request Failed!");
                            progressText.setVisibility(View.INVISIBLE);
                            uploadBar.setVisibility(View.INVISIBLE);
                            break;
                        case DataHandlingService.TASK_COMPLETED:
                            progressText.setText("Completed Successfully");
                            progressText.setVisibility(View.INVISIBLE);
                            uploadBar.setVisibility(View.INVISIBLE);
                            break;
                    }
                    break;

                case DataHandlingService.MSG_REQUEST_LIVE_THREADS:
                    if (LiveFragReference.get() != null) {
                        LiveFragReference.get().handleLiveResponseState(msg);
                    }
                    break;

                case DataHandlingService.MSG_REQUEST_REPLIES:
                    if (ReplyFragReference.get() != null) {
                        ReplyFragReference.get().handleReplyResponseState(msg);
                    }
                    break;

                case DataHandlingService.MSG_REQUEST_LOCAL_POSTS:
                    if (LocalFragReference.get() != null) {
                        LocalFragReference.get().handleLocalResponseState(msg);
                    }
                    break;


                case DataHandlingService.MSG_REQUEST_MESSAGES:
                    if (MessageFragReference.get() != null) {
                     //   MessageFragReference.get().handleMessageResponseState(msg);
                    }
                    break;

                case DataHandlingService.MSG_CREATE_THREAD:
                    Log.v(TAG, "entering msg_create_thread");
                 /*   Toast.makeText(activity.get(),
                            "created live thread info received...",
                            Toast.LENGTH_LONG)
                            .show();*/
                    break;



                case MSG_DATABASE_CLEARED:
                    Toast.makeText(activity.get(),
                            "entire database cleared",
                            Toast.LENGTH_LONG).show();
                    break;
                case MSG_PICTURE_TAKEN: //-1 reply in main UI
                    CameraFragReference.get().onPictureTaken(1);
                    break;

                case MSG_TOO_MANY_REQUESTS:
                    new AlertDialog.Builder(activity.get())
                            .setTitle("Alert")
                            .setMessage("You have posted too many times, " +
                                    "in a small period, and now we're worried you're not human.")
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();
                    break;

                case MSG_LIVE_REFRESH_DONE:
                  //  Toast.makeText(activity.get(),"Live Refresh Finished...",Toast.LENGTH_SHORT).show();
                   /* LiveFragment f = LiveFragReference.get();
                    if (f != null) {
                        if (VERBOSE) Log.v(TAG,"recreating live loader at id" + LiveFragment.LIVE_LOADER_ID);
                        getLoaderManager().initLoader(LiveFragment.LIVE_LOADER_ID, null,f);
                        f = null;
                    } else {
                        Log.d(TAG,"Live is currently not instantiated... doing nothing...");
                    }*/
                    break;

                case MSG_UNAUTHORIZED:
                    new AlertDialog.Builder(activity.get())
                            .setTitle("Unauthorized")
                            .setMessage("A bad userID was supplied to our server... hold on a " +
                                    "moment while we make you another one.")
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton("Got it, coach", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                            .show();
                    break;

                case MSG_NOT_FOUND:
                    Log.i(TAG,"404 received...");
                    new AlertDialog.Builder(activity.get())
                        .setTitle("404 - not found")
                        .setMessage("You have attempted to get the replies for a thread that no" +
                                " longer exists. You should refresh.")
                        .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton("Refresh", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    activity.get().sendMsgRequestLiveThreads();

                                    getContentResolver()
                                            .delete(FireFlyContentProvider
                                                            .CONTENT_URI_LIVE,
                                                    null,
                                                    null
                                            );

                                    getContentResolver()
                                            .delete(FireFlyContentProvider
                                                            .CONTENT_URI_REPLY_LIST,
                                                    null,
                                                    null
                                            );
                                }
                            })
                            .setNegativeButton("Don't tell me what to do.",
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                        .show();
                    break;

                case MSG_NOTHING_RETURNED:

                    switch (msg.arg1) {
                        case DataHandlingService.MSG_REQUEST_MESSAGES:
                            Toast.makeText(activity.get(),
                                    "No messages received...",
                                    Toast.LENGTH_SHORT).show();
                            break;

                        case DataHandlingService.MSG_REQUEST_LIVE_THREADS:
                            Toast.makeText(activity.get(),
                                    "No live threads returned...",
                                    Toast.LENGTH_SHORT).show();
                            break;

                        case DataHandlingService.MSG_REQUEST_LOCAL_POSTS:
                            Toast.makeText(activity.get(),
                                    "No local posts returned...",
                                    Toast.LENGTH_SHORT).show();
                            break;

                        case DataHandlingService.MSG_REQUEST_REPLIES:
                            //Toast.makeText(activity.get(), "No replies received...",
                            // Toast.LENGTH_SHORT).show();
                            if (MainActivity.ReplyFragReference.get()!=null) {
                                //MainActivity.ReplyFragReference.get()
                                // .handleResponseCode(MSG_NOTHING_RETURNED);
                            }
                            break;
                    }

                    break;
            }

            Log.d(TAG, "exit handleMessage");
        }
    }

    class UploadHandler extends Handler {
        public UploadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            Log.i(TAG, "received upload progress from the background service...");
            switch (msg.what) {
                case DataHandlingService.CONNECTION_FAILED:
                    break;
                case DataHandlingService.CONNECTION_STARTED:
                    break;
                case DataHandlingService.CONNECTION_COMPLETED:
                    break;
                case DataHandlingService.REQUEST_FAILED:
                    break;
                case DataHandlingService.REQUEST_STARTED:
                    break;
                case DataHandlingService.TASK_COMPLETED:
                    break;
            }

        }
    }

    /**
     *
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the object we can use to
            // interact with the service.  We are communicating with the
            // service using a Messenger, so here we get a client-side
            // representation of that from the raw IBinder object.
            Log.d(TAG,"binding to service...");
            mService = new Messenger(service);
            isBound = true;

            Message msg = Message.obtain(null, DataHandlingService.MSG_SET_CALLBACK_MESSENGER);
            msg.replyTo = messageHandler;
            try {
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG,"error sending message to set messageHandler. This is a fatal error.",e);
                System.exit(1);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            Log.d(TAG,"unbinding from service...");

            mService = null;
            isBound = false;
        }
    };

    /**
     * callback from {@link com.jokrapp.android.CameraFragment.OnCameraFragmentInteractionListener}
     */
    @Override
    public void toggleScrolling() {
        if (mPager.isPagingEnabled()) {
            mPager.setPagingEnabled(false);
        } else {
            mPager.setPagingEnabled(true);
        }
    }

    public void enableScrolling() { mPager.setPagingEnabled(true); }
    public void disableScrolling() {
        mPager.setPagingEnabled(false);
    }

/***************************************************************************************************
 * ANALYTICS
 **/

    /**
     * method 'reportAnalyticsEvent'
     *
     * reports an event to Google analytics.
     *
     * {@link com.jokrapp.android.LiveFragment.onLiveFragmentInteractionListener}
     * {@link com.jokrapp.android.LocalFragment.onLocalFragmentInteractionListener}
     * Called by these linked interface callbacks.
     **/
    public void sendMsgReportAnalyticsEvent(Bundle b) {
        if (VERBOSE) Log.d(TAG,"sending message to report analytics event");
        if (isBound) {
            try {
                Message msg = Message.obtain(null, DataHandlingService.MSG_REPORT_ANALYTICS);
                msg.setData(b);
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "error send message to report analytics",e);
            }
        } else {
            Log.e(TAG,"failed to report analyitcs event, service was not bound...");
        }
    }

    public void sendMsgReportTimingEvent(Bundle b) {
        if (VERBOSE) Log.d(TAG,"sending message to report timing event");
        if (isBound) {
            try {
                Message msg = Message.obtain(null, DataHandlingService.MSG_REPORT_ANALYTIC_TIMING);
                msg.setData(b);
                mService.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "error send message to report analytics",e);
            }
        } else {
            Log.e(TAG,"failed to report analytics event, service was not bound...");
        }
    }

    /**
     * method 'sendScreenName'
     * <p/>
     * reports a screen view event to Google Analytics
     *
     * @param name name of the screen to be sent
     */
    public void setAnalyticsScreenName(String name) {
        if (VERBOSE) Log.v(TAG, "Sending screen event for screen name: " + name);

        mTracker.getEventClient()
                .recordEvent(mTracker
                        .getEventClient()
                        .createEvent(Constants.ANALYTICS_CATEGORY_SCREEN)
                        .withAttribute(Constants.ANALYTICS_CATEGORY_MESSAGE,name
                        )
                );
    }

    public void onDeveloperInteraction(int request, Uri resource) {
        Log.i(TAG, "entering onDeveloperInteraction with request- " + request + " and resource - "
                + resource.toString());
    }

}