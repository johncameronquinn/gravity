package us.gravwith.android;

import android.app.Activity;
import android.app.LoaderManager;
import android.app.Fragment;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.github.clans.fab.FloatingActionMenu;

import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.util.UUID;

import fr.castorflex.android.verticalviewpager.VerticalViewPager;


/**
 * Author/Copyright John C. Quinn, All Rights Reserved.
 *
 * class 'LiveFragment'
 *
 * contains the live portion of the app
 *
 * //todo use a disk cache for storing thread topics
 */
public class LiveFragment extends BaseFragment implements
        LoaderManager.LoaderCallbacks<Cursor>, ViewPager.OnPageChangeListener {
    public static final boolean VERBOSE = false;
    private static final String TAG = "LiveFragment";

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private final String CURRENT_THREAD_KEY = "threadkey";
    // private final String THREAD_PAGER_KEY = "pagerkey";

    private final int LIVE_LOADER_ID = 1;
    private final int LIVE_OFFSCREEN_LIMIT = 3;

    private boolean hasRefreshed = false;

    public static final int NO_LIVE_THREADS_ID = -1;

    private VerticalViewPager threadPager;
    private CursorPagerAdapter mAdapter;
    private onLiveFragmentInteractionListener mListener;

    private TextView titleView;
    private TextView uniqueView;
    private TextView replyView;
    private TextView timeView;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param messageCount the current number of messages that have been recieved.
     * @param threadPosition your thread's current position in live.
     * @return A new instance of fragment LocalFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static LiveFragment newInstance(String messageCount, String threadPosition) {
        LiveFragment fragment = new LiveFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, messageCount);
        fragment.setArguments(args);
        return fragment;
    }


    /**
     * method 'createThread'
     *
     * displays a view allowing the user to enter the necessary information to create a thread
     *
     */
    /*public void createThread(MainActivity activity) {
        activity.disableScrolling();
        LayoutInflater inflater = (LayoutInflater)activity.getSystemService
                (Context.LAYOUT_INFLATER_SERVICE);
        FrameLayout root = (FrameLayout)activity.findViewById(R.id.live_root_layout);
        View v = inflater.inflate(R.layout.live_post,
                root,
                false);

        getPostButtonListener(this).setCreateView(v);
        v.findViewById(R.id.button_cancel_post).setOnClickListener(getPostButtonListener(this));
        v.findViewById(R.id.button_confirm_post).setOnClickListener(getPostButtonListener(this));
         root.addView(v,root.getChildCount());
    }*/

    public LiveFragment() {
        // Required empty public constructor
    }

