package com.jokrapp.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.LruCache;
import android.util.Log;
import android.util.TimingLogger;
import android.view.View;
import android.widget.ProgressBar;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;

import java.io.File;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class creates pools of background threads for downloading
 * Picasa images from the web, based on URLs retrieved from Picasa's featured images RSS feed.
 * The class is implemented as a singleton; the only way to get an PhotoManager instance is to
 * call {@link #getInstance}.
 * <p>
 * The class sets the pool size and cache size based on the particular operation it's performing.
 * The algorithm doesn't apply to all situations, so if you re-use the code to implement a pool
 * of threads for your own app, you will have to come up with your choices for pool size, cache
 * size, and so forth. In many cases, you'll have to set some numbers arbitrarily and then
 * measure the impact on performance.
 * <p>
 * This class actually uses two threadpools in order to limit the number of
 * simultaneous image decoding threads to the number of available processor
 * cores.
 * <p>
 * Finally, this class defines a handler that communicates back to the UI
 * thread to change the bitmap to reflect the state.
 */
public class PhotoManager {
    /*
     * Status indicators
     */
    static final int DOWNLOAD_FAILED = -1;
    static final int DOWNLOAD_STARTED = 1;
    static final int DOWNLOAD_COMPLETE = 2;
    static final int DECODE_STARTED = 3;
    static final int TASK_COMPLETE = 4;
    static final int DISKLOAD_STARTED = 6;
    static final int DISKLOAD_COMPLETE = 5;
    static final int REQUEST_FAILED = 7;
    static final int REQUEST_STARTED = 8;
    static final int REQUEST_COMPLETE = 9;

    static final int AWS_DOWNLOAD_COMPLETE = 5;

    static final boolean VERBOSE = false;


    private static final String TAG = "PhotoManager";
    // Sets the size of the storage that's used to cache images
    private static final int IMAGE_CACHE_SIZE = 1024 * 1024 * 10; //10MiB

    // Sets the amount of time an idle thread will wait for a task before terminating
    private static final int KEEP_ALIVE_TIME = 1;


    // Sets the duration of the crossfade for all image view
    private static final int CROSSFADE_DURATION = 150;


    // Sets the Time Unit to seconds
    private static final TimeUnit KEEP_ALIVE_TIME_UNIT;

    // Sets the initial threadpool size to 8
    private static final int CORE_POOL_SIZE = 8;

    // Sets the maximum threadpool size to 8
    private static final int MAXIMUM_POOL_SIZE = 8;

    /**
     * NOTE: This is the number of total available cores. On current versions of
     * Android, with devices that use plug-and-play cores, this will return less
     * than the total number of cores. The total number of cores is not
     * available in current Android implementations.
     */
    private static int NUMBER_OF_CORES =
            Runtime.getRuntime().availableProcessors(); //this shit... fucking great

    /*
     * Creates a cache of byte arrays indexed by image URLs. As new items are added to the
     * cache, the oldest items are ejected and subject to garbage collection.
     */
    private final LruCache<String, byte[]> mPhotoCache;


    // A queue of Runnables for the image download pool
    private final BlockingQueue<Runnable> mDownloadWorkQueue;

    // A queue of Runnables for the image decoding pool
    private final BlockingQueue<Runnable> mDecodeWorkQueue;

    // A queue of PhotoManager tasks. Tasks are handed to a ThreadPool.
    private final Queue<PhotoTask> mPhotoTaskWorkQueue;

    // A managed pool of background download threads
    private final ThreadPoolExecutor mDownloadThreadPool;

    // A managed pool of background decoder threads
    private final ThreadPoolExecutor mDecodeThreadPool;

    // An object that manages Messages in a Thread
    private Handler mHandler;

    private final HashMap<String,PhotoTask> mWaitingPhotoTasks;

    // A single instance of PhotoManager, used to implement the singleton pattern
    private static PhotoManager sInstance = null;

    private final AmazonS3Client s3Client;

    // A static block that sets class fields
    static {

        // The time unit for "keep alive" is in seconds
        KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

        // Creates a single static instance of PhotoManager
        sInstance = new PhotoManager();
    }
    /**
     * Constructs the work queues and thread pools used to download and decode images.
     */
    private PhotoManager() {
        if (Constants.LOGV) Log.v(TAG,"entering PhotoManager constructor...");

        /*
         * Creates an amazon s3 client for allowing get requests from the server
         */
        s3Client = new AmazonS3Client( //todo this is so bad security-wise
                new BasicAWSCredentials(
                        "AKIAIZ42NH277ZC764XQ",
                        "pMYCGMq+boy6858OfITL4CTXWgdkVbVreyROHckG"
                )
        );

        /*
         * Creates a list of waiting photoTasks that will hold all phototasks still
         * waiting for the download to finish
         */
        mWaitingPhotoTasks = new HashMap<>();

        /*
         * Creates a work queue for the pool of Thread objects used for downloading, using a linked
         * list queue that blocks when the queue is empty.
         */
        mDownloadWorkQueue = new LinkedBlockingQueue<Runnable>();

        /*
         * Creates a work queue for the pool of Thread objects used for decoding, using a linked
         * list queue that blocks when the queue is empty.
         */
        mDecodeWorkQueue = new LinkedBlockingQueue<Runnable>();

        /*
         * Creates a work queue for the set of of task objects that control downloading and
         * decoding, using a linked list queue that blocks when the queue is empty.
         */
        mPhotoTaskWorkQueue = new LinkedBlockingQueue<PhotoTask>();

        /*
         * Creates a new pool of Thread objects for the download work queue
         */
        mDownloadThreadPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT, mDownloadWorkQueue);

        /*
         * Creates a new pool of Thread objects for the decoding work queue
         */
        mDecodeThreadPool = new ThreadPoolExecutor(NUMBER_OF_CORES, NUMBER_OF_CORES,
                KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT, mDecodeWorkQueue);

        // Instantiates a new cache based on the cache size estimate
        mPhotoCache = new LruCache<String, byte[]>(IMAGE_CACHE_SIZE) {

            /*
             * This overrides the default sizeOf() implementation to return the
             * correct size of each cache entry.
             */
            @Override
            protected int sizeOf(String key, byte[] value) {
                return super.sizeOf(key, value);
            }

        };

        if (Constants.LOGV) Log.v(TAG,"creating anonymous handler to function on UI thread...");
        /*
         * Instantiates a new anonymous Handler object and defines its
         * handleMessage() method. The Handler *must* run on the UI thread, because it moves photo
         * Bitmaps from the PhotoTask object to the View object.
         * To force the Handler to run on the UI thread, it's defined as part of the PhotoManager
         * constructor. The constructor is invoked when the class is first referenced, and that
         * happens when the View invokes startDownload. Since the View runs on the UI Thread, so
         * does the constructor and the Handler.
         */
        mHandler = new Handler(Looper.getMainLooper()) {
            /*
             * handleMessage() defines the operations to perform when the
             * Handler receives a new Message to process.
             */
            @Override
            public void handleMessage(Message inputMessage) {

                // Gets the image task from the incoming Message object.
                PhotoTask photoTask = (PhotoTask) inputMessage.obj;

                if (inputMessage.obj == null) {
                    photoTask = mWaitingPhotoTasks.get(
                            inputMessage.getData().getString
                                    (Constants.KEY_S3_KEY)
                    );
                }

                if (photoTask == null) {
                    Log.d(TAG,"No phototask was grabbable, exiting...");
                    return;
                }

                // Sets an PhotoView that's a weak reference to the
                // input ImageView
                PhotoView localView = photoTask.getPhotoView();

                // If this input view isn't null
                if (localView != null) {

                    /*
                     * Gets the URL of the *weak reference* to the input
                     * ImageView. The weak reference won't have changed, even if
                     * the input ImageView has.
                     */
                    String localKey = localView.getImageKey();

                    /*
                     * Compares the URL of the input ImageView to the URL of the
                     * weak reference. Only updates the bitmap in the ImageView
                     * if this particular Thread is supposed to be serving the
                     * ImageView.
                     */
                    if (photoTask.getImageKey() == localKey)

                        /*
                         * Chooses the action to take, based on the incoming message
                         */
                        switch (inputMessage.what) {

                            // If the download has started, sets background color to dark green
                            case DOWNLOAD_STARTED:
                                //localView.setStatusResource(R.drawable.imagedownloading);
                                break;

                            /*
                             * If the download is complete, but the decode is waiting, sets the
                             * background color to golden yellow
                             */
                            case DOWNLOAD_COMPLETE:
                                // Sets background color to golden yellow
                                //localView.setStatusResource(R.drawable.decodequeued);
                                break;

                            case DOWNLOAD_FAILED:
                                //localView.setStatusResource(R.drawable.imagedownloadfailed);
                                recycleTask(photoTask);
                                break;

                            // If the download has started, sets background color to dark green
                            case REQUEST_STARTED:
                                if (VERBOSE) Log.d(TAG, "Request Started...");
                                //localView.setStatusResource(R.drawable.imagedownloading);
                                break;

                            /*
                             * If the download is complete, but the decode is waiting, sets the
                             * background color to golden yellow
                             */
                            case REQUEST_COMPLETE:
                                if (VERBOSE) Log.d(TAG, "Request Complete...");
                                handleState(photoTask, DOWNLOAD_COMPLETE);
                                // Sets background color to golden yellow
                                //localView.setStatusResource(R.drawable.decodequeued);
                                if (localView.getVisibility()== View.GONE) {
                                    if (Constants.LOGV) Log.v(TAG, "View was set to GONE, setting to visible");
                                    localView.setVisibility(View.VISIBLE);
                                }
                                break;

                            case REQUEST_FAILED:
                                handleState(photoTask, DOWNLOAD_FAILED);
                                localView.setStatusResource(R.drawable.imagedownloadfailed);

                                // Attempts to re-use the Task object
                                recycleTask(photoTask);
                                break;


                            // If the decode has started, sets background color to orange
                            case DISKLOAD_STARTED:
                                if (VERBOSE) Log.d(TAG, "Diskload Started...");
                                break;
                            case DISKLOAD_COMPLETE:
                                if (VERBOSE) Log.d(TAG, "Diskload Complete...");
                                break;

                            case DECODE_STARTED:
                                //localView.setStatusResource(R.drawable.decodedecoding);
                                break;

                            /*
                             * The decoding is done, so this sets the
                             * ImageView's bitmap to the bitmap in the
                             * incoming message
                             */

                            case TASK_COMPLETE:
                                if (VERBOSE) Log.d(TAG,"setting image bitmap");

                                /*
                                 *fade the view in because it looks like shit otherwise
                                 */

                                //start with setting the visibility to visible, and alpha to 0
                                localView.setAlpha(0f);
                                localView.setVisibility(View.VISIBLE);

                                //now set the bitmap
                                localView.setImageBitmap(photoTask.getImage());

                                //now fade it in
                                localView.animate()
                                        .alpha(1f)
                                        .setDuration(CROSSFADE_DURATION)
                                        .setListener(null);crossfade(localView,null);

                                mWaitingPhotoTasks.remove(photoTask.getImageKey());
                                break;
                            // The download failed, sets the background color to dark red


                            default:
                                // Otherwise, calls the super method
                                super.handleMessage(inputMessage);
                        }
                }
            }
        };

        if (Constants.LOGV) Log.v(TAG,"exiting PhotoManager constructor...");
    }

    private static void crossfade(View mContentView, final View mLoadingView) {
        if (mContentView == null) {
            Log.e(TAG,"crossfade cannot have a null contentView...");
            return;
        }

        // Set the content view to 0% opacity but visible, so that it is visible
        // (but fully transparent) during the animation.
        mContentView.setAlpha(0f);
        mContentView.setVisibility(View.VISIBLE);

        if (mLoadingView!=null) {
            // Animate the content view to 100% opacity, and clear any animation
            // listener set on the view.
            mContentView.animate()
                    .alpha(1f)
                    .setDuration(CROSSFADE_DURATION)
                    .setListener(null);

            // Animate the loading view to 0% opacity. After the animation ends,
            // set its visibility to GONE as an optimization step (it won't
            // participate in layout passes, etc.)
            mLoadingView.animate()
                    .alpha(0f)
                    .setDuration(CROSSFADE_DURATION)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mLoadingView.setVisibility(View.GONE);
                        }
                    });
        } else {
            mContentView.animate()
                    .alpha(1f)
                    .setDuration(CROSSFADE_DURATION)
                    .setListener(null);
        }
    }

    public byte[] getCachedImage(String key) {
        return mPhotoCache.get(key);
    }

    /**
     * Returns the PhotoManager object
     * @return The global PhotoManager object
     */
    public static PhotoManager getInstance() {
        return sInstance;
    }

    public static Handler getMainHandler() {
        if (sInstance!=null) {
            return sInstance.mHandler;
        } else {
            return null;
        }
    }


    /**
     * Handles state messages for a particular task object
     * @param photoTask A task object
     * @param state The state of the task
     */
    @SuppressLint("HandlerLeak")
    public void handleState(PhotoTask photoTask, int state) {
        if (VERBOSE) Log.v(TAG,"entering handleState...");

        switch (state) {

            // The task finished downloading and decoding the image
            case TASK_COMPLETE:
                if (VERBOSE) Log.v(TAG,"task is complete...");

                // Puts the image into cache
                if (photoTask.isCacheEnabled()) {
                    // If the task is set to cache the results, put the buffer
                    // that was

                    if (photoTask.getImageKey() == null || photoTask.getByteBuffer() == null) {
                        Log.e(TAG,"unable to cache image, key or buffer null.");
                    } else {
                        if (VERBOSE) Log.v(TAG,"Storing key " + photoTask.getImageKey() + " in mPhotoCache");
                        mPhotoCache.put(photoTask.getImageKey(), photoTask.getByteBuffer());//todo store in content provider
                    }
                    // successfully decoded into the cache

                }

                // Gets a Message object, stores the state in it, and sends it to the Handler
                Message completeMessage = mHandler.obtainMessage(state, photoTask);
                completeMessage.sendToTarget();
                break;


            case REQUEST_COMPLETE:

                if (VERBOSE) Log.v(TAG,"received download_complete message from service... " +
                        "starting diskload.");
                /*
                 * Decodes the image, by queuing the decoder object to run in the decoder
                 * thread pool
                 */
                mDownloadThreadPool.execute(photoTask.getDiskloadRunnable());
                break;
            case REQUEST_FAILED:
                if (VERBOSE) Log.v(TAG, "received message from the service that the s3 download failed, " +
                        "removing task from waiting queue, and passing.");
                mWaitingPhotoTasks.remove(photoTask.getImageKey());
                break;


            case REQUEST_STARTED:
                if (VERBOSE) Log.v(TAG,"image download started... saving task");

                //add it to waiting tasks map
                mWaitingPhotoTasks.put(photoTask.getImageKey(), photoTask);

                mHandler.obtainMessage(state, photoTask).sendToTarget();
                break;

            // The task finished downloading the image
            case DISKLOAD_COMPLETE:

                if (VERBOSE) Log.v(TAG,"image diskload is complete... starting decode.");
                /*
                 * Decodes the image, by queuing the decoder object to run in the decoder
                 * thread pool
                 */

                if (photoTask.getByteBuffer() != null) {
                    mDecodeThreadPool.execute(photoTask.getPhotoDecodeRunnable());
                } else {
                    Log.e(TAG,"diskload failed to successfully load byte array");
                }

                mHandler.obtainMessage(state, photoTask).sendToTarget();
                break;

            case DOWNLOAD_COMPLETE:
                /*
                 * Decodes the image, by queuing the decoder object to run in the decoder
                 * thread pool
                 */
                mDecodeThreadPool.execute(photoTask.getPhotoDecodeRunnable());

                // In all other cases, pass along the message without any other action.
            default:
                mHandler.obtainMessage(state, photoTask).sendToTarget();

                break;
        }

        if (VERBOSE) Log.v(TAG,"exiting handleState...");
    }

    /**
     * Cancels all Threads in the ThreadPool
     */
    public static void cancelAll() {
        if (Constants.LOGV) Log.v(TAG,"entering... cancelAll");

        /*
         * Creates an array of tasks that's the same size as the task work queue
         */
        PhotoTask[] taskArray = new PhotoTask[sInstance.mDownloadWorkQueue.size()];

        // Populates the array with the task objects in the queue
        sInstance.mDownloadWorkQueue.toArray(taskArray);

        // Stores the array length in order to iterate over the array
        int taskArraylen = taskArray.length;

        /*
         * Locks on the singleton to ensure that other processes aren't mutating Threads, then
         * iterates over the array of tasks and interrupts the task's current Thread.
         */
        synchronized (sInstance) {

            // Iterates over the array of tasks
            for (int taskArrayIndex = 0; taskArrayIndex < taskArraylen; taskArrayIndex++) {

                // Gets the task's current thread
                Thread thread = taskArray[taskArrayIndex].mThreadThis;

                // if the Thread exists, post an interrupt to it
                if (null != thread) {
                    thread.interrupt();
                }
            }
        }

        /*
         * Clears all waiting photoTasks
         */
        sInstance.mWaitingPhotoTasks.clear();

        if (Constants.LOGV) Log.v(TAG,"exiting... cancelAll");
    }

    public static void cancelDirectory(String directory) {
        if (VERBOSE) Log.v(TAG,"entering cancelCategory, with: " + directory);

        /*
         * Creates an array of tasks that's the same size as the task work queue
         */
        PhotoTask[] taskArray = new PhotoTask[sInstance.mDownloadWorkQueue.size()];

        // Populates the array with the task objects in the queue
        sInstance.mDownloadWorkQueue.toArray(taskArray);

        // Stores the array length in order to iterate over the array
        int taskArraylen = taskArray.length;

        /*
         * Locks on the singleton to ensure that other processes aren't mutating Threads, then
         * iterates over the array of tasks and interrupts the task's current Thread.
         */
        synchronized (sInstance) {

            // Iterates over the array of tasks
            for (int taskArrayIndex = 0; taskArrayIndex < taskArraylen; taskArrayIndex++) {

                PhotoTask task = taskArray[taskArrayIndex];
                //ensures the tasks directory is the directory being cancelled
                if (task.getImageDirectory().equals(directory)) {
                    // Gets the task's current thread
                    Thread thread = task.mThreadThis;
                    // if the Thread exists, post an interrupt to it
                    if (null != thread) {
                        thread.interrupt();
                    }


                    //if its waiting to be downloaded, remove it
                    sInstance.mWaitingPhotoTasks.remove(task.getImageKey());
                }
            }
        }


        if (VERBOSE) Log.v(TAG,"exiting cancelCategory...");
    }

    /**
     * Stops a download Thread and removes it from the threadpool
     *
     * @param downloaderTask The download task associated with the Thread
     * @param imageKey The URL being downloaded
     */
    static public void removeDownload(PhotoTask downloaderTask, String imageKey) {
        if (VERBOSE) Log.v(TAG,"entering removeDownload...");
        // If the Thread object still exists and the download matches the specified URL
        if (downloaderTask != null && downloaderTask.getImageKey().equals(imageKey)) {
            if (Constants.LOGV) Log.v(TAG,"removing task for image key " + imageKey);
            /*
             * Locks on this class to ensure that other processes aren't mutating Threads.
             */
            synchronized (sInstance) {

                // Gets the Thread that the downloader task is running on
                Thread thread = downloaderTask.getCurrentThread();

                // If the Thread exists, posts an interrupt to it
                if (null != thread)
                    thread.interrupt();
            }
            /*
             * Removes the download Runnable from the ThreadPool. This opens a Thread in the
             * ThreadPool's work queue, allowing a task in the queue to start.
             */
            sInstance.mDownloadThreadPool.remove(downloaderTask.getDownloadRunnable());

            /*
             * Removes the photoTask that is waiting
             */
            sInstance.mWaitingPhotoTasks.remove(imageKey);
        }




        if (VERBOSE) Log.v(TAG,"exiting removeDownload...");
    }



    /**
     * Starts an image download and decode
     *
     * @param imageView The ImageView that will get the resulting Bitmap
     * @param cacheFlag Determines if caching should be used
     * @return The task instance that will handle the work
     */
    static public PhotoTask startDownload(
            PhotoView imageView,
            boolean cacheFlag) {
        if (VERBOSE) Log.v(TAG, "entering startDownload...");

        /*
         * Gets a task from the pool of tasks, returning null if the pool is empty
         */
        PhotoTask downloadTask = sInstance.mPhotoTaskWorkQueue.poll();

        // If the queue was empty, create a new task instead.
        if (null == downloadTask) {
            downloadTask = new PhotoTask(getMainHandler());
        }

        // Initializes the task
        downloadTask.initializeDownloaderTask(PhotoManager.sInstance, imageView, cacheFlag,
                sInstance.s3Client);

        /*
         * Provides the download task with the cache buffer corresponding to the URL to be
         * downloaded.
         */
        //downloadTask.setByteBuffer(null);

        downloadTask.setByteBuffer(sInstance.mPhotoCache.get(downloadTask.getImageKey()));

        // If the byte buffer was empty, the image isn't in memory cache
        if (null == downloadTask.getByteBuffer()) {


            if ((downloadTask.getImageDirectory().equals(Constants.KEY_S3_LOCAL_DIRECTORY) ||
                    downloadTask.getImageDirectory().equals(Constants.KEY_S3_MESSAGE_DIRECTORY))
                    && !BuildConfig.FLAVOR.equals("sales")) {

                if (VERBOSE) Log.w(TAG, "file is from local or message, direct downloading...");

                sInstance.mDownloadThreadPool.execute(downloadTask.getS3DownloadRunnable());
                imageView.setStatusResource(R.drawable.imagequeued);
            } else {

                //is it in the disk cache?
                File imagePath = new File(downloadTask.getCacheDirectory(), downloadTask.getImageKey());

                if (VERBOSE) Log.v(TAG,"does the path : " + imagePath.getAbsolutePath() + " exist?");
                if (imagePath.exists()) {
                    if (VERBOSE) Log.v(TAG, "file is stored in the disk cache, loading...");
                    sInstance.mDownloadThreadPool.execute(downloadTask.getDiskloadRunnable());
                    imageView.setStatusResource(R.drawable.imagequeued);

                //it was not,
                } else {
                    if (VERBOSE) Log.w(TAG, "file was not stored in the disk cache, and is not" +
                            " local or message. Requesting to download.");
            /*
             * "Executes" the tasks' download Runnable in order to download the image. If no
             * Threads are available in the thread pool, the Runnable waits in the queue.
             */
                    sInstance.mDownloadThreadPool.execute(downloadTask.getDownloadRequestRunnable());

                    // Sets the display to show that the image has been requested
                    imageView.setStatusResource(R.drawable.imagequeued);

                }
            }

        // The image was memory cached, so no download is required.
        } else {

           /*
            * Signals that the download is "complete", because the byte array already contains the
            * undecoded image. The decoding starts.
            */
        sInstance.handleState(downloadTask, DOWNLOAD_COMPLETE);

        }

        // Returns a task object, either newly-created or one from the task pool

        if (VERBOSE) Log.v(TAG, "exiting startDownload...");
        return downloadTask;
    }

    /**
     * Recycles tasks by calling their internal recycle() method and then putting them back into
     * the task queue.
     * @param downloadTask The task to recycle
     */
    void recycleTask(PhotoTask downloadTask) {
        if (VERBOSE)  Log.v(TAG,"entering recycleTask...");
        // Frees up memory in the task
        downloadTask.recycle();

        // Puts the task object back into the queue for re-use.
        mPhotoTaskWorkQueue.offer(downloadTask);

        if (VERBOSE)  Log.v(TAG,"exiting recycleTask...");
    }


}