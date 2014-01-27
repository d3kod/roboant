/* Copyright 2011 Google Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: http://code.google.com/p/usb-serial-for-android/
 */

package uk.ac.ed.insectlab.ant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import uk.co.ed.insectlab.ant.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitors a single {@link UsbSerialDriver} instance, showing all data
 * received.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class AntControlActivity extends Activity implements CameraControl {

    private static final int CAMERA_NUMBER = 1;

    private static final long CAMERA_TIMEOUT = 1000;

    private final String TAG = AntControlActivity.class.getSimpleName();

    /**
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialDriver)}.
     *
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */
    private static UsbSerialDriver sDriver = null;

    //    private TextView mTitleTextView;
    //    private TextView mDumpTextView;
    //    private ScrollView mScrollView;


    Pattern mPattern = Pattern.compile("(l|r)\\s(-?\\d+)");

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

        @Override
        public void onRunError(Exception e) {
            Log.d(TAG, "Runner stopped.");
        }

        @Override
        public void onNewData(final byte[] data) {
            AntControlActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AntControlActivity.this.updateReceivedData(data);
                }
            });
        }
    };

    private EditText mInputField;

    private int mRightSpeed = 0;

    private int mLeftSpeed = 0;
    private EditText mLeftSpeedIndicator;
    private EditText mRightSpeedIndicator;
    private EditText mServerIP;
    private EditText mServerPort;

    private RoboAntControl mRoboAntControl;

    private Pattern mSpeedPattern = Pattern.compile("l(-?\\d+)r(-?\\d+)");

    private Handler handler;

    private Runnable mConnectorRunnable;

    private WakeLock mWakeLock;

    private SeekBar mLeftSeek;

    private SeekBar mRightSeek;

    private Camera mCamera;

    private CameraPreview mPreview;

    private Button mCaptureButton;

    private MediaRecorder mMediaRecorder;

    private long mCamMessageRecievedAt = System.currentTimeMillis();

    private TextView mAIMessage;

    private TextView mRecordingText;

    private ProgressBar mTrackProgressBar;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);
        
        mCurrentStepPic = (ImageView)findViewById(R.id.pic_current_step);
        mStepTowardsPic = (ImageView)findViewById(R.id.pic_step_towards);
        
        handler = new Handler();
        mTrackProgressBar = (ProgressBar)findViewById(R.id.track_proress);
        mTrackProgressBar.setVisibility(View.GONE);
        //        mTitleTextView = (TextView) findViewById(R.id.demoTitle);
        mInputField = (EditText) findViewById(R.id.input);
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        mInputField.setOnEditorActionListener(new OnEditorActionListener() {

            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event == null) {
                    Log.i(TAG, "Event is null " + actionId);
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    Log.i(TAG, "Event is up " + actionId);
                    //                    switch (actionId) {
                    //                        case KeyEvent.KEYCODE_ENTER:
                    mSerialIoManager.writeAsync((mInputField.getText().toString() + '\n').getBytes());
                    Log.i(TAG, "Written " + mInputField.getText());
                    //                            break;
                    //                        default:
                    //                            break;
                    //                    }
                }
                return true;
            }
        });
        Button mBtnMotorTest = (Button) findViewById(R.id.motor_test);
        mBtnMotorTest.setOnClickListener(new Button.OnClickListener() {

            @Override
            public void onClick(View v) {
                new MotorTestTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mRoboAntControl);
            }


        });

        mLeftSpeedIndicator = (EditText)findViewById(R.id.leftValue);
        mRightSpeedIndicator = (EditText)findViewById(R.id.rightValue);

        Button capture = (Button)findViewById(R.id.btn_capture);
        capture.setOnClickListener(new Button.OnClickListener() {

            @Override
            public void onClick(View v) {
                if (mCamera != null) {
                    if (mAIControl != null) {
                        takeAIPicture();
                    }
                    else {
                         PictureCallback callback = new PictureCallback() {

                            @Override
                            public void onPictureTaken(byte[] data, Camera camera) {

                                File pictureFile = Util.getOutputMediaFile(Constants.MEDIA_TYPE_IMAGE);
                                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, Util.getRouteFollowingBitmapOpts());
                                mStepTowardsPic.setImageBitmap(Util.getCroppedBitmap(bmp));
                                if (pictureFile == null){
                                    Log.d(TAG, "Error creating media file, check storage permissions");
                                    return;
                                }

                                try {
                                    FileOutputStream fos = new FileOutputStream(pictureFile);
                                    fos.write(data);
                                    fos.close();
                    camera.startPreview();
                                } catch (FileNotFoundException e) {
                                    Log.d(TAG, "File not found: " + e.getMessage());
                                } catch (IOException e) {
                                    Log.d(TAG, "Error accessing file: " + e.getMessage());
                                }
                            }
                        };
                        mCamera.takePicture(null, null, callback);
                    }
                }
            }




        });
        
        SurfaceView mCameraOverlay = (CameraOverlay) findViewById(R.id.camera_overlay);
        
        
        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        this.mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "My Tag");
        this.mWakeLock.acquire();

        mAIMessage = (TextView)findViewById(R.id.ai_message);

        mLeftSeek = (SeekBar)findViewById(R.id.left);
        mRightSeek = (SeekBar)findViewById(R.id.right);
        OnSeekBarChangeListener seeker = new OnSeekBarChangeListener() {

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // TODO Auto-generated method stub

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // TODO Auto-generated method stub

            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,
                    boolean fromUser) {
                switch (seekBar.getId()) {
                    case R.id.left:
                        mRoboAntControl.setLeftSpeed(progress);
                        Log.i(TAG, "Setting left speed to " + progress);
                        break;
                    case R.id.right:
                        mRoboAntControl.setRightSpeed(progress);
                        Log.i(TAG, "Setting right speed to " + progress);
                        break;

                    default:
                        break;
                }

            }


        };

        mRecordingText = (TextView)findViewById(R.id.path_recording);
        mRecordingText.setVisibility(View.GONE);

        mLeftSeek.setOnSeekBarChangeListener(seeker);
        mRightSeek.setOnSeekBarChangeListener(seeker);

        mServerIP = (EditText)findViewById(R.id.serverIP);
        mServerPort = (EditText)findViewById(R.id.serverPort);

        mServerIP.setText("192.168.1.2");
        mServerPort.setText("1234");

        postConnectorRunnable();

        if (Util.checkCameraHardware(this)) {
            Toast.makeText(this, "Initializing camera", Toast.LENGTH_SHORT).show();
            mCamera = Util.getCameraInstance(CAMERA_NUMBER);
            Log.i(TAG, "Supported picture formats " + Arrays.toString(mCamera.getParameters().getSupportedPictureFormats().toArray()));
            if (mCamera == null) {
                Log.i(TAG, "Could not get hold of camera");
                Toast.makeText(this, "Could not get hold of camera", Toast.LENGTH_LONG).show();
            }
            else {
                Util.setCameraDisplayOrientation(this, CAMERA_NUMBER, mCamera);
                // Create our Preview view and set it as the content of our activity.
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = getResources().getDisplayMetrics().heightPixels;
                mCamera.getParameters().setPreviewSize(width, height);
                mPreview = new CameraPreview(this, mCamera);
                FrameLayout preview = (FrameLayout) findViewById(R.id.camera_preview);
                preview.addView(mPreview);



                // Add a listener to the Capture button
                mCaptureButton = (Button) findViewById(R.id.btn_record);
                mCaptureButton.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                toggleCameraRecording();
                            }
                        }
                        );
            }
        }

    }

    private void takeAIPicture() {
        PictureCallback pictureStepTowards = new PictureCallback() {

            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                Log.i(TAG, "Picture for AIControl taken");
                mAIControl.stepTowards(data); 
                ImageView stepTowards = (ImageView)findViewById(R.id.pic_step_towards);
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, Util.getRouteFollowingBitmapOpts());
                stepTowards.setImageBitmap(bmp);
                delayedStartPreview();
            }
        };
        try {
            mCamera.takePicture(null, null, pictureStepTowards);
        }
        catch (Exception e) {
            e.printStackTrace();
            handler.postDelayed(new Runnable() {
                
                @Override
                public void run() {
                    takeAIPicture();
                }
            }, 100);
        }

    }

    void delayedStartPreview() {
        handler.postDelayed(new Runnable() {

            @Override
            public void run() {
                mCamera.startPreview(); 
            }
        }, 100);
    }

    private void toggleCameraRecording() {
        if (isRecording) {
            // stop recording and release camera
            mMediaRecorder.stop();  // stop the recording
            releaseMediaRecorder(); // release the MediaRecorder object
            mCamera.lock();         // take camera access back from MediaRecorder

            // inform the user that recording has stopped
            mCaptureButton.setText("Capture");
            isRecording = false;
        } else {
            // initialize video camera
            if (prepareVideoRecorder()) {
                // Camera is available and unlocked, MediaRecorder is prepared,
                // now you can start recording
                mMediaRecorder.start();

                // inform the user that recording has started
                mCaptureButton.setText("Stop");
                isRecording = true;
            } else {
                // prepare didn't work, release the camera
                releaseMediaRecorder();
                // inform user
            }
        }

    }

    private boolean prepareVideoRecorder() {

        //      mCamera = getCameraInstance();
        mMediaRecorder = new MediaRecorder();

        // Step 1: Unlock and set camera to MediaRecorder
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);

        // Step 2: Set sources
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        // Step 3: Set a CamcorderProfile (requires API Level 8 or higher)
        mMediaRecorder.setProfile(CamcorderProfile.get(CAMERA_NUMBER, CamcorderProfile.QUALITY_HIGH));
        //      mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);
        //      mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT);

        // Step 4: Set output file
        mMediaRecorder.setOutputFile(Util.getOutputMediaFile(Constants.MEDIA_TYPE_VIDEO).toString());
        mMediaRecorder.setVideoSize(1280, 960); 
        // Step 5: Set the preview output
        mMediaRecorder.setPreviewDisplay(mPreview.getHolder().getSurface());

        // Step 6: Prepare configured MediaRecorder
        try {
            mMediaRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d(TAG, "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d(TAG, "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    private boolean isRecording = false;


    private void releaseMediaRecorder(){
        if (mMediaRecorder != null) {
            mMediaRecorder.reset();   // clear recorder configuration
            mMediaRecorder.release(); // release the recorder object
            mMediaRecorder = null;
            mCamera.lock();           // lock camera for later use
        }
    }

    private void releaseCamera(){
        if (mCamera != null){
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }



    private TcpClient mTcpClient;

    private AIControlTask mAIControl;

    private String messageBuffer;

    private Pattern mLookAroundPattern = Pattern.compile("la\\s(g|d|\\d+)");

    private boolean mRecordingRoute;

    private ImageView mCurrentStepPic;

    private ImageView mStepTowardsPic;


    public class ConnectTask extends AsyncTask<Void, String, TcpClient> {

        @Override
        protected TcpClient doInBackground(Void... nothing) {
            Log.i(TAG, "Starting a ConnectTask");

            if (mTcpClient != null) {
                return null;
            }
            //we create a TCPClient object and
            mTcpClient = new TcpClient(new TcpClient.OnMessageReceived() {

                @Override
                //here the messageReceived method is implemented
                public void messageReceived(String message) {
                    //this method calls the onProgressUpdate
                    publishProgress(message);
                }

                @Override
                public void disconnected() {
                    Log.i(TAG, "Disconnected");
                    mTcpClient = null;

                    AntControlActivity.this.runOnUiThread(new Runnable() {

                        @Override
                        public void run() {
                            mServerIP.setEnabled(true);
                            mServerPort.setEnabled(true);
                        }
                    });

                    postConnectorRunnable();
                }



                @Override
                public void connected() {
                    Log.i(TAG, "Connected");
                    AntControlActivity.this.runOnUiThread(new Runnable() {

                        @Override
                        public void run() {
                            mServerIP.setEnabled(false);
                            mServerPort.setEnabled(false);
                        }
                    });
                    handler.removeCallbacks(mConnectorRunnable);
                }
            });

            mTcpClient.run(mServerIP.getText().toString(), Integer.parseInt(mServerPort.getText().toString()));

            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            handleMessage(values[0]);
        }

    }

    private void postConnectorRunnable() {
        final int period = 2000;

        mConnectorRunnable = new Runnable() {

            public void run() {
                if (mTcpClient != null) {
                    return;
                }
                Log.i(TAG, "Trying to connect to server");
                new ConnectTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                handler.postDelayed(this, period);
            }
        };

        handler.postDelayed(mConnectorRunnable, period);
    }

    private void handleMessage(String message) {
        //        Log.i(TAG, "Message received " + message);
        Matcher matcher = mPattern.matcher(message);
        if (matcher.find()) {
            int speed = Integer.parseInt(matcher.group(2));
            //            Log.i(TAG, "Pattern found! Speed is " + speed);
            if (mAIControl != null) {
                //                Log.i(TAG, "AI is in control!");
                return;
            }
            if (mRoboAntControl == null) {
                Log.i(TAG, "mRoboAntControl is null message is " + message);
                return;
            }
            if (matcher.group(1).equals("l")) {
                mRoboAntControl.setLeftSpeed(speed);
            }
            else {
                mRoboAntControl.setRightSpeed(speed);
            }
        }
        else if (message.startsWith("cam")) {
            Log.i(TAG, "cam message!");
            if (mCamMessageRecievedAt + CAMERA_TIMEOUT > System.currentTimeMillis()) {
                Log.i(TAG, "ignoring cam message");
                return;
            }
            mCamMessageRecievedAt = System.currentTimeMillis();
            toggleCameraRecording();
        }
        else if (message.startsWith("ai")) {
            Log.i(TAG, "ai message!");
            if (mAIControl != null) {
                mAIControl.stop();
                mAIMessage.setText("");
                mAIControl = null;
            }
            else {
                TextView currentStepNum = (TextView)findViewById(R.id.current_step_num);
                TextView stepTowardsNum = (TextView)findViewById(R.id.step_towards_num);
                mAIControl = new AIControlTask(this, mAIMessage, mCurrentStepPic, mStepTowardsPic, currentStepNum, stepTowardsNum, mTrackProgressBar, mRoutePictures);
                mAIControl.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mRoboAntControl);

//                if (!mRoutePictures.isEmpty()) {
//                    mAIControl.followRoute(mRoutePictures);
//                }
            }
        }
        else if (message.startsWith("pic")) {
            Log.i(TAG, "pic message!");
            if (mAIControl != null) {
                takeAIPicture();
            }
            else {
                mCamera.takePicture(null, null, Util.mPicture);
            }
        }
        else if (message.startsWith("rec")) {
            Log.i(TAG, "rec message!");
            if (!mRecordingRoute) {
                postRecordingRunnable();
                mRecordingRoute = true;
                mRecordingText.setVisibility(View.VISIBLE);
            }
            else {
                mRecordingRoute = false;
                handler.removeCallbacks(mRecordingRunnable);
                mRecordingText.setVisibility(View.GONE);
            }
        }
        else if (message.startsWith("calibrate")) {
            Log.i(TAG, "Calibrate message");
            if (mRoboAntControl != null) {
                mRoboAntControl.calibrate();
            }
        }
    }
    
    private long ROUTE_PICTURES_DELAY = 1000;
    LinkedList<Bitmap> mRoutePictures = new LinkedList<Bitmap>();
    private Runnable mRecordingRunnable;
    private void postRecordingRunnable() {
        for (Bitmap bmp : mRoutePictures) {
            bmp.recycle();
        }
        mRoutePictures.clear();

        handler.post(new Runnable() {

            @Override
            public void run() {
                mRecordingRunnable = this;
                PictureCallback callback = new PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] data, Camera camera) {
                       mRoutePictures.add(BitmapFactory.decodeByteArray(data, 0, data.length, Util.getRouteFollowingBitmapOpts()));
                       Log.i(TAG, "Route picture taken: " + mRoutePictures.size());
                       delayedStartPreview();
                    }
                };
               try {
                   mCamera.takePicture(null, null, callback); 
                   handler.postDelayed(this, ROUTE_PICTURES_DELAY);
               }
               catch (Exception e) {
                   e.printStackTrace();
                   handler.postDelayed(this, 100);
               }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.mWakeLock.release();
        stopIoManager();
        if (sDriver != null) {
            try {
                sDriver.close();
            } catch (IOException e) {
                // Ignore.
            }
            sDriver = null;
        }
        if (mTcpClient != null) {
            mTcpClient.stopClient();
        }
        if (mConnectorRunnable != null) {
            handler.removeCallbacks(mConnectorRunnable);
        }
        releaseMediaRecorder();
        releaseCamera();
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resumed, sDriver=" + sDriver);
        if (sDriver == null) {
            //            mTitleTextView.setText("No serial device.");
            Toast.makeText(this, "No serial device", Toast.LENGTH_LONG).show();;
        } else {
            try {
                sDriver.open();
                sDriver.setParameters(115200, 8, UsbSerialDriver.STOPBITS_1, UsbSerialDriver.PARITY_NONE);
            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                //                mTitleTextView.setText("Error opening device: " + e.getMessage());
                Toast.makeText(this, "Error opening device", Toast.LENGTH_LONG).show();;
                try {
                    sDriver.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sDriver = null;
                return;
            }
            //            mTitleTextView.setText("Serial device: " + sDriver.getClass().getSimpleName());
            Toast.makeText(this, "Serial device " + sDriver.getClass().getSimpleName(), Toast.LENGTH_LONG).show();;
        }
        onDeviceStateChange();
        postConnectorRunnable();
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
            mRoboAntControl = null;
        }
    }

    private void startIoManager() {
        if (sDriver != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sDriver, mListener);
            mExecutor.submit(mSerialIoManager);
            mRoboAntControl = new RoboAntControl(mSerialIoManager);
//            mAIControl = new AIControlTask(this, mAIMessage);
//            mAIControl.execute(mRoboAntControl);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    private void updateReceivedData(byte[] data) {
        String message = new String(data);
        if (messageBuffer != null) {
            message = messageBuffer + message;
        }
        Log.i(TAG, "Message from serial: " + message);
        Matcher matcher = mSpeedPattern.matcher(message);
        Matcher laMatcher = mLookAroundPattern.matcher(message);
        if (laMatcher.find()) {
            Log.i(TAG, "Look around message received!");
            if (laMatcher.group(1).equals("d")) {
                Log.i(TAG, "Look around done!");
                mRoboAntControl.lookAroundDone();
            }
            else if (laMatcher.group(1).equals("g")) {
                Log.i(TAG, "Go towards done!");
                mRoboAntControl.goTowardsDone();
            }
            else {
                Log.i(TAG, "Look around step " + laMatcher.group(1));
                mRoboAntControl.lookAroundStepDone(Integer.parseInt(laMatcher.group(1)));
            }
            messageBuffer = null;
        }
        else if (matcher.find()) {
            mLeftSpeedIndicator.setText(matcher.group(1));
            mRightSpeedIndicator.setText(matcher.group(2));
            mLeftSeek.setProgress(Integer.parseInt(matcher.group(1)));
            mRightSeek.setProgress(Integer.parseInt(matcher.group(2)));
            messageBuffer = null;
        }
        else {
            messageBuffer = message;
        }
    }

    /**
     * Starts the activity, using the supplied driver instance.
     *
     * @param context
     * @param driver
     */
    static void show(Context context, UsbSerialDriver driver) {
        sDriver = driver;
        final Intent intent = new Intent(context, AntControlActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }

    @Override
    protected void onStop() {

        super.onStop();
    }

    @Override
    public void takePicture(final CameraReceiver receiver) {
        final PictureCallback sendToReceiver = new PictureCallback() {

            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                receiver.receivePicture(data); 
                //                camera.startPreview();
                delayedStartPreview();
            }
        };
        //        handler.post(new Runnable() {

        //            @Override
        //            public void run() {
        try {
            mCamera.takePicture(null, null, sendToReceiver);
        }
        catch (NullPointerException e) {
            e.printStackTrace();
            releaseCamera();
            mCamera = Util.getCameraInstance(CAMERA_NUMBER);
            handler.postDelayed(new Runnable() {
                
                @Override
                public void run() {
                    takePicture(receiver);
                }
            }, 100);
        }
        catch (Exception e) {
            e.printStackTrace();
            handler.postDelayed(new Runnable() {
                
                @Override
                public void run() {
                    takePicture(receiver);
                }
            }, 100);
        }
        //            }
        //        });
    }



    @Override
    public void givePictureToReceiver() {
        // TODO Auto-generated method stub

    }

}