/***************************************************************************************************
* LIFECYCLE METHODS
*/
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (VERBOSE) Log.v(TAG,"entering onCreate...");

        if (savedInstanceState != null) {
            //Log.d(TAG,"restoring live state...");
        }

        if (getArguments() != null) {
            //mParam1 = getArguments().getString(ARG_PARAM1);
            //mParam2 = getArguments().getString(ARG_PARAM2);
        }
        if (savedInstanceState != null) {
        } else {

        }

        if (VERBOSE) Log.v(TAG,"initializing loader at id " + LIVE_LOADER_ID);
        getLoaderManager().restartLoader(LIVE_LOADER_ID, null, this);
        if (VERBOSE) Log.v(TAG,"exiting onCreate...");
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (VERBOSE) Log.v(TAG,"entering onDestroy...");

        if (VERBOSE) Log.v(TAG,"destroying loader at id " + LIVE_LOADER_ID);
        getLoaderManager().destroyLoader(LIVE_LOADER_ID);

        PhotoManager.cancelDirectory(Constants.KEY_S3_LIVE_DIRECTORY);
        if (VERBOSE) Log.v(TAG,"exiting onDestroy...");
        super.onDestroy();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mListener = (onLiveFragmentInteractionListener)context;

        String[] projection = {
                SQLiteDbContract.LiveEntry.COLUMN_ID,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_NAME,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_TITLE,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_DESCRIPTION,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_FILEPATH,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_THREAD_ID,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_REPLIES,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_UNIQUE
        };

      }

    //todo, this is a workaround for a bug and can be removed in the future
    public void onAttach(Activity context) {
        super.onAttach(context);

        mListener = (onLiveFragmentInteractionListener)context;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (VERBOSE) Log.v(TAG,"entering onSaveInstanceState...");
        //outState.putString(CURRENT_THREAD_KEY, currentThread.toString());
    //    outState.putInt(THREAD_PAGER_KEY, threadPager.getCurrentItem());
        /*View v = threadPager.getChildAt(threadPager.getCurrentItem());
        SparseArray<Parcelable> viewsave = new SparseArray<>();
        v.saveHierarchyState(viewsave);
        outState.putSparseParcelableArray(VIEW_HACK_KEY, viewsave);*/

        if (VERBOSE) Log.v(TAG,"exiting onSaveInstanceState...");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (VERBOSE) Log.v(TAG,"entering onCreateView...");

        View rootView = inflater.inflate(R.layout.fragment_live,container,false);
        rootView.findViewById(R.id.button_live_refresh).setOnClickListener(getButtonListener(this));
        //rootView.findViewById(R.id.button_new_thread).setOnClickListener(getButtonListener(this));
        rootView.findViewById(R.id.button_live_report).setOnClickListener(getButtonListener(this));
       // rootView.findViewById(R.id.button_live_hide).setOnClickListener(getButtonListener(this));

        titleView = (TextView)rootView.findViewById(R.id.textView_title);
        uniqueView = (TextView)rootView.findViewById(R.id.textView_unique_posters);
        replyView = (TextView)rootView.findViewById(R.id.textView_reply_count);
        timeView = (TextView)rootView.findViewById(R.id.textView_relative_time);
        //((SeekBar)rootView.findViewById(R.id.seekBar)).setOnSeekBarChangeListener(getButtonListener(this));

        if (VERBOSE) Log.v(TAG,"exiting onCreateView...");
        return rootView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (VERBOSE) Log.v(TAG,"entering onViewCreated...");

        threadPager = (VerticalViewPager)view.findViewById(R.id.live_thread_pager);
        threadPager.setOnPageChangeListener(this);
        threadPager.setAdapter(mAdapter);
        threadPager.setOffscreenPageLimit(LIVE_OFFSCREEN_LIMIT);

        if (savedInstanceState != null) {
            Log.d(TAG,"savedState was not null");
//            int instate = savedInstanceState.getInt(THREAD_PAGER_KEY);
       //     int outstate = instate + 3;

      /*      View a = new View(getActivity());
            a.restoreHierarchyState(savedInstanceState.getSparseParcelableArray(VIEW_HACK_KEY));
            threadPager.removeViewAt(0);
            threadPager.addView(a,0);*/
        }

        if (VERBOSE) Log.v(TAG,"exiting onViewCreated...");
    }

    @Override
    public void onDestroyView() {
        if (VERBOSE) Log.v(TAG,"entering onDestroyView...");

        //threadPager.setAdapter(null);
        threadPager.setOnPageChangeListener(null);
        threadPager = null;

        titleView = null;
        uniqueView = null;
        replyView = null;
        timeView = null;

        if (VERBOSE) Log.v(TAG,"exiting onDestroyView...");
        super.onDestroyView();
    }
