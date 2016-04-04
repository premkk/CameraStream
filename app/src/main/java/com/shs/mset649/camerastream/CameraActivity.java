package com.shs.mset649.camerastream;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.view.View;
import android.app.Activity;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.content.SharedPreferences.Editor;

//import android.view.SurfaceView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.rtsp.RtspServer;
import net.majorkernelpanic.streaming.video.VideoQuality;

/*
 * A basic demonstration of how to write a point-and-shoot camera app against the new
 * android.hardware.camera2 API.
 *
 * FIXME: Start streaming using this URL: rtsp://10.0.1.38:1234?h264=200-20-320-240
 * FIXME: 200 - buf 20 - fps 320 - resolution w 240 - resolution h
 */
public class CameraActivity extends Activity implements Session.Callback{
    /* Tag to distinguish log prints. */
    static final String TAG = "CameraActivity";

    /* Request code for camera permissions. */
    private static final int REQUEST_CAMERA_PERMISSIONS = 1;

    /* Activity */
    Activity mActivity;

    /* An additional thread for running tasks that shouldn't block the UI. */
    HandlerThread mBackgroundThread;

    /* Handler for running tasks in the background. */
    Handler mBackgroundHandler;

    /* Handler for running tasks on the UI thread. */
    Handler mForegroundHandler;

    /* View for displaying the camera preview. */
    SurfaceView mSurfaceView;

    /* Handle to the Android camera services. */
    CameraManager mCameraManager;

    /* The specific camera device that we're using. */
    CameraDevice mCamera;

    /* Our image capture session. */
    CameraCaptureSession mCaptureSession;

    /* Set up a session for streaming */
    Session mSession;

