package de.welthungerhilfe.cgm.scanner.ui.fragments;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import de.welthungerhilfe.cgm.scanner.AppController;
import de.welthungerhilfe.cgm.scanner.R;
import de.welthungerhilfe.cgm.scanner.datasource.repository.FileLogRepository;
import de.welthungerhilfe.cgm.scanner.ui.activities.ScanModeActivity;
import de.welthungerhilfe.cgm.scanner.helper.tango.CameraSurfaceRenderer;
import de.welthungerhilfe.cgm.scanner.helper.tango.OverlaySurface;

import static de.welthungerhilfe.cgm.scanner.helper.AppConstants.SCAN_LYING_BACK;
import static de.welthungerhilfe.cgm.scanner.helper.AppConstants.SCAN_LYING_FRONT;
import static de.welthungerhilfe.cgm.scanner.helper.AppConstants.SCAN_LYING_SIDE;
import static de.welthungerhilfe.cgm.scanner.helper.AppConstants.SCAN_PREVIEW;
import static de.welthungerhilfe.cgm.scanner.helper.AppConstants.SCAN_STANDING_BACK;
import static de.welthungerhilfe.cgm.scanner.helper.AppConstants.SCAN_STANDING_FRONT;
import static de.welthungerhilfe.cgm.scanner.helper.AppConstants.SCAN_STANDING_SIDE;

public class MeasureScanFragment extends Fragment implements View.OnClickListener {
    private final String TAG = "ScanningProcess";

    private static GLSurfaceView mCameraSurfaceView;
    private static OverlaySurface mOverlaySurfaceView;
    private CameraSurfaceRenderer mRenderer;

    private boolean mIsConnected = false;

    private int mDisplayRotation = Surface.ROTATION_0;

    private static final int INVALID_TEXTURE_ID = 0;
    private static final int SECS_TO_MILLISECS = 1000;

    // NOTE: Naming indicates which thread is in charge of updating this variable.
    private int mConnectedTextureIdGlThread = INVALID_TEXTURE_ID;
    private AtomicBoolean mIsFrameAvailableTangoThread = new AtomicBoolean(false);

    private TextView mTitleView;
    private ProgressBar progressBar;
    private FloatingActionButton fab;
    private Button btnRetake;

    // variables for Pose and point clouds
    private float mDeltaTime;
    private int mValidPoseCallbackCount;
    private int mPointCloudCallbackCount;
    private boolean mTimeToTakeSnap;
    private String mPointCloudFilename;
    private int mNumberOfFilesWritten;
    private ArrayList<float[]> mPosePositionBuffer;
    private ArrayList<float[]> mPoseOrientationBuffer;
    private ArrayList<Float> mPoseTimestampBuffer;
    private ArrayList<String> mPointCloudFilenameBuffer;
    private float[] cam2dev_Transform;
    private int mNumPoseInSequence;
    private int mPreviousPoseStatus;
    private float mPosePreviousTimeStamp;
    private float mPointCloudPreviousTimeStamp;
    private float mCurrentTimeStamp;

    private File mExtFileDir;
    private File mScanArtefactsOutputFolder;
    private String mPointCloudSaveFolderPath;
    private File mPointCloudSaveFolder;
    private File mRgbSaveFolder;

    private boolean mPointCloudAvailable;
    private boolean mIsRecording;
    private int mProgress;

    private long mNowTime;
    private String mNowTimeString;
    private String mQrCode;

    private Semaphore mutex_on_mIsRecording;

    private int mode = SCAN_PREVIEW;

