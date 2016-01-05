package us.gravwith.android;


import android.app.Activity;
import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.app.Fragment;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RelativeLayout;


import java.net.HttpURLConnection;
import java.util.LinkedList;
import java.util.List;


/**
 * Author/Copyright John C. Quinn All Rights Reserved
 * Date last modified: 2015-06-17
 *
 * A simple {@link Fragment} subclass. factory method to
 * create an instance of this fragment.
 */
public class ReplyFragment extends Fragment implements LoaderManager.LoaderCallbacks<Cursor> {
    private static int currentThread = LiveFragment.NO_LIVE_THREADS_ID;
    public static final int REPLY_LOADER_ID = 3;

    private final boolean VERBOSE = true;
    private final String TAG = "ReplyFragment";

    private LiveFragment.onLiveFragmentInteractionListener mListener;
    private ListView mListView;

    private static ReplyButtonListener replyButtonListener;

    private int[] contentButtonArray = new int[]{0,1,2};

    HybridCursorAdapter mAdapter;

    public static ReplyFragment newInstance(int currentThread) {
        Bundle args = new Bundle();
        args.putInt(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID, currentThread);

        ReplyFragment fragment = new ReplyFragment();
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment ReplyFragment.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = getArguments();


        if (b != null) {

            if (VERBOSE) Log.v(TAG,"initializing loader at id " + ReplyFragment.REPLY_LOADER_ID);

            if (currentThread == LiveFragment.NO_LIVE_THREADS_ID) {
                currentThread = b.getInt(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID);
                Bundle args = new Bundle();
                args.putInt(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID, currentThread);

                mAdapter = new HybridCursorAdapter(getActivity(),null,0);

                getLoaderManager().restartLoader(ReplyFragment.REPLY_LOADER_ID, args, this);
            } else {
                currentThread = b.getInt(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID);
            }

        }


        replyButtonListener = new ReplyButtonListener();

    }

    @Override
    public void onDestroy() {
        getLoaderManager().destroyLoader(REPLY_LOADER_ID);

        replyButtonListener = null;
        mAdapter = null;
        PhotoManager.cancelDirectory(Constants.KEY_S3_REPLIES_DIRECTORY);
        super.onDestroy();
    }

    /**
     * method 'onAttach'
     *
     * called when the fragment attachs to the activity
     * @param activity what it attached to
     */
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

       // receiver = new ReplyReceiver();
//        IntentFilter filter = new IntentFilter(Constants.ACTION_IMAGE_REPLY_THUMBNAIL_LOADED);
//        filter.addAction(Constants.ACTION_IMAGE_REPLY_LOADED);
      //  activity.registerReceiver(receiver, filter);