    /*
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values.
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    static Size chooseBigEnoughSize(Size[] choices, int width, int height) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    /**
     * Called when our {@code Activity} gains focus. <p>Starts initializing the camera.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Save activity
        mActivity = this;

        // Start a background thread to manage camera requests
        mBackgroundThread = new HandlerThread("background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mForegroundHandler = new Handler(getMainLooper());
        mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // Inflate the SurfaceView, set it as the main layout, and attach a listener
        View layout = getLayoutInflater().inflate(R.layout.mainactivity, null);
        mSurfaceView = (SurfaceView) layout.findViewById(R.id.mainSurfaceView);
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        setContentView(mSurfaceView);

        // Control flow continues in mSurfaceHolderCallback.surfaceChanged()
    }

    /**
     * Called when our {@code Activity} loses focus. <p>Tears everything back down.</p>
     */
    @Override
    protected void onPause() {
        super.onPause();
        try {
            // Ensure SurfaceHolderCallback#surfaceChanged() will run again if the user returns
            mSurfaceView.getHolder().setFixedSize(/*width*/0, /*height*/0);

            // Cancel any stale preview jobs
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
        } finally {
            if (mCamera != null) {
                mCamera.close();
                mCamera = null;
            }
        }

        // Finish processing posted messages, then join on the handling thread
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
        } catch (InterruptedException ex) {
            Log.e(TAG, "Background worker thread was interrupted while joined", ex);
        }

    }


    /*
     * Callbacks invoked upon state changes in our {@code SurfaceView}.
     */
    final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        /* The camera device to use, or null if we haven't yet set a fixed surface size. */
        private String mCameraId;

        /* Whether we received a change callback after setting our fixed surface size. */
        private boolean mGotSecondCallback;

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            // This is called every time the surface returns to the foreground
            Log.i(TAG, "Surface created");
            mCameraId = null;
            mGotSecondCallback = false;
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "Surface destroyed");
            holder.removeCallback(this);
            // We don't stop receiving callbacks forever because onResume() will reattach us
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // On the first invocation, width and height were automatically set to the view's size
            if (mCameraId == null) {
                // Find the device's back-facing camera and set the destination buffer sizes
                try {
                    for (String cameraId : mCameraManager.getCameraIdList()) {
                        CameraCharacteristics cameraCharacteristics =
                                mCameraManager.getCameraCharacteristics(cameraId);
                        if (cameraCharacteristics.get(cameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK) {
                            Log.i(TAG, "Found a back-facing camera");
                            StreamConfigurationMap info = cameraCharacteristics
                                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                            // Danger, W.R.! Attempting to use too large a preview size could
                            // exceed the camera bus' bandwidth limitation, resulting in
                            // gorgeous previews but the storage of garbage capture data.
                            Log.i(TAG, "SurfaceView size: " +
                                    mSurfaceView.getWidth() + 'x' + mSurfaceView.getHeight());

                            Size optimalSize = chooseBigEnoughSize(
                                    info.getOutputSizes(SurfaceHolder.class), width, height);
                            // Set the SurfaceHolder to use the camera's largest supported size
                            Log.i(TAG, "Preview size: " + optimalSize);
                            SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
                            surfaceHolder.setFixedSize(optimalSize.getWidth(),
                                    optimalSize.getHeight());

                            mCameraId = cameraId;

                            return;
                            // Control flow continues with this method one more time
                            // (since we just changed our own size)
                        }
                    }
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Unable to list cameras", ex);
                }
                Log.e(TAG, "Didn't find any back-facing cameras");


            } else if (!mGotSecondCallback) {
                // This is the second time the method is being invoked: our size change is complete

                if (mCamera != null) {
                    Log.e(TAG, "Aborting camera open because it hadn't been closed");
                    return;
                }

                // Open the camera device
                try {
                    if (ActivityCompat.checkSelfPermission(mActivity, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(mActivity,
                                new String[]{Manifest.permission.CAMERA},
                                REQUEST_CAMERA_PERMISSIONS);
                        return;
                    }

                    mCameraManager.openCamera(mCameraId, mCameraStateCallback,
                            mBackgroundHandler);

                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to configure output surface", ex);
                }

                mGotSecondCallback = true;
                // Control flow continues in mCameraStateCallback.onOpened()
            }
        }};


    /*
     * Calledbacks invoked upon state changes in our {@code CameraDevice}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {

                @Override
                public void onOpened(CameraDevice camera) {
                    Log.i(TAG, "Successfully opened camera");
                    mCamera = camera;
                    try {
                        List<Surface> outputs = Arrays.asList(
                                mSurfaceView.getHolder().getSurface());
                        camera.createCaptureSession(outputs, mCaptureSessionCallback,
                                mBackgroundHandler);
                    } catch (CameraAccessException ex) {
                        Log.e(TAG, "Failed to create a capture session", ex);
                    }
                    // Control flow continues in mCaptureSessionCallback.onConfigured()
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.e(TAG, "Camera was disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "State error on device '" + camera.getId() + "': code " + error);
                }};


    /*
     * Callbacks invoked upon state changes in our {@code CameraCaptureSession}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraCaptureSession.StateCallback mCaptureSessionCallback
            = new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.i(TAG, "Finished configuring camera outputs");
                    mCaptureSession = session;
                    SurfaceHolder holder = mSurfaceView.getHolder();
                    if (holder != null) {
                        try {
                            // Build a request for preview footage
                            CaptureRequest.Builder requestBuilder =
                                    mCamera.createCaptureRequest(mCamera.TEMPLATE_PREVIEW);
                            requestBuilder.addTarget(holder.getSurface());
                            CaptureRequest previewRequest = requestBuilder.build();

                            // Start displaying preview images
                            try {
                                session.setRepeatingRequest(previewRequest, /*listener*/null,
                                /*handler*/null);
                            } catch (CameraAccessException ex) {
                                Log.e(TAG, "Failed to make repeating preview request", ex);
                            }

                            // Start streaming
                            startStreaming();
                        } catch (CameraAccessException ex) {
                            Log.e(TAG, "Failed to build preview request", ex);
                        }
                    }
                    else {
                        Log.e(TAG, "Holder didn't exist when trying to formulate preview request");
                    }
                }

                @Override
                public void onClosed(CameraCaptureSession session) {
                    mCaptureSession = null;
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Configuration error on device '" + mCamera.getId());
                }};


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    showMissingPermissionError();
                    return;
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void showMissingPermissionError() {
        Activity activity = mActivity;
        if (activity != null) {
            Toast.makeText(activity, "Permission request error", Toast.LENGTH_SHORT).show();
            activity.finish();
        }
    }


    private void startStreaming() {
        // Sets the port of the RTSP server to 1234
        Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(RtspServer.KEY_PORT, String.valueOf(1234));
        editor.putBoolean(RtspServer.KEY_ENABLED, true);
        editor.commit();

        mSession = SessionBuilder.getInstance()
                .setCallback(this)
                .setSurfaceView(mSurfaceView)
                .setPreviewOrientation(90)
                .setContext(getApplicationContext())
                .setAudioEncoder(SessionBuilder.AUDIO_NONE)
                .setAudioQuality(new AudioQuality(16000, 32000))
                .setVideoEncoder(SessionBuilder.VIDEO_H264)
                .setVideoQuality(new VideoQuality(320,240,20,500000))
                .setDestination("10.0.1.37") // FIXME: hard coded to the client address
                .build();

        // Starts the RTSP server
        this.startService(new Intent(this,RtspServer.class));
        Log.i(TAG, "Configuration error on device '" + mCamera.getId());
    }

    @Override
    public void onSessionConfigured() {
        Log.d(TAG,"Preview configured.");
        // Once the stream is configured, you can get a SDP formated session description
        // that you can send to the receiver of the stream.
        // For example, to receive the stream in VLC, store the session description in a .sdp file
        // and open it with VLC while streming.
        //Log.d(TAG, mSession.getSessionDescription());
        mSession.start();
    }

    @Override
    public void onSessionStarted() {
        Log.d(TAG,"Session started.");
        //mButton1.setEnabled(true);
        //mButton1.setText(R.string.stop);
    }

    @Override
    public void onSessionStopped() {
        Log.d(TAG,"Session stopped.");
        //mButton1.setEnabled(true);
        //mButton1.setText(R.string.start);
    }

    @Override
    public void onBitrateUpdate(long bitrate) {
        Log.d(TAG, "Bitrate: " + bitrate);
    }

    @Override
    public void onSessionError(int message, int streamType, Exception e) {
        // mButton1.setEnabled(true);
        if (e != null) {
            // logError(e.getMessage());
        }
    }

    @Override

    public void onPreviewStarted() {
        Log.d(TAG, "Preview started.");
    }
}

/*
 * Original class created by Android Studio - can be reused later

public class CameraActivity extends AppCompatActivity {

    // testing
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_camera, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
*/