    private FileLogRepository repository;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        repository = FileLogRepository.getInstance(context);

    }

    @Override
    public void onCreate(Bundle savedBundle) {
        super.onCreate(savedBundle);

        mPointCloudFilename = "";
        mNumberOfFilesWritten = 0;
        mPosePositionBuffer = new ArrayList<float[]>();
        mPoseOrientationBuffer = new ArrayList<float[]>();
        mPoseTimestampBuffer = new ArrayList<Float>();
        mPointCloudFilenameBuffer = new ArrayList<String>();
        mNumPoseInSequence = 0;
        mutex_on_mIsRecording = new Semaphore(1,true);
        mIsRecording = false;
        mPointCloudAvailable = false;

        mNowTime = System.currentTimeMillis();
        mNowTimeString = String.valueOf(mNowTime);
        mQrCode = ((ScanModeActivity)getActivity()).person.getQrcode();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_measure_scan, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mTitleView = view.findViewById(R.id.txtTitle);
        progressBar = view.findViewById(R.id.progressBar);
        fab = view.findViewById(R.id.fab_scan_result);
        fab.setOnClickListener(this);
        btnRetake = view.findViewById(R.id.btnRetake);
        view.findViewById(R.id.btnRetake).setOnClickListener(this);
        view.findViewById(R.id.imgClose).setOnClickListener(this);

        mCameraSurfaceView = view.findViewById(R.id.surfaceview);
        mOverlaySurfaceView = view.findViewById(R.id.overlaySurfaceView);

        switch (mode) {
            case SCAN_STANDING_FRONT:
                mTitleView.setText(getString(R.string.front_view_01) + " - " + getString(R.string.mode_standing));
                break;
            case SCAN_STANDING_SIDE:
                mTitleView.setText(getString(R.string.lateral_view_02) + " - " + getString(R.string.mode_standing));
                break;
            case SCAN_STANDING_BACK:
                mTitleView.setText(getString(R.string.back_view_03) + " - " + getString(R.string.mode_standing));
                break;
            case SCAN_LYING_FRONT:
                mTitleView.setText(getString(R.string.front_view_01) + " - " + getString(R.string.mode_lying));
                break;
            case SCAN_LYING_SIDE:
                mTitleView.setText(getString(R.string.lateral_view_02) + " - " + getString(R.string.mode_lying));
                break;
            case SCAN_LYING_BACK:
                mTitleView.setText(getString(R.string.back_view_03) + " - " + getString(R.string.mode_lying));
                break;
        }

        setupScanArtefacts();
        setupRenderer();
    }

    @Override
    public void onActivityCreated(Bundle savedBundle) {
        super.onActivityCreated(savedBundle);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();

        mCameraSurfaceView.onResume();
        mCameraSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        if (mode == SCAN_STANDING_FRONT || mode == SCAN_STANDING_SIDE || mode == SCAN_STANDING_BACK)
            mOverlaySurfaceView.setMode(OverlaySurface.INFANT_CLOSE_DOWN_UP_OVERLAY);
        else if (mode == SCAN_LYING_FRONT || mode == SCAN_LYING_SIDE || mode == SCAN_LYING_BACK)
            mOverlaySurfaceView.setMode(OverlaySurface.BABY_OVERLAY);
    }

    @Override
    public void onPause() {
        super.onPause();

        mCameraSurfaceView.onPause();

    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.fab_scan_result:
                if (mIsRecording) {
                    if (mProgress >= 100) {
                        completeScan();
                    } else {
                        pauseScan();
                    }
                } else {
                    if (mProgress > 0) {
                        resumeScan();
                    } else {
                        startScan();
                    }
                }
                break;
            case R.id.imgClose:
                ((ScanModeActivity)getActivity()).closeScan();
                break;
            case R.id.btnRetake:
                mProgress = 0;
                break;
        }
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    private void setupScanArtefacts() {
        // TODO make part of AppController?
        /*
        File mExtFileDir;
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            mExtFileDir = new File(Environment.getExternalStorageDirectory(), getString(R.string.app_name_long));
        } else {
            mExtFileDir = getContext().getFilesDir();
        }
        */
        mExtFileDir = AppController.getInstance().getRootDirectory();

        // TODO make part of AppConstants
        Log.e("Root Directory", mExtFileDir.getParent());
        mScanArtefactsOutputFolder  = new File(mExtFileDir,mQrCode+"/measurements/"+mNowTimeString+"/");
        mPointCloudSaveFolder = new File(mScanArtefactsOutputFolder,"pc");
        mRgbSaveFolder = new File(mScanArtefactsOutputFolder,"rgb");

        if(!mPointCloudSaveFolder.exists()) {
            boolean created = mPointCloudSaveFolder.mkdirs();
            if (created) {
                Log.i(TAG, "Folder: \"" + mPointCloudSaveFolder + "\" created\n");
            } else {
                Log.e(TAG,"Folder: \"" + mPointCloudSaveFolder + "\" could not be created!\n");
            }
        }

        if(!mRgbSaveFolder.exists()) {
            boolean created = mRgbSaveFolder.mkdirs();
            if (created) {
                Log.i(TAG, "Folder: \"" + mRgbSaveFolder + "\" created\n");
            } else {
                Log.e(TAG,"Folder: \"" + mRgbSaveFolder + "\" could not be created!\n");
            }
        }

        Log.v(TAG,"mPointCloudSaveFolder: "+mPointCloudSaveFolder);
        Log.v(TAG,"mRgbSaveFolder: "+mRgbSaveFolder);
    }

    // TODO: setup own renderer for scanning process (or attribute Apache License 2.0 from Google)
    private void setupRenderer() {
        mCameraSurfaceView.setEGLContextClientVersion(2);
        mRenderer = new CameraSurfaceRenderer(new CameraSurfaceRenderer.RenderCallback() {

            @Override
            public void preRender() {

                // This is the work that you would do on your main OpenGL render thread.

                // We need to be careful to not run any Tango-dependent code in the OpenGL
                // thread unless we know the Tango Service to be properly set up and connected.
                if (!mIsConnected) {
                    return;
                }
            }
        });

        mCameraSurfaceView.setRenderer(mRenderer);
    }



    private void updateScanningProgress(int numPoints, float distance, float confidence) {
        float minPointsToCompleteScan = 199500.0f;
        float progressToAddFloat = numPoints / minPointsToCompleteScan;
        progressToAddFloat = progressToAddFloat*100;
        int progressToAdd = (int) progressToAddFloat;
        Log.d(TAG, "numPoints: "+numPoints+" float: "+progressToAddFloat+" currentProgress: "+mProgress+" progressToAdd: "+progressToAdd);
        if (mProgress+progressToAdd > 100) {
            mProgress = 100;
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    fab.setImageResource(R.drawable.done);
                }
            });
        } else {
            mProgress = mProgress+progressToAdd;
        }

        Log.d("scan_progress", String.valueOf(mProgress));
        Log.d("scan_progress_step", String.valueOf(progressToAdd));
    }

    private void startScan() {
        mProgress = 0;

        resumeScan();
    }

    private void resumeScan() {
        if (mode == SCAN_PREVIEW)
            return;

        mIsRecording = true;
        fab.setImageResource(R.drawable.stop);
    }

    private void pauseScan() {
        mIsRecording = false;
        fab.setImageResource(R.drawable.recorder);
    }

    private void completeScan() {
        mIsRecording = false;

        ((ScanModeActivity)getActivity()).goToNextStep();
    }
}
