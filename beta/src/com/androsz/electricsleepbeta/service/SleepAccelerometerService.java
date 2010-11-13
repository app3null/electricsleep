package com.androsz.electricsleepbeta.service;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;

import com.androsz.electricsleepbeta.R;
import com.androsz.electricsleepbeta.alarmclock.Alarm;
import com.androsz.electricsleepbeta.alarmclock.Alarms;
import com.androsz.electricsleepbeta.app.CalibrationWizardActivity;
import com.androsz.electricsleepbeta.app.SaveSleepActivity;
import com.androsz.electricsleepbeta.app.SettingsActivity;
import com.androsz.electricsleepbeta.app.SleepActivity;

public class SleepAccelerometerService extends Service implements
		SensorEventListener {
	public static final String POKE_SYNC_CHART = "com.androsz.electricsleepbeta.POKE_SYNC_CHART";
	public static final String STOP_AND_SAVE_SLEEP = "com.androsz.electricsleepbeta.STOP_AND_SAVE_SLEEP";
	public static final String SLEEP_STOPPED = "com.androsz.electricsleepbeta.SLEEP_STOPPED";

	private static final int NOTIFICATION_ID = 0x1337a;

	private final ArrayList<Double> currentSeriesX = new ArrayList<Double>();
	private final ArrayList<Double> currentSeriesY = new ArrayList<Double>();

	private WakeLock partialWakeLock;

	private long lastChartUpdateTime = 0;
	// private double minSensitivity = SettingsActivity.DEFAULT_MIN_SENSITIVITY;
	private double alarmTriggerSensitivity = SettingsActivity.DEFAULT_ALARM_SENSITIVITY;

	private boolean airplaneMode = false;
	private boolean useAlarm = false;
	private int alarmWindow = 30;

	private int updateInterval = CalibrationWizardActivity.ALARM_CALIBRATION_TIME;

	private Date dateStarted;

	public static final int SENSOR_DELAY = SensorManager.SENSOR_DELAY_NORMAL;

	private final BroadcastReceiver pokeSyncChartReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			// if (currentSeriesX.size() > 0 && currentSeriesY.size() > 0) {
			final Intent i = new Intent(SleepActivity.SYNC_CHART);
			i.putExtra("currentSeriesX", currentSeriesX);
			i.putExtra("currentSeriesY", currentSeriesY);
			i.putExtra("min", minNetForce);
			i.putExtra("alarm", alarmTriggerSensitivity);
			i.putExtra("useAlarm", useAlarm);
			sendBroadcast(i);
			// }
		}
	};

	private final BroadcastReceiver stopAndSaveSleepReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			final Intent saveIntent = addExtrasToSaveSleepIntent(new Intent(
					SleepAccelerometerService.this, SaveSleepActivity.class));
			startActivity(saveIntent);
			stopSelf();
		}
	};

	private final BroadcastReceiver alarmDoneReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			createSaveSleepNotification();
			stopSelf();
		}
	};

	private static String LOCK_TAG = "com.androsz.electricsleepbeta.service.SleepAccelerometerService";

	private double maxNetForce = 0;

	private double minNetForce = Double.MAX_VALUE;

	private static final double a0 = 0.535144118;

	private static final double a1 = -0.132788237;

	private static final double a2 = -0.402355882;

	private static final double b1 = -0.154508496;

	private static final double b2 = -0.0625;

	private double x_r_1, x_r_2, x_1; // x_r_1 means x[n-1], etc.

	private int count = 0;

	private Intent addExtrasToSaveSleepIntent(final Intent saveIntent) {
		saveIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
				| Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
		saveIntent.putExtra("id", hashCode());
		saveIntent.putExtra("currentSeriesX", currentSeriesX);
		saveIntent.putExtra("currentSeriesY", currentSeriesY);
		saveIntent.putExtra("min", minNetForce);
		saveIntent.putExtra("alarm", alarmTriggerSensitivity);

		// send start/end time as well
		final DateFormat sdf = DateFormat.getDateTimeInstance(DateFormat.SHORT,
				DateFormat.SHORT, Locale.getDefault());
		DateFormat sdf2 = DateFormat.getDateTimeInstance(DateFormat.SHORT,
				DateFormat.SHORT, Locale.getDefault());
		final Date now = new Date();
		if (dateStarted.getDate() == now.getDate()) {
			sdf2 = DateFormat.getTimeInstance(DateFormat.SHORT);
		}
		saveIntent.putExtra("name", sdf.format(dateStarted) + " "
				+ getText(R.string.to) + " " + sdf2.format(now));
		return saveIntent;
	}

	private void createSaveSleepNotification() {
		final NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		final int icon = R.drawable.home_btn_sleep_pressed;
		final CharSequence tickerText = getText(R.string.notification_save_sleep_ticker);
		final long when = System.currentTimeMillis();

		final Notification notification = new Notification(icon, tickerText,
				when);

		notification.flags = Notification.FLAG_AUTO_CANCEL;

		final Context context = getApplicationContext();
		final CharSequence contentTitle = getText(R.string.notification_save_sleep_title);
		final CharSequence contentText = getText(R.string.notification_save_sleep_text);
		final Intent notificationIntent = addExtrasToSaveSleepIntent(new Intent(
				this, SaveSleepActivity.class));
		final PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				notificationIntent, 0);

		notification.setLatestEventInfo(context, contentTitle, contentText,
				contentIntent);

		notificationManager.notify(this.hashCode(), notification);
		startActivity(notificationIntent);
	}

	private Notification createServiceNotification() {
		final int icon = R.drawable.icon_small;
		final CharSequence tickerText = getText(R.string.notification_sleep_ticker);
		final long when = System.currentTimeMillis();

		final Notification notification = new Notification(icon, tickerText,
				when);

		notification.flags = Notification.FLAG_ONGOING_EVENT;

		final Context context = getApplicationContext();
		final CharSequence contentTitle = getText(R.string.notification_sleep_title);
		final CharSequence contentText = getText(R.string.notification_sleep_text);
		final Intent notificationIntent = new Intent(this, SleepActivity.class);
		notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
				| Intent.FLAG_ACTIVITY_NEW_TASK);
		final PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				notificationIntent, 0);

		notification.setLatestEventInfo(context, contentTitle, contentText,
				contentIntent);

		return notification;
	}

	private void obtainWakeLock() {
		final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		partialWakeLock = powerManager.newWakeLock(
				PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG);
		partialWakeLock.acquire();
		partialWakeLock.setReferenceCounted(false);
	}

	@Override
	public void onAccuracyChanged(final Sensor sensor, final int accuracy) {
		// not used
	}

	@Override
	public IBinder onBind(final Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		obtainWakeLock();
		registerAccelerometerListener();
		lastChartUpdateTime = System.currentTimeMillis();

		registerReceiver(pokeSyncChartReceiver, new IntentFilter(
				POKE_SYNC_CHART));

		registerReceiver(stopAndSaveSleepReceiver, new IntentFilter(
				STOP_AND_SAVE_SLEEP));

		registerReceiver(alarmDoneReceiver, new IntentFilter(
				Alarms.ALARM_DONE_ACTION));

		dateStarted = new Date();
		startForeground(NOTIFICATION_ID, createServiceNotification());
	}

	@Override
	public void onDestroy() {
		unregisterAccelerometerListener();

		if (partialWakeLock.isHeld()) {
			partialWakeLock.release();
		}

		unregisterReceiver(pokeSyncChartReceiver);
		unregisterReceiver(stopAndSaveSleepReceiver);
		unregisterReceiver(alarmDoneReceiver);

		// tell monitoring activities that sleep has ended
		sendBroadcast(new Intent(SLEEP_STOPPED));

		toggleAirplaneMode(false);

		stopForeground(true);

		super.onDestroy();
	}

	@Override
	public void onSensorChanged(final SensorEvent event) {
		if (count < 3) {
			count++;
			return;
		}
		final long currentTime = System.currentTimeMillis();
		final long timeSinceLastChartUpdate = currentTime - lastChartUpdateTime;

		final double curX = event.values[0];
		final double curY = event.values[1];
		final double curZ = event.values[2];

		// final long timeSinceLastSensorChange = currentTime
		// - lastOnSensorChangedTime;

		final double mAccelCurrent = Math
				.sqrt((curX * curX + curY * curY + curZ * curZ))
				- SensorManager.GRAVITY_EARTH;

		final double x_r_0 = a0 * mAccelCurrent + a1 * x_r_1 + a2 * x_r_2 + b1
				* x_r_1 + b2 * x_r_2;
		x_1 = mAccelCurrent;
		x_r_2 = x_r_1;
		x_r_1 = x_r_0;

		final double force = Math.abs(x_r_0);

		maxNetForce = force > maxNetForce ? force : maxNetForce;
		// lastOnSensorChangedTime = currentTime;

		if (timeSinceLastChartUpdate >= updateInterval) {
			if (maxNetForce < minNetForce) {
				minNetForce = maxNetForce;
			}
			final double x = currentTime;
			final double y = java.lang.Math.min(alarmTriggerSensitivity,
					maxNetForce);

			currentSeriesX.add(x);
			currentSeriesY.add(y);

			final Intent i = new Intent(SleepActivity.UPDATE_CHART);
			i.putExtra("x", x);
			i.putExtra("y", y);
			i.putExtra("min", minNetForce);
			i.putExtra("alarm", alarmTriggerSensitivity);
			sendBroadcast(i);

			// totalTimeBetweenSensorChanges = 0;

			lastChartUpdateTime = currentTime;
			maxNetForce = 0;

			if(triggerAlarmIfNecessary(currentTime, y))
			{
				unregisterAccelerometerListener();
			}
		}
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags,
			final int startId) {
		if (intent != null && startId == 1) {
			updateInterval = intent.getIntExtra("interval",
					CalibrationWizardActivity.ALARM_CALIBRATION_TIME);
			alarmTriggerSensitivity = intent.getDoubleExtra("alarm", 2d);

			useAlarm = intent.getBooleanExtra("useAlarm", false);
			alarmWindow = intent.getIntExtra("alarmWindow", 0);
			airplaneMode = intent.getBooleanExtra("airplaneMode", false);

			toggleAirplaneMode(true);
		}
		return startId;
	}

	private void registerAccelerometerListener() {
		final SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

		sensorManager.registerListener(this,
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				SENSOR_DELAY);
	}

	private void toggleAirplaneMode(final boolean enabling) {
		if (airplaneMode) {
			Settings.System.putInt(getContentResolver(),
					Settings.System.AIRPLANE_MODE_ON, enabling ? 1 : 0);

			final Intent intent = new Intent(
					Intent.ACTION_AIRPLANE_MODE_CHANGED);
			intent.putExtra("state", enabling);
			sendBroadcast(intent);
		}
	}

	private boolean triggerAlarmIfNecessary(final long currentTime,
			final double y) {
		if (useAlarm) {
			// final AlarmDatabase adb = new
			// AlarmDatabase(getContentResolver());
			final Alarm alarm = Alarms.calculateNextAlert(this);// adb.getNearestEnabledAlarm();
			if (alarm != null) {
				final Calendar alarmTime = Calendar.getInstance();
				alarmTime.setTimeInMillis(alarm.time);
				alarmTime.add(Calendar.MINUTE, alarmWindow * -1);
				final long alarmMillis = alarmTime.getTimeInMillis();
				if (currentTime >= alarmMillis && y >= alarmTriggerSensitivity) {
					//alarm.time = currentTime;
					partialWakeLock.release();
					Alarms.enableAlert(this, alarm, currentTime);

					return true;
				}
			}
		}
		return false;
	}

	private void unregisterAccelerometerListener() {
		final SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		sensorManager.unregisterListener(this);
	}
}