        mListener = (MainActivity)activity;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        getLoaderManager().destroyLoader(REPLY_LOADER_ID);
    //    getActivity().unregisterReceiver(receiver);
    //    receiver = null;
        mListener = null;
    }

    /**
     * method 'onCreateView'
     *
     * everything to create the UI goes here.
     *
     * @param inflater the layoutInflater - is the object that create views from xml files
     * @param container the parent to place the view in - in this case, the ViewPager
     * @param savedInstanceState null unless something was saved.
     * @return the view to create
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (VERBOSE) {Log.v(TAG,"entering onCreateView...");}

        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_reply, container, false);

        mListView = (ListView)v.findViewById(R.id.reply_list_view);
        mListView.setAdapter(mAdapter);

/*        String[] fromColumns = {
                SQLiteDbContract.LiveReplies.COLUMN_NAME_NAME,
                SQLiteDbContract.LiveReplies.COLUMN_NAME_DESCRIPTION,
                SQLiteDbContract.LiveReplies.COLUMN_NAME_TIME};

        int[] toViews = {R.id.reply_detail_row_name, R.id.reply_detail_row_text, R.id.reply_detail_row_time};*/

        if (currentThread!=LiveFragment.NO_LIVE_THREADS_ID) {
            //mAdapter = new SimpleCursorAdapter(getActivity(),
//                    R.layout.fragment_reply_detail_row, null, fromColumns, toViews, 0);

            //mAdapter = new HybridCursorAdapter(getActivity(),null,0);

        }

        //contentButtonViews.add(v.findViewById(R.id.button_reply_load))

        if (VERBOSE) {Log.v(TAG, "exiting onCreateView...");}
        return v;
    }



    @Override
    public void onDestroyView() {
       //todo maintain active references to avoid the necessity to search

        View view = getView();
        if(view != null) {
            view.findViewById(R.id.button_reply_refresh).setOnClickListener(null);
            view.findViewById(R.id.button_send_reply).setOnClickListener(null);
            view.findViewById(R.id.button_reply_capture).setOnClickListener(null);
        }

        super.onDestroyView();
    }

    /**
     * method 'onViewCreated'
     *
     * @param view view that was created
     * @param savedInstanceState null
     */
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (VERBOSE) Log.v(TAG,"entering onViewCreated...");
        view.findViewById(R.id.button_reply_refresh).setOnClickListener(replyButtonListener);
        view.findViewById(R.id.button_send_reply).setOnClickListener(replyButtonListener);
        view.findViewById(R.id.button_reply_capture).setOnClickListener(replyButtonListener);
        setCurrentThread(String.valueOf(currentThread));
        //anything that requires the UI to already exist goes here
        if (VERBOSE) Log.v(TAG,"exiting onViewCreated...");
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);

        View view = getView();

        if(view != null) {
            view.findViewById(R.id.button_reply_refresh).setOnClickListener(replyButtonListener);
            view.findViewById(R.id.button_send_reply).setOnClickListener(replyButtonListener);
            view.findViewById(R.id.button_reply_capture).setOnClickListener(replyButtonListener);
            setCurrentThread(String.valueOf(currentThread));
        }
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

    public void setCurrentThread(String thread) {
        Log.i(TAG, "setting current thread to : " + thread + ".");
        //((ImageButton)getActivity().findViewById(R.id.button_reply_refresh)).setText(thread);
        currentThread = Integer.valueOf(thread);
        resetDisplay();
    }

    public void resetDisplay() {
        if (isAdded()) {
            Log.d(TAG, "restarting loader...");
            Bundle b = new Bundle();
            b.putString(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID, String.valueOf(currentThread));
            getLoaderManager().restartLoader(REPLY_LOADER_ID, b, this);
        }
    }

    public int getCurrentThread() {
        return currentThread;
    }

    public void triggerReplyRefresh() {
        //mListView.setAdapter(null);
        mListener.sendMsgRequestLiveThreads();
    }


    public void handleReplyResponseState(Message msg) {
        if (VERBOSE) {
            Log.v(TAG,"entering handleReplyResponseState...");
        }

        switch (msg.arg2) {
            case HttpURLConnection.HTTP_OK:
                if (VERBOSE) Log.v(TAG,"Response code : " + msg.arg2);

                mListView.setAdapter(mAdapter);
                break;

            default:
                //Toast.makeText(getActivity(), "Response code : " + msg.arg2, Toast.LENGTH_SHORT).show();
                break;
        }

        if (VERBOSE) {
            Log.v(TAG,"exiting handleReplyResponseState...");
        }
    }


    /**
     * class 'replyButtonListener
     *
     * listens to all the general live thread interactions
     */
    public class ReplyButtonListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            if (VERBOSE) {
                Log.v(TAG, "OnClickRegistered..." + v.toString());
            }

            EditText commentText;

            MainActivity activity = (MainActivity)getActivity();

            Bundle b = new Bundle();
            b.putString(Constants.KEY_ANALYTICS_CATEGORY,Constants.ANALYTICS_CATEGORY_REPLY);

            switch (v.getId()) {

                case R.id.button_reply_refresh:
                    if (isAdded()) {
                        triggerReplyRefresh();
                    }
                    b.putString(Constants.KEY_ANALYTICS_ACTION,"refresh");
                    b.putString(Constants.KEY_ANALYTICS_LABEL,"current thread");
                    b.putString(Constants.KEY_ANALYTICS_VALUE,String.valueOf(currentThread));

                    resetDisplay();
                    break;

                case R.id.button_send_reply:

                    if (isAdded()) {
                        commentText   = ((EditText) activity.findViewById(R.id.editText_reply_comment));
                        RelativeLayout layout = (RelativeLayout) commentText.getParent();

                        //activity.setReplyFilePath("");
                        activity.setLiveFilePath("");
                        activity.setLiveCreateThreadInfo("", commentText.getText().toString());
                        //activity.setLiveCreateReplyInfo(commentText.getText().toString(), getCurrentThread());
                        triggerReplyRefresh();

                        InputMethodManager imm = (InputMethodManager) activity
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(layout.getWindowToken(), 0);

                        commentText.setText("");

                        b.putString(Constants.KEY_ANALYTICS_ACTION, "send reply");
                        b.putString(Constants.KEY_ANALYTICS_LABEL, "current thread");
                        b.putString(Constants.KEY_ANALYTICS_VALUE, String.valueOf(currentThread));
                    }

                    break;

                case R.id.button_reply_capture:

                    if (isAdded()) {

                        commentText   = ((EditText) activity.findViewById(R.id.editText_reply_comment));
                        RelativeLayout layout = (RelativeLayout) commentText.getParent();
                        //activity.takeReplyPicture();
                        activity.takeLivePicture();
                        //activity.setLiveCreateReplyInfo(commentText.getText().toString(),
                          //      getCurrentThread());
                        activity.setLiveCreateThreadInfo("","",commentText.getText().toString());

                        InputMethodManager imm = (InputMethodManager) activity
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(layout.getWindowToken(), 0);

                        commentText.setText("");

                        b.putString(Constants.KEY_ANALYTICS_ACTION,"take picture");
                    }
                    break;


            }

            mListener.sendMsgReportAnalyticsEvent(b);
        }

    }


    /*@Override
    public CursorLoader onCreateLoader(int id, Bundle args) {
        if (VERBOSE) Log.v(TAG,"entering onCreateLoader...");

        String[] selectionArgs = {String.valueOf(args.get(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID))};

        Log.d(TAG,"current selection args = " + args.get(SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID));

            CursorLoader loader = new CursorLoader(
                    this.getActivity(),
                    FireFlyContentProvider.CONTENT_URI_REPLY_LIST,
                    null,
                    SQLiteDbContract.LiveReplies.COLUMN_NAME_THREAD_ID + " = ?" ,
                    selectionArgs,
                    SQLiteDbContract.LiveReplies.COLUMN_ID
            );

        if (VERBOSE) Log.v(TAG,"exiting onCreateLoader...");
            return loader;
    }*/

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (VERBOSE) Log.v(TAG,"enter onCreateLoader...");

        String[] projection = {
                SQLiteDbContract.LiveEntry.COLUMN_ID,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_TIME,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_DESCRIPTION,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_FILEPATH,
                SQLiteDbContract.LiveEntry.COLUMN_NAME_THREAD_ID,
        };

        if (VERBOSE) Log.v(TAG,"loader created.");
        if (VERBOSE) Log.v(TAG,"exit onCreateLoader...");

        return new CursorLoader(
                getActivity(),
                FireFlyContentProvider.CONTENT_URI_LIVE,
                projection,
                null,
                null,
                SQLiteDbContract.LiveEntry.COLUMN_ID);
        //sort by column ID
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (VERBOSE) Log.v(TAG,"entering onLoadFinished...");

          /* only set the action buttons to visible if there is content */
        if (data == null) {
            Log.e(TAG, "why was the returned cursor null?");
            mAdapter.swapCursor(null);
            return;
        }

        Log.e(TAG,"cursor row count : " + data.getCount());
        if (data.getCount() > 0) {
            View v = getView();
            if (v!=null) {
                //v.findViewById(R.id.button_reply_load).setVisibility(View.VISIBLE);
                //v.findViewById(R.id.button_reply_report).setVisibility(View.VISIBLE);
            }
        } else {
            View v = getView();
            if (v!=null) {
                //v.findViewById(R.id.button_reply_load).setVisibility(View.GONE);
                //v.findViewById(R.id.button_reply_report).setVisibility(View.GONE);
            }

            Log.i(TAG, "There are no replies... notify user?");
            //todo, explain what replies are, and suggest a reply
        }

        mAdapter.swapCursor(data);
        mListView.setAdapter(mAdapter);

        if (VERBOSE) Log.v(TAG,"exiting onLoadFinished...");
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        if (VERBOSE) Log.v(TAG, "entering onLoaderReset...");
        mAdapter.swapCursor(null);
        if (VERBOSE) Log.v(TAG,"exiting onLoaderReset...");
    }

}