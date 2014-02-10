package uk.ac.ed.insectlab.ant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.provider.Settings.Global;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

public class AIControlTask extends AsyncTask<ArduinoZumoControl, String, Void> implements LookAroundListener {


	protected static final String TAG = "AIControlTask";

	private static final int WITHIN_BEST_TO_STOP = 5;
	private static final int PICTURES_WINDOW = 20;

	private static final int TURN_STEP = 20;
	private static final int TURN_FOR = 7;

	private static final int TURN_SPEED = 100;

	private static final boolean AWAIT_CONFIRM = false;


	double OPTIC_FLOW_TURN = 10/360.;

	private ArduinoZumoControl mRoboAntControl;

	LinkedList<byte[]> mReceivedPictures = new LinkedList<byte[]>();

	String lock = "lock";

	private OpenCVCamera mCameraControl;

	//	private byte[] mCompareTo;

	private TextView mMessageView;

	private int mLookAroundStep;

	private boolean mLookAroundDone;

	private boolean mFollowingRoute;

	private List<Bitmap> mRoutePictures;

	private Bitmap mCompareToBmp;

	private boolean mStop;

	private ImageView mCurrentStepPic;

	private Bitmap mPicToPublish;

	private ImageView mGoTowardsPic;

	private Bitmap mPicToPublishGoTowards;

	private ProgressBar mProgressBar;

	private int mBestImageNum;

	private TextView mCurrentStepNum;

	private TextView mGoTowardsNum;

	private int mCurrentStepToPublish;

	private int mGoTowardsToPublish;

	private HashSet<Point> mPixelsToCheck;

	private NetworkControl mNetworkControl;

	private boolean mSSDCalibrated;

	private double mSSDMin;

	private double mSSDMax;


	public AIControlTask(OpenCVCamera camControl, NetworkControl networkControl, TextView messageView, ImageView currentStepPic, ImageView goTowardsPic, TextView currentStepNum, TextView goTowardsNum, ProgressBar progressBar) {
		this(camControl, networkControl, messageView, currentStepPic, goTowardsPic, currentStepNum, goTowardsNum, progressBar, new LinkedList<Bitmap>());
	}

	public AIControlTask(OpenCVCamera camControl, NetworkControl networkControl, TextView messageView, ImageView currentStepPic, ImageView goTowardsPic, TextView currentStepNum, TextView goTowardsNum, ProgressBar progressBar, List<Bitmap> routePictures) {
		Log.i(TAG, "constructor");
		mCurrentStepNum = currentStepNum;
		mGoTowardsNum = goTowardsNum;
		mProgressBar = progressBar;
		mCurrentStepPic = currentStepPic;
		mGoTowardsPic = goTowardsPic;
		mCameraControl = camControl;
		mMessageView = messageView;
		mRoutePictures = routePictures;
		mNetworkControl = networkControl;
        mSSDCalibrated = GLOBAL.getSettings().getSSDCalibrated();
		if (mSSDCalibrated) {
                mSSDMin = GLOBAL.getSettings().getSSDMin();
                mSSDMax = GLOBAL.getSettings().getSSDMax();
		}

		if (!mRoutePictures.isEmpty()) {
			mFollowingRoute = true;
		}
	}

	public void setNetworkControl(NetworkControl control) {
		mNetworkControl = control;
	}


	@Override
	protected void onPreExecute() {
		Log.i(TAG, "onPreExecute");
		mMessageView.setText("AI ON");
		if (mFollowingRoute) {
			mProgressBar.setVisibility(View.VISIBLE);
			mProgressBar.setMax(mRoutePictures.size());
		}
		super.onPreExecute();
	}

	private class TurnStep {

		private Bitmap bmp;
		private int deg;

		public TurnStep(Bitmap bmp, int deg) {
			this.bmp = bmp; this.deg = deg;
		}
	}

	double mTotalFlow;

	private HandlerThread mHandlerThread;

	private Handler mHandler;


	@Override
	protected Void doInBackground(ArduinoZumoControl... params) {
		Log.i(TAG, "doInBackground");

		mHandlerThread = new HandlerThread("ai_control_handler");
		mHandlerThread.start();

		while(!mHandlerThread.isAlive()) {};  
		mHandler = new Handler(mHandlerThread.getLooper(), new Handler.Callback() {

			@Override
			public boolean handleMessage(Message msg) {
				switch (msg.arg1) {
				case OpenCVCamera.MSG_PICTURE:
					mTakePictureBuffer = (Bitmap)msg.obj;

					//					releaseLock();
					return true;
				}
				return false;
			}
		});

		mRoboAntControl = params[0];

		while (true) {
			if (mFollowingRoute) {
				if (mStop) {
					return null;
				}
				swayingHoming(mRoutePictures);
//				int counter = 0;
//				int at = 0;
//				while (at + WITHIN_BEST_TO_STOP < mRoutePictures.size()) {
//					if (mStop) {
//						break;
//					}
//					Log.i(TAG, "Following Route loop " + counter++);
//					//					ArrayList<TurnStep> turnsteps = lookAround();
//					//					ArrayList<TurnStep> turnsteps = lookAroundFast();
//					//					moveTowardsMin(turnsteps, mRoutePictures);
//					//					moveTowardsMin(turnsteps, moveTowards);(mRoutePictures.subList(at, 10));
//					int from = at-PICTURES_WINDOW/2;
//					int to = at+PICTURES_WINDOW/2;
//					if (from < 0) {
//						from = 0;
//					}
//					if (to > mRoutePictures.size()) {
//						to = mRoutePictures.size()-1;
//					}
//					int atIncr = doTurnUntilSlope(mRoutePictures.subList(from, to));
//					at = from + atIncr;
//					moveForward(100, 200);
//					Log.i(TAG, "At " + at + " " + from + " " + to + " " + atIncr + " Within " + (mRoutePictures.size() - at) + " of end");
//				}
//				Log.i(TAG, "Follow route finished");
//				publishProgress("Follow route finished");
//				mFollowingRoute = false;
			}
			else {
				Log.i(TAG, "Wait lock");
				waitLock();
				Log.i(TAG, "Wait done");

				if (mStop) return null;

				//				mNetworkControl.sendPicture(new RoboPicture(mCompareToBmp, PictureType.GoTowards, 0));

				//				ArrayList<TurnStep> turnsteps = lookAround();
				//				ArrayList<TurnStep> turnsteps = lookAroundFast();

				List<Bitmap> bitmap = new ArrayList<Bitmap>();
				bitmap.add(mCompareToBmp);

				//				moveTowardsMin(turnsteps, bitmap);
				//				moveTowardsMinRealtime(bitmap);
				//				doTurnUntilSlope(bitmap, 0);
				//				doTurnSA(Util.rotateBitmap(mCompareToBmp, 45));

				waitLock();

				//				doTurnSA(mCompareToBmp);
				doTurnUntilSlope(bitmap);
			}
		}
	}
	
