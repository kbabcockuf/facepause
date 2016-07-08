package com.babcock.facepause;


import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.FaceDetector;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Activity to display a fullscreen video (portrait or landscape). The video can be paused/played using the
 * on screen pause button, but only when a face is detected using the front-facing camera. If a face is detected,
 * the video will automatically play, and if the camera cannot find a face for 750ms, the video will be automatically
 * paused until a face is found again. To increase accuracy, face detection uses both the hardware detection (fast but not as accurate),
 * and the software detection (slower, but more accurate). For the video to pause, both these methods must fail to find a face
 * for 750ms
 */
public class FullScreenVideoActivity extends Activity {

    //General
    private static final String TAG = "FullScreenVideoActivity";
    private static final boolean D  = true;
    private static final int FACE_DELAY_MS = 750;
    private Activity mActivity;

    //UI Elements
    private Button  mPauseBtn;
    private Button  mPlayBtn;
    private SeekBar mProgressSeekBar;

    //Video
    private VideoView   mVideoView;
    private ImageView   mVideoImageView;
    private int         mCurrentVideoPosition;
    private int         mMaxPosition;

    //Face Detection
    private Camera      mCamera;
    private TextureView mCameraPreviewTextureView;
    private Timer       mFaceTimer;
    private Timer       mSoftFaceTimer;
    private Bitmap      mSoftFaceBitmap;
    private Object      mSoftFaceSync = new Object();
    private View        mFaceIndicator;