/***************************************************************************************************
 * USER INTERACTION
 */
    /**
     * method 'onActivityResult'
     *
     * called when the user selects an image to be loaded into live, or cancels
     *
     * @param requestCode code supplied to the activity
     * @param resultCode result be it cancel or otherwise
     * @param data the uri returned
     */
    private final int SELECT_PICTURE = 1;

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == SELECT_PICTURE) {
                Uri selectedImageUri = data.getData();
                Bitmap b = null;
                try {
                    b = BitmapFactory.decodeStream(getActivity()
                            .getContentResolver()
                            .openInputStream(selectedImageUri));
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "error decoding stream from returned URI...");
                }

            } else {

            }
        }
    }

    public void triggerLiveRefresh() {
        if (VERBOSE) Log.v(TAG,"entering triggerLiveRefresh...");

        mListener.sendMsgRequestLiveThreads();
        //threadPager.setAdapter(null);
        hasRefreshed = true;

        if (VERBOSE) Log.v(TAG,"exiting triggerLiveRefresh...");
    }

    public void resetLiveAdapter() {
        if (VERBOSE) Log.v(TAG,"entering resetLiveAdapter...");
        threadPager.setAdapter(mAdapter);
        if (VERBOSE) Log.v(TAG,"exiting resetLiveAdapter...");
    }


    public void closeRadical() {
        ((FloatingActionMenu)getActivity().findViewById(R.id.live_radical_menu)).close(true);
    }


    /**
     * class 'liveButtonListener
     *
     * listens to all the general live thread interactions
     */
    public class LiveButtonListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            if (VERBOSE) {
                Log.v(TAG,"OnClickRegistered..." + v.toString());
            }

            mListener.getAnalyticsReporter().ReportClickEvent(v);

            switch (v.getId()) {

                case R.id.button_live_refresh:
                    //starts a refresh and waits for success
                    triggerLiveRefresh();
                    break;

                /*case R.id.button_new_thread:
                    //setSeekMode(v);
                    mListener.takeLivePicture();
                    break;*/

                case R.id.button_live_report:
                    if (VERBOSE) Log.v(TAG,"entering report mode...");

                    ReportManager manager = new ReportManager((MainActivity)getActivity(),threadPager,
                            new ReportManager.ReportStatusListener() {
                        @Override
                        public void onRequestSuccess() {

                        }

                        @Override
                        public void onRequestError(int code) {

                        }

                        @Override
                        public void onRequestStarted() {

                        }

                        @Override
                        public void onDialogClosed(boolean didTheyHitYes) {
                            mListener.getAnalyticsReporter().ReportBehaviorEvent(
                                    AnalyticsReporter.ANALYTICS_ACTION_BUTTON_PRESS,
                                    mListener.getAnalyticsReporter()
                                            .getButtonResourceID(R.id.button_live_report),
                                    (didTheyHitYes) ? "yes":"no"
                            );
                        }
                    });

                    manager.setItemIDAndShow(mAdapter
                            .getCurrentFragment()
                            .getThreadID()
                    );

                    break;

                /*case R.id.button_live_save:
                    //gets the photoView of the thread currently selected by the pager, and saves.

                    //Fragment fragment = mAdapter.getItem(currentThread);
                    //v = fragment.getView();
                    LiveThreadFragment liveFragment = mAdapter.getCurrentFragment();

                    if (liveFragment!=null) {
                        Log.v(TAG,"liveThreadFragment returned: " + liveFragment.toString());
                        PhotoView photoView = liveFragment.mPhotoView;
                        mListener.saveToStash(photoView);
                    } else {
                        Log.e(TAG,"fragment.getView() returned null");
                    }
                    break;*/

               /* case R.id.button_live_hide:

                    Toast.makeText(getActivity(),"not yet working...",Toast.LENGTH_SHORT).show();
                    // STOPSHIP: 1/18/16 Hide must be working

                    /*
                    LiveThreadFragment fragment = mAdapter.getCurrentFragment();

                    String selectionClause = SQLiteDbContract.LiveEntry
                            .COLUMN_NAME_THREAD_ID + " = ?";

                    String[] selectionArgs = {fragment.getThreadID()};

                    getActivity().getContentResolver().delete(
                            FireFlyContentProvider.CONTENT_URI_LIVE,
                            selectionClause,
                            selectionArgs
                    );*/

                    //break;

            }
        }

    }

    /**
     * static method getButtonListener
     *
     * this is used to create a singleton reference to a buttonlistener, allowing one
     * buttonlistener to handle all button interactions
     *
     *
     * s@param parent the context in which this functions
     * @return a ButtonListener to be used by all the buttons in CameraFragment
     */
    private static WeakReference<LiveButtonListener> buttonListenerReference;

    public static LiveButtonListener getButtonListener(LiveFragment parent) {
        if (buttonListenerReference == null) {
            buttonListenerReference = new WeakReference<>(parent.new LiveButtonListener());
        }

        return buttonListenerReference.get();
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

    }

    @Override
    public void onPageSelected(int position) {
        if (VERBOSE) Log.v(TAG, "entering onPageSelected... page " + position + " selected.");

        UUID currentThread = getCurrentThreadID();
        mListener.sendMsgRequestReplies(currentThread);

        if (VERBOSE) Log.v(TAG,"initializing loader at id " + ReplyFragment.REPLY_LOADER_ID);

        Bundle args = new Bundle();
        args.putString(CURRENT_THREAD_KEY, String.valueOf(currentThread));

        mListener.setCurrentThread(
                currentThread,
                getCurrentTopicARN(),
                getCurrentRepliesCount(),
                getCurrentDescription(),
                getCurrentImageKey(),
                getCurrentTime()
        );

        LiveThreadFragment f = (LiveThreadFragment)mAdapter.getItem(position);
        updateViews(f);

        mListener.updateReplyViews();

        //report thread view to analytics service
        if (mListener != null) {
        }

        if (VERBOSE) Log.v(TAG, "exiting onPageSelected...");
    }

    @Override
    public void onPageScrollStateChanged(int state) {
    }

    public void updateViews(LiveThreadFragment mThreadFragment) {
        if (uniqueView != null) {

            if (mThreadFragment == null) {
                titleView.setText("");
                uniqueView.setText("");
                timeView.setText("");
                replyView.setText("");
            } else {
                titleView.setText(mThreadFragment.getTitle());
                uniqueView.setText(mThreadFragment.getUniqueCount());
                timeView.setText(mThreadFragment.getRelativeTime());
                replyView.setText(mThreadFragment.getReplyCount());
            }
        }
    }



    /**************************************************************************************************
     * CONTENT LOADING
      */

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (VERBOSE) Log.v(TAG,"enter onCreateLoader...");

        String[] projection = {
                SQLiteDbContract.LiveEntry.COLUMN_ID,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_NAME,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_TIME,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_TITLE,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_DESCRIPTION,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_FILEPATH,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_THREAD_ID,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_REPLIES,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_TOPIC_ARN,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_UNIQUE
        };

        mAdapter = new CursorPagerAdapter<>(getChildFragmentManager(),
                LiveThreadFragment.class,
                projection,
                null);

        if (VERBOSE) Log.v(TAG,"loader created.");
        if (VERBOSE) Log.v(TAG,"exit onCreateLoader...");
        return new CursorLoader(getActivity(),
                FireFlyContentProvider.CONTENT_URI_LIVE,
                projection,
                null,
                null,
                SQLiteDbContract.LiveEntry.COLUMN_ID);
        //sort by column ID
    }

    private UUID getCurrentThreadID() {
        UUID out = null;
        LiveThreadFragment f = (LiveThreadFragment)mAdapter.getItem(threadPager.getCurrentItem());
        if (f != null) {
            out = f.getThreadID();
        }
        return out;
    }

    private String getCurrentTopicARN() {
        String out = "";

        LiveThreadFragment f = (LiveThreadFragment)mAdapter.getItem(threadPager.getCurrentItem());
        if (f != null) {
            out = f.getTopicARN();
        }

        return out;
    }

    private String getCurrentRepliesCount() {
        LiveThreadFragment f = (LiveThreadFragment)mAdapter.getItem(threadPager.getCurrentItem());
        if (f!=null) {
            return f.getReplyCount();
        } else {
            return "";
        }
    }

    private String getCurrentTime() {
        LiveThreadFragment f = (LiveThreadFragment)mAdapter.getItem(threadPager.getCurrentItem());
        if (f!=null) {
            return f.getRelativeTime();
        } else {
            return "";
        }
    }

    private String getCurrentDescription() {
        LiveThreadFragment f = (LiveThreadFragment)mAdapter.getItem(threadPager.getCurrentItem());
        if (f!=null) {
            return f.getDescription();
        } else {
            return "";
        }
    }

    private String getCurrentImageKey() {
        LiveThreadFragment f = (LiveThreadFragment)mAdapter.getItem(threadPager.getCurrentItem());
        if (f!=null) {
            return f.mImageKey;
        } else {
            return "";
        }
    }

    public void updateReplyCount(String replyCount) {
        replyView.setText(replyCount);
    }


    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (VERBOSE) Log.v(TAG,"enter onLoadFinished...");


        /* only set the action buttons to visible if there is content */
        if (data == null) {
            return;
        }
        if (data.getCount() > 0) {
            View v = getView();
            if (v!=null) {
                //v.findViewById(R.id.button_live_save).setVisibility(View.VISIBLE);
                //v.findViewById(R.id.button_live_load).setVisibility(View.VISIBLE);
                //v.findViewById(R.id.button_live_hide).setVisibility(View.VISIBLE);
                //v.findViewById(R.id.button_live_report).setVisibility(View.VISIBLE);
            }
        } else {
            View v = getView();
            if (v!=null) {
                //v.findViewById(R.id.button_live_save).setVisibility(View.GONE);
                //v.findViewById(R.id.button_live_load).setVisibility(View.GONE);
                //(v.findViewById(R.id.button_live_hide)).setEnabled(false);
                //v.findViewById(R.id.button_live_report).setVisibility(View.GONE);
            }

            Log.i(TAG,"there are no messages pending or received... display something...?");
            //todo, show text that explains to the user what message is and how use
        }

        if (mAdapter != null) {
            Log.i(TAG, "Live cursor finished loading data");
            mAdapter.swapCursor(data);

            if (mAdapter.getCount() > 0) {
                mListener.setCurrentThread(
                        getCurrentThreadID(),
                        getCurrentTopicARN(),
                        getCurrentRepliesCount(),
                        getCurrentDescription(),
                        getCurrentImageKey(),
                        getCurrentTime()
                );
            }

            threadPager.setAdapter(mAdapter);
            updateViews((LiveThreadFragment) mAdapter.getItem(threadPager.getCurrentItem()));
            mListener.updateReplyViews();

            Log.d(TAG, "Returned cursor contains: " + data.getCount() + " rows.");

        }

        if (VERBOSE) Log.v(TAG,"exit onLoadFinished...");
    }


    /**
     * method 'onLoaderReset'
     *
     * called when the data at the loader is no longer available
     *
     * @param loader the loader which was reset
     */
    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        if (VERBOSE) Log.v(TAG,"enter onLoaderReset...");

        mAdapter.swapCursor(null);

        if (VERBOSE) Log.v(TAG,"exit onLoaderReset...");
    }

    public interface onLiveFragmentInteractionListener extends BaseFragmentInterface {
        //void setAnalyticsFragment(String name);
        void sendMsgRequestLiveThreads();
        void sendMsgRequestReplies(UUID threadID);
        void setCurrentThread(UUID threadID,String topicARN, String desc, String imageKey,String repliesCount, String time);
        void takeLivePicture();
        void saveToStash(PhotoView imageToSave);
        UUID getCurrentThread();
        String getCurrentTopicARN();
        String getCurrentDescription();
        String getCurrentImageKey();
        void updateReplyViews();
        void updateCurrentReplies(int count);
        void updateLiveReplyCount();
        void swapTopics(String newTopic);
        String getCurrentRepliesCount();
    }



}