	private void swayingHoming(List<Bitmap> routePics) {
		int dir = 1; //right
		double minDist;
		double thisDist;
		int rotateSpeed;
		
		int speedAdj = 200;
		
		Bitmap thisPicture;
		
//		if (lookahead <= 0 && lookahead > routePics.size()) {
//			lookahead = routePics.size();
//		}
//		
		while (true) {
			if (mStop) {
				break;
			}
			
			thisPicture = takePicture();
			
			minDist = Double.MAX_VALUE;
			
			for (int i = 0; i < routePics.size(); ++i) {
                thisDist = imagesSSD(routePics.get(i), thisPicture);
                if (thisDist < minDist) {
                	minDist = thisDist;
                	Log.i(TAG, "loop min is " + minDist + " at " + i);
                }
			}
           	Log.i(TAG, "final min is " + minDist);
			
			rotateSpeed = (int)(speedAdj * minDist);

           	Log.i(TAG, "rotateSpeed is " + rotateSpeed);
			
			mRoboAntControl.simpleTurnInPlaceBlocking(dir*rotateSpeed, 300);
			
			dir = -dir;

			moveForward(80, 200);
		}
	}
	

	private int doTurnUntilSlope(List<Bitmap> orientPics) {
		long start = System.currentTimeMillis();
		Bitmap picture = takePicture();

		int n = orientPics.size(), i;
		double[] curDif = new double[n];

		for (i = 0; i < n; ++i) {
			curDif[i] = imagesSSD(picture, orientPics.get(i));
		}

		int dir = Math.random() >= 0.5 ? 1 : -1;

		picture.recycle();

		int turnSpeed = 80; 
		int deg = 0;

		double[] thisMean = new double[n];
		double[] prevMean = new double[n];
		double minMean = Util.minInArrray(curDif);

		for (i = 0; i < n; ++i) {
			mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(deg, i, curDif[i]));
		}

		int smooth_by = 20;
		DescriptiveStatistics[] stats = new DescriptiveStatistics[n];
		for (i = 0; i < n; ++i) {
			stats[i] = new DescriptiveStatistics();
			stats[i].setWindowSize(smooth_by);
			stats[i].addValue(curDif[i]);
			thisMean[i] = curDif[i];
			prevMean[i] = thisMean[i];
		}

		SimpleRegression regr = new SimpleRegression();
		int count = 0;

		int messageCount = 0;
		int sendMessageEvery = 1;

		double slope;

		mRoboAntControl.setSpeeds(dir*turnSpeed, -dir*turnSpeed);

		double minSlopeDirChange = 20000;
		int speed = Integer.MAX_VALUE;
		int prevSpeed = 0;

		int regrNum = smooth_by;

		double prevSlope = 0;

		int lastDirChange = 0;

		int dirChangeTimeout = 5;		

		Log.i(TAG, "Time: initialization " + (System.currentTimeMillis() - start));

		double[] thisSlopes = new double[n];
		double[] prevSlopes = new double[n];
		
		int usingSlopeAt = 0;

		int beenhere = 0;
		int maxTimesHere = 10;
		
		boolean toBreak;
		boolean dirChanged; 
		
		boolean[] dipFound = new boolean[n];
		int[] dipFoundFor = new int[n];
		int dipFoundThresh = 2;
		int meanThresh = 4000000;
		while (true) {
			start = System.currentTimeMillis();
			if (mStop) {
				break;
			}
			toBreak = false;
			dirChanged = false;
			deg += dir;
			count++;

			picture = takePicture();

			for (i = 0; i < n; ++i) {
				prevMean[i] = thisMean[i];
				curDif[i] = imagesSSD(picture, orientPics.get(i));
				stats[i].addValue(curDif[i]);
				thisMean[i] = stats[i].getMean();
//
//				if (thisMean[i] < minMean) {
//					minMean = thisMean[i];
//				}

				//get last regrNum elements from stats
				int statsN = (int)stats[i].getN();
				int t = statsN > regrNum? regrNum : statsN;
				regr.clear();
				for (int j = 0; j < t; ++j) {
					regr.addData(j, stats[i].getElement(statsN - t + j));
				}

				thisSlopes[i] = regr.getSlope();
				slope = thisSlopes[i];
				prevSlope = prevSlopes[i];
				Log.i(TAG, "Slope " + slope + " " + usingSlopeAt);

				if (!dirChanged && Math.abs(slope) > minSlopeDirChange && lastDirChange > dirChangeTimeout) {
					Log.i(TAG, "Change dir timeout " + dirChangeTimeout);
					lastDirChange = 0;
					dir = -dir;
					dirChangeTimeout *= 2;
					prevSpeed = 0;
					dirChanged = true;
				}
//				else {
//					lastDirChange++;
//					if (lastDirChange > 2*dirChangeTimeout) {
//						dirChangeTimeout /= 2;
//					}
//				}

				if (count % sendMessageEvery == 0) {
//					for (i = 0; i < n; ++i) {
						mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(messageCount, i, thisMean[i]));
//					}
					mNetworkControl.sendMessage(Util.newLookAroundSkewnessMessage(messageCount, i, slope));
				}

				Log.i(TAG, "Slope " + slope);

				if (slope > 0 && prevSlope < 0) {
					// we are in a minimum
					dipFound[i] = true;
					dipFoundFor[i] = 0;
					beenhere++;
					Log.i(TAG, "Peak - Break? " + beenhere + " meanThresh " + thisMean[i]);
					if (thisMean[i] < meanThresh) {
						toBreak = true;
						break;
					}
					else {
						meanThresh += 1000000;
					}
//					if (speed <= 70 || beenhere > maxTimesHere) {
//						Log.i(TAG, "Break");
//						toBreak = true;
//						break;
//					}
				}
				
//				else if (dipFound[i] && slope > 0) {
//					dipFoundFor[i]++;
//					Log.i(TAG, "dipFoundFor " + dipFoundFor[i] + " " + i);
//					if (dipFoundFor[i] >= dipFoundThresh) {
//						toBreak = true;
//						break;
//					}
//				}
				
				if (slope < 0) {
					dipFound[i] = false;
				}

				
				prevSlopes[i] = slope;
			}