    //Flags
    private boolean isUserPaused = false; //Check if user paused vs automatically paused

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_video);

        initUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        setupCameraPreview();
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseVideo();
    }

    @Override
    public void onBackPressed() {
        Intent returnIntent = new Intent();
        setResult(RESULT_CANCELED, returnIntent);
        finish();
    }

    /**
     * Initializes UI elements, sets up the custom seekbar, and sets listeners
     */
    private void initUi() {

        mActivity = this;

        mPauseBtn        = (Button)  findViewById(R.id.btn_pause);
        mPlayBtn         = (Button)  findViewById(R.id.btn_play);
        mProgressSeekBar = (SeekBar) findViewById(R.id.seekbar_video_progress);

        mVideoView          = (VideoView)   findViewById(R.id.video_view);
        mVideoImageView     = (ImageView)   findViewById(R.id.iv_video);

        mPlayBtn.setVisibility(View.VISIBLE);
        mProgressSeekBar.setEnabled(false);
        mCurrentVideoPosition = 0;

        //Setup SeekBar
        final Handler seekBarHandler = new Handler();
        Runnable seekBarRunnable = new Runnable() {
            @Override
            public void run() {
                if(mVideoView!=null) {
                    if(mVideoView.isPlaying()) {
                        if(mCurrentVideoPosition < mVideoView.getDuration()) { //Video is still playing
                            mCurrentVideoPosition = mVideoView.getCurrentPosition();
                            mMaxPosition = mVideoView.getDuration();
                            mProgressSeekBar.setProgress(mVideoView.getCurrentPosition() / 1000);
                            mProgressSeekBar.setMax(mVideoView.getDuration() / 1000);
                        }
                    }
                    else {
                        mProgressSeekBar.setProgress(mCurrentVideoPosition / 1000);
                        mProgressSeekBar.setMax(mMaxPosition / 1000);
                    }

                }
                seekBarHandler.postDelayed(this,1000);
            }
        };
        seekBarHandler.postDelayed(seekBarRunnable, 1000);

        mPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pauseVideo();
                isUserPaused = true;
            }
        });
        mPlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isUserPaused = false;
            }
        });

        mVideoView.setBackgroundColor(getResources().getColor(R.color.transparent));

        if(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
            mVideoImageView.getLayoutParams().height = 500;

        new LoadingVideoAsyncTask().execute("http://0.s3.envato.com/h264-video-previews/80fad324-9db4-11e3-bf3d-0050569255a8/490527.mp4");
    }

    /**
     * Sets up the front facing camera preview that will allow for face detection
     */
    private void setupCameraPreview() {
        mCameraPreviewTextureView = new TextureView(this);
        mCameraPreviewTextureView.setSurfaceTextureListener(mSurfaceTextureListener);

        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        if(display.getRotation() == Surface.ROTATION_0) //Portrait
            mCameraPreviewTextureView.setRotation(90.0f);
        else if(display.getRotation() == Surface.ROTATION_270) //90 Degrees Right
            mCameraPreviewTextureView.setRotation(180.0f);
        else if(display.getRotation() == Surface.ROTATION_90) //90 Degrees Left
            mCameraPreviewTextureView.setRotation(0.0f);

        RelativeLayout.LayoutParams params =
                new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
        mCameraPreviewTextureView.setLayoutParams(params);


        addContentView(mCameraPreviewTextureView, new ViewGroup.LayoutParams(240, 170));

        // Now create the OverlayView:
        mFaceIndicator = new View(this);
        mFaceIndicator.setAlpha(0.5f);
        mFaceIndicator.setBackgroundColor(Color.RED);
        addContentView(mFaceIndicator, new ViewGroup.LayoutParams(40, 40));
    }

    /**
     * Begins playing the current video. This can be triggered by face detection so as to start and stop the
     * video automatically. If the user has manually paused the video, it sets a user paused flag that
     * will stop the video from being automatically played until the user has pressed play
     */
    public void playVideo() {
        if(!isUserPaused) {
            if (mVideoImageView.getAlpha() != 0f)
                mVideoImageView.setAlpha(0f);
            mPlayBtn.setVisibility(View.INVISIBLE);
            mPauseBtn.setVisibility(View.VISIBLE);
            mProgressSeekBar.setProgress(mCurrentVideoPosition / 1000);
            mProgressSeekBar.setMax(mVideoView.getDuration() / 1000);
            mVideoView.start();
        }
    }

    /**
     * Pauses the current video and saves the current position of the video. This can be triggered by
     * face detection so as to start and stop the video automatically.
     */
    public void pauseVideo() {
        mPlayBtn.setVisibility(View.VISIBLE);
        mPauseBtn.setVisibility(View.INVISIBLE);
        mVideoView.pause();
        mCurrentVideoPosition = mVideoView.getCurrentPosition();
    }

    /**
     * Async task to load the video that is hosted on a remote server
     */
    private class LoadingVideoAsyncTask extends AsyncTask<String, Uri, Void> {
        ProgressDialog dialog;

        protected void onPreExecute() {
            dialog = new ProgressDialog(mActivity);
            dialog.setMessage("Loading, Please Wait...");
            dialog.setCancelable(true);
            dialog.show();
        }

        protected void onProgressUpdate(final Uri... uri) {
            try {
                mVideoView.setVideoURI(uri[0]);
                mVideoView.requestFocus();
                mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer arg0) {
                        dialog.dismiss();
                    }
                });
                mVideoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                    }
                });
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (SecurityException e) {
                e.printStackTrace();
            }
        }

        @Override
        protected Void doInBackground(String... params) {
            try {
                Uri uri = Uri.parse(params[0]);

                publishProgress(uri);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return null;
        }

    }

    /*-------- Begin face detection methods --------*/

    /**
     * Listens to when the surface texture is available, and then begins face detection by making sure it is supported
     * by the device, and then starting the camera preview/face detection, as well as starting the face timer.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mCamera = openFrontFacingCamera();

            if (mCamera != null) {

                Camera.Parameters params = mCamera.getParameters();

                if(params.getMaxNumDetectedFaces() == 0) {
                    Toast.makeText(getApplicationContext(),"Unsupported Device (No Face Detection)",Toast.LENGTH_LONG).show();
                    Intent returnIntent = new Intent();
                    setResult(RESULT_CANCELED, returnIntent);
                    FullScreenVideoActivity.this.finish();
                }
                else {

                    try {
                        mCamera.setPreviewTexture(surface);
                    } catch (IOException e) {
                        Log.e(TAG, "Could not preview image", e);
                    }

                    mCamera.startPreview();

                    mCamera.setFaceDetectionListener(mFaceDetectionListener);
                    mCamera.startFaceDetection();

                    mCamera.setPreviewCallback(mPreviewCallback);

                    startFaceTimer();
                }
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // ignore
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            if (mCamera != null) {
                mCamera.setPreviewCallback(null);
                mCamera.setFaceDetectionListener(null);
                mCamera.release();
                mCamera = null;
            }

            if (mFaceTimer != null) {
                mFaceTimer.cancel();
                mFaceTimer = null;
            }

            if (mSoftFaceTimer != null) {
                mSoftFaceTimer.cancel();
                mSoftFaceTimer = null;
            }

            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    /**
     * Face detection listener for the camera hardware. overrides onFaceDetection to call handleFaceDetection depending on
     * whether or not faces were found in the camera preview
     */
    private Camera.FaceDetectionListener mFaceDetectionListener = new Camera.FaceDetectionListener() {
        @Override
        public void onFaceDetection(Camera.Face[] faces, Camera camera) {
            boolean hasFaces = faces != null && faces.length > 0;
            handleFaceDetection(hasFaces);
        }
    };

    /**
     * Opens the front facing camera
     *
     * @return the front facing camera that has been opened
     */
    private Camera openFrontFacingCamera() {
        int cameraCount = 0;
        Camera cam = null;
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        cameraCount = Camera.getNumberOfCameras();
        for (int camIdx = 0; camIdx < cameraCount; camIdx++) {
            Camera.getCameraInfo(camIdx, cameraInfo);
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                try {
                    cam = Camera.open(camIdx);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Camera failed to open: " + e.getLocalizedMessage());
                }
            }
        }

        return cam;
    }

    /**
     * Callback for the camera preview. This is for the software face detection. Takes the camera bitmap,
     * converts it to rgb 366, and set the software face bitmap to be analyzed
     *
     */
    private Camera.PreviewCallback mPreviewCallback = new Camera.PreviewCallback() {

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            Bitmap bmp = convertBitmapTo565(mCameraPreviewTextureView.getBitmap());
            synchronized (mSoftFaceSync) {
                mSoftFaceBitmap = bmp;
            }
        }
    };


    /**
     * Starts the face timer. Every one second, takes the bitmap from the camera preview, and searches for faces
     * using the software face detection. This then calls handleFaceDetection, and passes whether or not faces were found
     * as a parameter
     */
    private void startFaceTimer() {
        mSoftFaceTimer = new Timer();

        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                synchronized (mSoftFaceSync) {
                    if (mSoftFaceBitmap != null) {
                        FaceDetector myFaceDetect = new FaceDetector(mSoftFaceBitmap.getWidth(), mSoftFaceBitmap.getHeight(), 1);
                        int numberOfFacesDetected = myFaceDetect.findFaces(mSoftFaceBitmap, new FaceDetector.Face[1]);

                        handleFaceDetection(numberOfFacesDetected > 0);

                        mSoftFaceBitmap = null;
                    }
                }
            }
        };

        mSoftFaceTimer.schedule(timerTask, 0, 1);
    }

    /**
     * Handles face detection given the existence of faces as a parameter. If false is passed in, begins
     * a timer that, after FACE_DELAY_MS (750) milliseconds, pauses the video. However, if this method is called again
     * before the timer fires, and true is passed as a parameter, the timer is then canceled. This gets rid of
     * false negatives by ensuring all attempts to find a face in the given time frame yielded false
     *
     * @param hasFaces true if faces were detected
     */
    private void handleFaceDetection(boolean hasFaces) {
        if (hasFaces) {
            if (mFaceTimer != null) {
                mFaceTimer.cancel();
                mFaceTimer = null;
            }

            runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    if (!mVideoView.isPlaying())
                        playVideo();

                    mFaceIndicator.setBackgroundColor(Color.GREEN);
                }
            });

        }
        else if (mFaceTimer == null) {
            mFaceTimer = new Timer();
            mFaceTimer.schedule(new TimerTask() {

                @Override
                public void run() {
                    runOnUiThread(new Runnable() {

                        @Override
                        public void run() {
                            pauseVideo();

                            mFaceIndicator.setBackgroundColor(Color.RED);
                        }
                    });
                }

            }, FACE_DELAY_MS);
        }
    }


    /**
     * Utility method to convert bitmaps color space to RGB 565 (needed for using the camera preview for software face detection)
     * @param bitmap bitmap to convert
     * @return converted bitmap with new color space
     */
    private Bitmap convertBitmapTo565(Bitmap bitmap) {
        Bitmap.Config config = Bitmap.Config.RGB_565;
        Bitmap convertedBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), config);
        Canvas canvas = new Canvas(convertedBitmap);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        canvas.drawBitmap(bitmap, 0, 0, paint);
        return convertedBitmap;
    }

}