			if (toBreak) {
				usingSlopeAt = i;
				break;
			}
			
			if (!dirChanged) {
				lastDirChange++;
			}
			
			slope = Double.MAX_VALUE;
			
			for (i = 0; i < n; ++i) {
				if (thisSlopes[i] < slope) {
					slope = thisSlopes[i];
					usingSlopeAt = i;
				}
			}
			
			if (slope < 0) {
				speed = (int)((turnSpeed - 15 * (1 - thisMean[usingSlopeAt]/10000000.)));
				Log.i(TAG, "Speed is " + speed);
			}
			else {
				speed = turnSpeed;
			}
//			if (prevSpeed != speed) {
				mRoboAntControl.setSpeeds(dir*speed, -dir*speed);
//				prevSpeed = speed;
//			}

			if (count % sendMessageEvery == 0) {
				messageCount ++;
			}
			
			
			//			Log.i(TAG, "means " + thisMean[0] + " " + prevMean[0] + " " + minMean[0]);
			picture.recycle();
			Log.i(TAG, "Time: full loop " + (System.currentTimeMillis() - start));
		}
		mRoboAntControl.setSpeeds(0, 0);

		return usingSlopeAt;
	}


	private void moveForward(int speed, int time) {
		mRoboAntControl.setSpeeds(speed, speed);
		try {
			Thread.sleep(time);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		mRoboAntControl.setSpeeds(0, 0);
	}

	@Override
	protected void onPostExecute(Void result) {
		Log.i(TAG, "onPostExecute");
		super.onPostExecute(result);
	}

	private ArrayList<TurnStep> lookAroundFast() {
		ArrayList<TurnStep> steps = new ArrayList<AIControlTask.TurnStep>();
		Bitmap bmp = takePicture();
		steps.add(new TurnStep(bmp, 0));
		//		mNetworkControl.sendPicture(new RoboPicture(bmp, PictureType.LookAround, 0));
		int rotate_step = 1;
		int rotate_until = 20;
		for (int rotate = rotate_step; rotate < rotate_until; rotate += rotate_step) {
			Bitmap bmpRot = Util.rotateBitmap(bmp, rotate);
			steps.add(new TurnStep(bmpRot, rotate));
			//			mNetworkControl.sendPicture(new RoboPicture(bmpRot, PictureType.LookAround, rotate));
			//			try {
			//				Thread.sleep(500);
			//			} catch (InterruptedException e) {
			//				// TODO Auto-generated catch block
			//				e.printStackTrace();
			//			}
		}
		for (int rotate = -rotate_step; rotate > -rotate_until; rotate -= rotate_step) {
			Bitmap bmpRot = Util.rotateBitmap(bmp, rotate);
			steps.add(new TurnStep(bmpRot, rotate));
			//			mNetworkControl.sendPicture(new RoboPicture(bmpRot, PictureType.LookAround, rotate));
			//			try {
			//				Thread.sleep(500);
			//			} catch (InterruptedException e) {
			//				// TODO Auto-generated catch block
			//				e.printStackTrace();
			//			}
		}
		Log.i(TAG, "lookAroundFast " + steps.size());
		return steps;
	}

	private ArrayList<TurnStep> lookAround() {
		Log.i(TAG, "Starting look around");
		mNetworkControl.sendMessage(NetworkControl.NEW_LOOK_AROUND);

		int dir = 1;

		ArrayList<TurnStep> turnsteps = new ArrayList<TurnStep>();

		for (int turn = 0; turn < TURN_FOR; ++turn) {
			if (turn == TURN_FOR/2) {
				dir = -1;
				//				doTurn(-(turn+2)*TURN_STEP);
				for (int i = 0; i < TURN_FOR/2; ++i) {
					doTurn(-TURN_STEP);
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				//				doTurn(-(TURN_FOR/2)*TURN_STEP);
				//				doTurn(-TURN_STEP);
				//				try {
				//					Thread.sleep(200);
				//				} catch (InterruptedException e) {
				//					e.printStackTrace();
				//				}
				//				doTurn(-TURN_STEP);
				//				try {
				//					Thread.sleep(200);
				//				} catch (InterruptedException e) {
				//					e.printStackTrace();
				//				}
				//				doTurn(-TURN_STEP);
				//				try {
				//					Thread.sleep(1000);
				//				} catch (InterruptedException e) {
				//					e.printStackTrace();
				//				}
				Bitmap bmp = takePicture();
				turnsteps.add(new TurnStep(bmp, 0));
				//				Log.i(TAG, "FLOW IS " + mCameraControl.getOpticFlow(mCompareToBmp, bmp));
				//				mNetworkControl.sendPicture(new RoboPicture(bmp, PictureType.LookAround, 0));
				continue;
			}
			int target;
			if (dir > 0 ) {
				target = (turn + 1) * TURN_STEP;
			}
			else {
				target = -(turn - TURN_FOR/2) * TURN_STEP;
			}
			Log.i(TAG, "Turn " + turn + " aimAngle " + target);

			doTurn(dir*TURN_STEP);

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			Bitmap bmp = takePicture();
			turnsteps.add(new TurnStep(bmp, target));
			//			Log.i(TAG, "FLOW IS " + mCameraControl.getOpticFlow(mCompareToBmp, bmp));
			//			mNetworkControl.sendPicture(new RoboPicture(bmp, PictureType.LookAround, target));
		}

		//		doTurn((TURN_FOR/2 +2)*TURN_STEP);
		for (int i = 0; i < TURN_FOR/2; ++i) {
			doTurn(TURN_STEP);
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		//		doTurn(TURN_STEP);
		//		try {
		//			Thread.sleep(200);
		//		} catch (InterruptedException e) {
		//			e.printStackTrace();
		//		}
		//		doTurn(TURN_STEP);
		//		try {
		//			Thread.sleep(200);
		//		} catch (InterruptedException e) {
		//			e.printStackTrace();
		//		}
		return turnsteps;
	}

	private void doTurn(final int angle) {
		Log.i(TAG, "Doing turn " + angle);
		if (angle > 0) {
			mRoboAntControl.setSpeeds(TURN_SPEED, -TURN_SPEED);
		}
		else {
			mRoboAntControl.setSpeeds(-TURN_SPEED, TURN_SPEED);
		}

		mTotalFlow = 0;

		mCameraControl.setFlowListener(new OpenCVCamera.FlowListener() {
			@Override
			public boolean flowChanged(double flow) {
				mTotalFlow += flow;
				Log.i(TAG, "mTotalFlow " + mTotalFlow + " aim is "+  Math.abs(angle * OPTIC_FLOW_TURN));
				if (mTotalFlow > Math.abs(angle * OPTIC_FLOW_TURN)) {
					mRoboAntControl.setSpeeds(0, 0);
					synchronized (lock) {
						lock.notify();
					}
					return false;
				}
				return true;
			}
		});

		waitLock();

	}

	//	private void moveTowardsMinRealtime(List<Bitmap> moveTowards) {
	//
	//		// forward 
	//		Bitmap bForward = takePicture();
	//
	//		// right 
	//		mRoboAntControl.simpleTurnInPlaceBlocking(80, 200);
	//		Bitmap bRight = takePicture();
	//
	//		// left 
	//		mRoboAntControl.simpleTurnInPlaceBlocking(-80, 400);
	//		Bitmap bLeft = takePicture();
	//
	//		double minLeft, minRight, minForward;
	//		int minPicLeft, minPicRight, minPicForward;
	//		int routePicNum = 0;
	//		minLeft = minRight = minForward = 1000000000;
	//		minPicLeft = minPicRight = minPicForward = -1;
	//
	//		for (Bitmap routeBmp : moveTowards) {
	//
	//			double ssdLeft = imagesSSD(routeBmp, bLeft);
	//			double ssdRight = imagesSSD(routeBmp, bRight);
	//			double ssdForward = imagesSSD(routeBmp, bForward);
	//
	//			if (minLeft > ssdLeft) {
	//				minLeft = ssdLeft;
	//				minPicLeft = routePicNum;
	//			}
	//
	//			if (minRight > ssdRight) {
	//				minRight = ssdRight;
	//				minPicRight = routePicNum;
	//			}
	//
	//			if (minForward > ssdForward) {
	//				minForward = ssdForward;
	//				minPicForward = routePicNum;
	//			}
	//
	//			routePicNum ++;
	//		}
	//
	//		Log.i(TAG, "mins (L, R, F)" + minLeft + " " + minRight + " " + minForward);
	//		Log.i(TAG, "routeNums (L, R, F)" + minPicLeft + " " + minPicRight + " " + minPicForward);
	//
	//		if (minLeft < minRight) {
	//			if (minLeft < minForward) {
	//				Log.i(TAG, "Turning left");
	//				doTurnUntilPic(bLeft, -1);
	//			}
	//			else {
	//				Log.i(TAG, "Going straight");
	//				doTurnUntilPic(bForward, 0);
	//			}
	//		}
	//		else {
	//			if (minRight < minForward) {
	//				Log.i(TAG, "Turning right");
	//				doTurnUntilPic(bRight, 1);
	//			}
	//			else {
	//				Log.i(TAG, "Going straight");
	//				doTurnUntilPic(bForward, 0);
	//			}
	//		}
	//
	//		mRoboAntControl.setSpeeds(100, 100);
	//
	//		try {
	//			Thread.sleep(500);
	//		} catch (InterruptedException e) {
	//			e.printStackTrace();
	//		}
	//		mRoboAntControl.setSpeeds(0, 0);
	//	}

	private void doTurnSA(Bitmap routePic) {
		//		int startSpeed = 120;
		int kmax = 100;
		//		int startT = 300;
		double degToSpeed = 200/90.;
		int startT = 0;

		Bitmap curPic = takePicture();

		//		int s = 0; 
		double e = imagesSSD(routePic, curPic);

		//		int sbest = star;
		double ebest = e;

		int snew;
		double enew = e;

		int k = 0;

		double T;

		int totalTurned = 0;

		int etarget = 2000000;

		Log.i(TAG, "start e is " + enew);

		int speed;

		int turnMax = 200;

		while (k < kmax && enew > etarget) {
			T = 120 * (1 - ((double)k)/kmax);

			snew = (int)(T - Math.random()*2*T);
			// -80  -- + 80

			if (snew + totalTurned > turnMax) {
				snew = turnMax - totalTurned;
				totalTurned = turnMax - snew;
			}

			else if (snew + totalTurned < -turnMax) {
				snew = -turnMax - totalTurned;
				totalTurned = -turnMax - snew;
			}

			speed = (int)(degToSpeed * snew);
			if (Math.abs(speed) < 80) {
				if (speed < 0) {
					speed = -80;
				}
				else {
					speed = 80;
				}
			}

			mRoboAntControl.simpleTurnInPlaceBlocking(speed, 100);
			try {
				Thread.sleep(300);
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

			totalTurned += snew;

			curPic = takePicture();
			enew = imagesSSD(routePic, curPic);

			mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(k, 0, enew));

			Log.i(TAG, "Turned " + totalTurned + " energy is " + enew);

			if (enew < e) {
				e = enew;
			}
			else {
				Log.i(TAG, "exp " + Math.exp(-(enew - e)/(T*8000)) + " " + -(enew - e)/(T*8000));
				if (Math.random() < Math.exp(-(enew - e)/(T*8000))) {
					e = enew;
				}
				else {
					// go back to previous state
					mRoboAntControl.simpleTurnInPlaceBlocking(-speed, 100);
					totalTurned -= snew;
				}
			}

			++k;
		}
	}

	private void moveTowardsMin(ArrayList<TurnStep> turnsteps, List<Bitmap> moveTowards) {

		Log.i(TAG, "Turn pictures: " + turnsteps.size() + " moveTowards pictures " + moveTowards.size());
		Log.i(TAG, "Total to evaluate: " + turnsteps.size() * moveTowards.size());

		int picNum;
		double minTurnDist, minRoutePicDist, dist;
		Bitmap bestPicTowards = null;
		Bitmap minTurnPic = null;
		Bitmap minRoutePic = null;
		minTurnDist = 100000000000L;
		int minStep = -1;
		int minRouteStep = -1;
		int turnNum = 0;
		for (TurnStep step: turnsteps) {
			Bitmap bmp = step.bmp;
			minRoutePicDist = 100000000000L; 
			picNum = 0;
			int currentMinRouteStep = -1;
			bestPicTowards = null;
			for (Bitmap routePic: moveTowards) {
				dist = imagesSSD(routePic, bmp);
				//				Log.i(TAG, "Dist is " + dist);
				if (dist < minRoutePicDist) {
					minRoutePicDist = dist;
					currentMinRouteStep = picNum;  
					bestPicTowards = routePic;
					Log.i(TAG, "Best route pic for step " + turnNum + " is " + currentMinRouteStep + " " + dist);
				}
				picNum += 1;
			}
			//			mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(turnsteps.get(turnNum).deg, currentMinRouteStep, minRoutePicDist));
			if (minRoutePicDist < minTurnDist) {
				minTurnDist = minRoutePicDist;
				minTurnPic = bmp;
				minRoutePic = bestPicTowards;
				minStep = turnNum;
				minRouteStep = currentMinRouteStep;
			}
			mPicToPublishGoTowards  = minRoutePic;
			mPicToPublish = minTurnPic;
			mCurrentStepToPublish = minStep;
			mGoTowardsToPublish = minRouteStep;
			publishProgress("Turn " + turnNum + "Current min is " + minStep + " " + minRouteStep + " " + minTurnDist);
			Log.i(TAG, "Current min is " + minStep + " " + minRouteStep + " " + minTurnDist);
			turnNum ++;
		}

		mPicToPublishGoTowards  = minRoutePic;
		mPicToPublish = minTurnPic;
		mCurrentStepToPublish = minStep;
		mGoTowardsToPublish = minRouteStep;
		mBestImageNum = minRouteStep;

		publishProgress("Going towards " + minStep + "\nDist:"+minTurnDist);

		int turnTo = turnsteps.get(minStep).deg;
		mNetworkControl.sendMessage(NetworkControl.TURN_TO + turnTo);
		mNetworkControl.sendMessage(NetworkControl.ROUTE_MATCH + minRouteStep);

		if (AWAIT_CONFIRM) {
			waitLock();
		}

		Log.i(TAG, "Turning to " + turnTo);
		//		doTurnUntilPic(Util.rotateBitmap(minTurnPic, -45), -45);
		doTurnUntilPic(minTurnPic, turnTo);

		//		if (AWAIT_CONFIRM) {
		//			waitLock();
		//		}
		//
		//
		//		//		doTurn(turnTo);
		//		doTurnUntil(minTurnPic, turnTo);
		//		doTurnUntilFlow(minTurnPic, turnTo < 0 ? -1 : 1);

		mRoboAntControl.setSpeeds(100, 100);

		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		mRoboAntControl.setSpeeds(0, 0);
	}
	//
	//	private void showTurningSlope() {
	//		//		Bitmap bmp = Util.rotateBitmap(takePicture(), 40);
	//		Bitmap bmp = takePicture();
	//		Bitmap compareTo = Util.rotateBitmap(mCompareToBmp, 40);
	//
	//		double prevDif = 0;
	//		double curDif = imagesSSD(bmp, compareTo);
	//
	//		int dir = 1;
	//
	//		bmp.recycle();
	//
	//		int turnSpeed = 100, turnTime = 100;
	//		int thresh = 3000000;
	//		int deg = 0;
	//		double bestDif = curDif;
	//		int bestAt = deg;
	//
	//		//		doTurn(dir * TURN_STEP);
	//		mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(deg, 0, curDif));
	//		Log.i(TAG, "curDif " + curDif + " prevDif " + prevDif);
	//		while (deg < 50) {
	//			deg += dir;
	//
	//			//			try {
	//			//				Thread.sleep(200);
	//			//			}
	//			//			catch (InterruptedException e) {
	//			//				e.printStackTrace();
	//			//			}
	//			//			doTurn(turnStep);
	//			mRoboAntControl.simpleTurnInPlaceBlocking(dir*turnSpeed, turnTime);
	//			bmp = takePicture();
	//			prevDif = curDif;
	//			curDif = imagesSSD(bmp, compareTo);
	//			if (curDif < bestDif) {
	//				bestDif = curDif;
	//				bestAt = deg;
	//			}
	//			mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(deg, 0, curDif));
	//			Log.i(TAG, "curDif " + curDif + " prevDif " + prevDif + " best dif " + bestDif + " " + bestAt);
	//			bmp.recycle();
	//		}
	//
	//		int smooth_by = 5;
	//		DescriptiveStatistics stats = new DescriptiveStatistics();
	//		stats.setWindowSize(smooth_by);
	//		stats.addValue(curDif);
	//
	//		dir = -1;
	//		while (deg > -100) {
	//			deg += dir;
	//
	//			//			try {
	//			//				Thread.sleep(200);
	//			//			}
	//			//			catch (InterruptedException e) {
	//			//				e.printStackTrace();
	//			//			}
	//			//			doTurn(turnStep);
	//			mRoboAntControl.simpleTurnInPlaceBlocking(dir*turnSpeed, turnTime);
	//			bmp = takePicture();
	//			prevDif = curDif;
	//			curDif = imagesSSD(bmp, mCompareToBmp);
	//			if (curDif < bestDif) {
	//				bestDif = curDif;
	//				bestAt = deg;
	//			}
	//			stats.addValue(curDif);
	//			mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(deg, 0, stats.getMean()));
	//			Log.i(TAG, "curDif " + curDif + " prevDif " + prevDif + " best dif " + bestDif + " " + bestAt);
	//			bmp.recycle();
	//		}
	//	}

	private void doTurnUntilPic(Bitmap turnTo, int initialDir) {
		Bitmap bmp = takePicture();

		double prevDif = 0;
		double curDif = imagesSSD(bmp, turnTo);

		int dir = initialDir >= 0 ? 1 : -1;

		bmp.recycle();

		int turnSpeed = 80, turnTime = 100;
		int deg = 0;
		double bestDif = curDif;
		int bestAt = deg;

		double thisMean, prevMean;

		mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(deg, 0, curDif));
		Log.i(TAG, "curDif " + curDif + " prevDif " + prevDif);

		int smooth_by = 80;
		DescriptiveStatistics stats = new DescriptiveStatistics();
		stats.setWindowSize(smooth_by);
		stats.addValue(curDif);

		thisMean = stats.getMean();
		prevMean = thisMean;
		double meanMax = thisMean;
		//		double thresh = 100000;
		double thresh = 2000;
		double minThresh = 200;
		//		double thresh = 10000000;
		double stopThresh = 400000;

		boolean peak1 = false;
		int count = 0;

		int messageCount = 0;
		int sendMessageEvery = 20;

		int changedDir = 0;
		int maxChangedDir = 5;
		mRoboAntControl.setSpeeds(dir*turnSpeed, -dir*turnSpeed);
		while (true) {
			if (mStop) {
				break;
			}
			deg += dir;

			//			try {
			//				Thread.sleep(200);
			//			}
			//			catch (InterruptedException e) {
			//				e.printStackTrace();
			//			}
			//			doTurn(turnStep);
			//			mRoboAntControl.simpleTurnInPlaceBlocking(dir*turnSpeed, turnTime);
			bmp = takePicture();
			prevDif = curDif;
			prevMean = thisMean;
			curDif = imagesSSD(bmp, turnTo);
			if (curDif < bestDif) {
				bestDif = curDif;
				bestAt = deg;
			}
			stats.addValue(curDif);
			thisMean = stats.getMean();
			if (peak1 && Math.abs(meanMax - thisMean) < stopThresh) {
				break;
				//				continue;
			}
			if (thisMean < meanMax) {
				meanMax = thisMean;
			}
			count++;
			if (count % sendMessageEvery == 0) {
				mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(messageCount++, 0, thisMean));
			}

			Log.i(TAG, "Skewness " + stats.getSkewness());
			if (stats.getN() == smooth_by && thisMean > prevMean && (thisMean - prevMean) > thresh) {
				//			thisSkewness = stats.getSkewness();
				//			prevSkewness = stats.getSkewness();
				//			stats.get
				//			if (stats.getN() > smooth_by && Math.abs(thisMean - prevMean) < minThresh) {
				//			if (stats.getN() > smooth_by && Math.abs(stats.getSkewness()) < 0.5) {
				//				if (peak1) {
				//					mRoboAntControl.simpleTurnInPlaceBlocking(dir*turnSpeed, 2*turnTime);
				//					bmp = takePicture();
				//					curDif = imagesSSD(bmp, turnTo);
				//					stats.addValue(curDif);
				//					thisMean = stats.getMean();
				//					mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(count+10, 0, thisMean));
				//					break;
				//				}
				changedDir++;
				if (changedDir > maxChangedDir) {
					Log.i(TAG, "maxChangedDir reached");
					break;
				}
				dir = -dir;
				Log.i(TAG, "peak1 true");
				mRoboAntControl.setSpeeds(dir*turnSpeed, -dir*turnSpeed);
				peak1 = true;
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}


			Log.i(TAG, "curDif " + curDif + " prevDif " + prevDif + " best dif " + bestDif + " " + bestAt);
			bmp.recycle();
		}
		mRoboAntControl.setSpeeds(0, 0);
	}

	private void doTurnUntilFlow(Bitmap untilBmp, int initialDir) {
		Bitmap bmp = takePicture();

		double prevFlow;
		double curFlow = mCameraControl.getOpticFlow(untilBmp, bmp);

		int dir = initialDir;

		bmp.recycle();

		double k = 30;
		int turn_speed = 80;
		int turn_time2 = 100;

		boolean method1 = false;
		Log.i(TAG, "curFlow is " + curFlow);

		while (curFlow > 0.5) {
			Log.i(TAG, "curFlow is " + curFlow);
			if (method1) {
				mRoboAntControl.setSpeeds(dir*turn_speed, -dir*turn_speed);
				try {
					Thread.sleep(turn_time2);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				mRoboAntControl.setSpeeds(0, 0);
				try {
					Thread.sleep(turn_time2);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			else {
				doTurn((int)(dir*curFlow));
			}
			prevFlow = curFlow;
			bmp = takePicture();
			curFlow = mCameraControl.getOpticFlow(untilBmp, bmp);
			if (prevFlow < curFlow) {
				dir = -dir;
			}
		}

	}

	private void doTurnUntil(Bitmap bmpToMatch, int turnTo) {
		Bitmap bmp = takePicture();

		double prevDif = 0;
		double curDif = imagesSSD(bmp, bmpToMatch);

		int dir = turnTo > 0 ? 1 : -1;

		bmp.recycle();

		int turnSpeed = 80, turnTime = 100;
		int thresh = 3000000;
		int deg = 0;

		//		doTurn(dir * TURN_STEP);
		mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(deg, 0, curDif));
		Log.i(TAG, "curDif " + curDif + " prevDif " + prevDif);
		while (curDif > thresh) {
			deg += dir;

			//			try {
			//				Thread.sleep(200);
			//			}
			//			catch (InterruptedException e) {
			//				e.printStackTrace();
			//			}
			//			doTurn(turnStep);
			mRoboAntControl.simpleTurnInPlaceBlocking(dir*turnSpeed, turnTime);
			bmp = takePicture();
			prevDif = curDif;
			curDif = imagesSSD(bmp, bmpToMatch);
			mNetworkControl.sendMessage(Util.newLookAroundSSDMessage(deg, 0, curDif));
			Log.i(TAG, "curDif " + curDif + " prevDif " + prevDif);
			dir = curDif < prevDif ? dir : -dir;
			bmp.recycle();
		}
	}

	private void goTowards(int minStep) {
		mRoboAntControl.doGoTowards(this, minStep);
		waitLock();
	}

	private void lookAroundStep() {
		Log.i(TAG, "Doing look around step");
		mRoboAntControl.doLookAroundStep(this);
		waitLock();
	}

	@Override
	protected void onProgressUpdate(String... values) {
		super.onProgressUpdate(values);
		mMessageView.setText(values[0]);
		if (mPicToPublish != null) {
			mCurrentStepPic.setImageBitmap(mPicToPublish);
			mPicToPublish = null;
		}
		if (mPicToPublishGoTowards != null) {
			mGoTowardsPic.setImageBitmap(mPicToPublishGoTowards);
			mPicToPublishGoTowards = null;
		}
		if (mFollowingRoute) {
			mProgressBar.setProgress(mBestImageNum);
		}
		else {
			mProgressBar.setVisibility(View.GONE);
		}

		mCurrentStepNum.setText(mCurrentStepToPublish+"");
		mGoTowardsNum.setText(mGoTowardsToPublish+"");
	}

	private Bitmap mTakePictureBuffer;

	private Bitmap takePicture() {
		Log.i(TAG, "Taking picture..");
		mTakePictureBuffer = null;
		// critical start
		return mCameraControl.getPicture();
		//		mCameraControl.getPicture(new OpenCVCamera.PictureListener() {
		//
		//			@Override
		//			public void pictureReceived(final Bitmap picture) {
		//				Log.i(TAG, "pictureReceived " + picture);
		//
		////				GLOBAL.PICTURE_STORAGE = picture;
		////				GLOBAL.PICTURE_MUTEX.release();
		//				mHandler.
		//				Log.i(TAG, "Released mutex " + Thread.currentThread().getName());
		//			}
		//		});

		//		try {
		//			GLOBAL.PICTURE_MUTEX.acquire();
		//		} catch (InterruptedException e1) {
		//			// TODO Auto-generated catch block
		//			e1.printStackTrace();
		//		}
		//		synchronized (lock) {
		//			try {
		//				lock.wait();
		//			} catch (InterruptedException e) {
		//				e.printStackTrace();
		//			}
		//		}
		//
		//		try {
		//			Log.i(TAG, "Acquiring mutex " + Thread.currentThread().getName() + " " + GLOBAL.PICTURE_MUTEX.availablePermits());
		//			GLOBAL.PICTURE_MUTEX.acquire();
		//			Log.i(TAG, "Acquired mutex " + Thread.currentThread().getName());
		//			return GLOBAL.PICTURE_STORAGE;
		//		} catch (InterruptedException e) {
		//			Log.i(TAG, "Interrupted");
		//			e.printStackTrace();
		//		}
		//		Bitmap bmp = Bitmap.createBitmap(GLOBAL.PICTURE_STORAGE);
		//		return bmp;

	}

	private void waitLock() {
		synchronized (lock) {
			try {
				lock.wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private class Point {
		int x, y;
		public Point(int x, int y) {
			this.x = x; this.y = y;
		}
	}

	private double imagesSSD(Bitmap b1, Bitmap b2) {

		if (mPixelsToCheck == null) {
			int pixel, r, g, bl, a;
			mPixelsToCheck = new HashSet<Point>();
			for (int i = 0; i < b1.getWidth(); ++i) {
				for (int j = 0; j < b1.getHeight(); ++j) {
					pixel = b1.getPixel(i, j);
					r = Color.red(pixel);
					g = Color.green(pixel);
					bl = Color.blue(pixel);
					a = Color.alpha(pixel);
					if (a == 0 || (r == 0 && g == 0 && bl == 0)) {
					}
					else {
						mPixelsToCheck.add(new Point(i, j));
					}
				}
			}
			for (int i = 0; i < b1.getWidth(); ++i) {
				for (int j = 0; j < b1.getHeight(); ++j) {
					//					int pixel = b1.getPixel(i, j);
					mPixelsToCheck.add(new Point(i, j));
				}
			}
			Log.i(TAG, "mPixelsToCheck size " + mPixelsToCheck.size() + " image size " + b1.getHeight() * b1.getWidth());
		}

		double ssd = 0;
		int pixel1, pixel2, r1, r2, g1, g2, bl1, bl2;

		//		Log.i(TAG, "mPixelsToCheck size " + mPixelsToCheck.size() + " " + b1.getByteCount() + " " + b2.getByteCount());
		for (Point p: mPixelsToCheck) {
			pixel1 = b1.getPixel(p.x, p.y);
			pixel2 = b2.getPixel(p.x, p.y);
			//			r1 = Color.red(pixel1);
			//			r2 = Color.red(pixel2);
			g1 = Color.green(pixel1);
			g2 = Color.green(pixel2);
			//			bl1 = Color.blue(pixel1);
			//			bl2 = Color.blue(pixel2);


			//			ssd += (r1 - r2) * (r1 - r2) +
			//					(g1 - g2) * (g1 - g2) +
			//					(bl1 - bl2) * (bl1 - bl2);
			ssd += (g1 - g2) * (g1 - g2);


		}
		//			}
		//		}

		//        Log.i(TAG, "imagesSSD runtime " + (System.currentTimeMillis() - start));

		return normalizeSSD(ssd);
	}

	private double normalizeSSD(double ssd) {
		if (!mSSDCalibrated) {
			Log.w(TAG, "Not calibrated, returning normal ssd");
			return ssd;
		}
		double calibrated = (ssd - mSSDMin)/(mSSDMax - mSSDMin);
		if (calibrated < 0 || calibrated > 1) {
//			throw(new RuntimeException("Calibrated is " + calibrated));
			Log.w(TAG, "Calibrated is < 0 or > 1 " + calibrated + ssd);
			if (calibrated < 0) {
				mSSDMin = ssd;
			}
			else {
				mSSDMax = ssd;
			}
			
			GLOBAL.getSettings().setSSDCalibrationResults(true, mSSDMin, mSSDMax);
			
			return normalizeSSD(ssd);
		}
		return calibrated;
	}

	public Handler getHandler() {
		return mHandler;
	}


	public void stepTowards() {
		Log.i(TAG, "Step towards");
		mHandler.post(new Runnable() {

			@Override
			public void run() {
				// TODO Auto-generated method stub
				Log.i(TAG, "IMHERE HEHE");
				Log.i(TAG, Thread.currentThread().getName());
				mCompareToBmp = takePicture();
				mPicToPublishGoTowards = mCompareToBmp;
				//		Log.i(TAG, "Notifying lock");
				//                try {
				//                    Thread.sleep(2000);
				//                } catch (InterruptedException e) {
				//                     TODO Auto-generated catch block
				//                    e.printStackTrace();
				//                }
				synchronized (lock) {
					lock.notify();
				}
			}
		});

		//            }
		//        });


	}

	@Override
	public void lookAroundDone() {
		mLookAroundDone = true;
		synchronized (lock) {
			lock.notify();
		}
	}

	@Override
	public void lookAroundStepDone(int step) {
		mLookAroundStep = step;
		synchronized (lock) {
			lock.notify();
		}
	}

	@Override
	public void goTowardsDone() {
		synchronized (lock) {
			lock.notify();
		}
	}

	public void followRoute(LinkedList<Bitmap> routePictures) {
		Log.i(TAG, "Follow route started!");
		mFollowingRoute = true;
		mRoutePictures = routePictures;
		synchronized (lock) {
			lock.notify();
		}
	}

	public void stop() {
		mStop = true;
		synchronized (lock) {
			lock.notify();
		}
	}

	public void notifyTargetReached() {
		synchronized (lock) {
			lock.notify();
		}
	}

	public void releaseLock() {
		synchronized (lock) {
			lock.notify();
		}
	}

	private double mCalibrateSSDMin;

	private double mCalibrateSSDMAX;

	private Bitmap mCalibrateBmp;
	
	private long mLastTimeChanged;

	public void calibrateSSD() {
		mRoboAntControl.setSpeeds(-100, 100);
		mCalibrateSSDMin = Double.MAX_VALUE;
		mCalibrateSSDMAX = Double.MIN_VALUE;
		mCalibrateBmp = mCameraControl.getPicture();
		
		mSSDCalibrated = false;
		
		mLastTimeChanged = System.currentTimeMillis();

		mHandler.postDelayed(new Runnable() {
			

			@Override
			public void run() {
				Bitmap bmp = mCameraControl.getPicture();
				boolean changed = false;
				
				double ssd = imagesSSD(bmp, mCalibrateBmp);
				if (ssd < mCalibrateSSDMin) {
					mCalibrateSSDMin = ssd;
					changed = true;
				}
				if (ssd > mCalibrateSSDMAX) {
					mCalibrateSSDMAX = ssd;
					changed = true;
				}
				
				if (changed) {
					mLastTimeChanged = System.currentTimeMillis();
				}
				else {
					if (System.currentTimeMillis() - mLastTimeChanged > 2000) {
						// do not post this callback again
						GLOBAL.getSettings().setSSDCalibrationResults(true, mCalibrateSSDMin, mCalibrateSSDMAX);
						mSSDCalibrated = true;
						mSSDMin = mCalibrateSSDMin;
						mSSDMax = mCalibrateSSDMAX;
						mRoboAntControl.setSpeeds(0, 0);
						return;
					}
				}
				mHandler.post(this);
				
			}
		}, 100);
		
	}

}
