/*
 * Copyright (c) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.vuze.android.remote.service;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;

import org.gudy.azureus2.core3.download.DownloadManager;
import org.gudy.azureus2.core3.global.GlobalManager;
import org.gudy.azureus2.core3.global.GlobalManagerListener;
import org.gudy.azureus2.core3.global.GlobalManagerStats;
import org.gudy.azureus2.core3.util.*;
import org.gudy.azureus2.plugins.PluginInterface;
import org.gudy.azureus2.plugins.ui.config.BooleanParameter;

import com.aelitis.azureus.core.*;
import com.aelitis.azureus.core.networkmanager.admin.NetworkAdmin;
import com.aelitis.azureus.core.pairing.PairingManager;
import com.aelitis.azureus.core.pairing.PairingManagerFactory;
import com.aelitis.azureus.core.pairing.impl.PairingManagerImpl;
import com.aelitis.azureus.core.tag.*;
import com.vuze.android.core.az.VuzeManager;
import com.vuze.android.remote.*;
import com.vuze.android.remote.activity.IntentHandler;
import com.vuze.android.util.NetworkState;
import com.vuze.util.DisplayFormatters;
import com.vuze.util.Thunk;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.Resources;
import android.net.wifi.WifiManager;
import android.os.*;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

/**
 * Android Service handling launch and shutting down Vuze core as well as
 * the notification section
 * <p/>
 * Typically instatiated by {@link VuzeServiceInitImpl}.  Lifecycle:<br>
 * 1) VuzeServiceInit starts and binds to service
 * <br>
 * 2) Service start {@link #onStartCommand(Intent, int, int)}
 * <br>
 * 3) Notification entry created
 * <br>
 * 4) VuzeServiceInit receives {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)}
 * <br>
 * 5) VuzeServiceInit sends {@link #MSG_IN_ADD_LISTENER} to listen to the MSG_OUT_* actions
 * <br>
 * 6) VuzeService sends {@link #MSG_OUT_REPLY_ADD_LISTENER} with state of core (restarting, stopping, stopped, ready-to-start)
 * <br>
 * 7) If reply is read-to-start, VuzeServiceInit sends {@link #MSG_IN_START_CORE} to start the core
 * <br>
 * 8) Event {@link #MSG_OUT_CORE_STARTED} fired
 * <br>
 * 9) Event {@link #MSG_OUT_WEBUI_STARTED} fired.
 * <br>
 * 10) Service is requested to stopped by:<br>
 * 10.a) Android OS<br>
 * 10.b) {@link VuzeServiceInitImpl#stopService()} which calls {@link #stopSelf()}<br>
 * 10.c) The Vuze Core itself initiates a shutdown (stop) and notififies this 
 *      service, which invokes stopSelf.  In this case, Event 
 *      {@link #MSG_OUT_CORE_STOPPING} will be sent to VuzeServiceInit<br>
 * <br>
 * 11) Service {@link #onDestroy()} called by Android OS
 * <br>
 * 12) If Vuze core is not already stopping, Vuze Core stop is called, and will
 *     result in the same {@link #MSG_OUT_CORE_STOPPING} event being called as
 *     in 10.c
 * <br>
 * 13) When Vuze core is done stopping, {@link #MSG_OUT_CORE_STOPPED} event is 
 *     sent to VuzeServiceInit
 * <br>
 * 14) If there is request for the Vuze Core to be restarted, Service is restarted
 *     via {@link #startService(Intent)}
 * <br>
 * 15) Event {@link #MSG_OUT_SERVICE_DESTROY} is sent
 * <br>
 * 16) System.exit(0) is called to ensure Android does not keep
 *     the service thread around for super smart caching.  It would be nice
 *     to have a flag on a Service that said "kill the thread after onDestroy".
 *     Since Vuze Core uses a lot of static variables and does not fully clean
 *     them up, we can't guarantee all Vuze Core classes are disposed of unless
 *     we kill the threads.
 * <br>
 * <p/>
 * Goals:
 * - Ability to shut down core when 0 active torrents and no UI attached
 * <p/>
 * Created by TuxPaper on 3/24/16.
 */
public class VuzeService
	extends Service
	implements PairingManagerImpl.UIAdapter, NetworkState.NetworkStateListener,
	CorePrefs.CorePrefsChangedListener
{
	public static final int MSG_IN_ADD_LISTENER = 0;

	public static final int MSG_IN_REMOVE_LISTENER = 1;

	public static final int MSG_IN_START_CORE = 2;

	public static final int MSG_OUT_REPLY_ADD_LISTENER = 10;

	public static final int MSG_OUT_CORE_STARTED = 100;

	public static final int MSG_OUT_CORE_STOPPED = 200;

	public static final int MSG_OUT_WEBUI_STARTED = 300;

	public static final int MSG_OUT_CORE_STOPPING = 150;

	public static final int MSG_OUT_SERVICE_DESTROY = 400;

	static final String TAG = "VuzeService";

	public static final String INTENT_ACTION_START = "com.vuze.android.remote.START_SERVICE";

	public static final String INTENT_ACTION_STOP = "com.vuze.android.remote.STOP_SERVICE";

	public static final String INTENT_ACTION_RESTART = "com.vuze.android.remote.RESTART_SERVICE";

	private static final String INTENT_ACTION_PAUSE = "com.vuze.android.remote.PAUSE_TORRENTS";

	private static final String INTENT_ACTION_RESUME = "com.vuze.android.remote.RESUME_TORRENTS";

	private static final String WIFI_LOCK_TAG = "vuze power lock";

	class IncomingHandler
		extends Handler
	{
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_IN_ADD_LISTENER: {
					if (msg.replyTo != null) {
						mClients.add(msg.replyTo);
					}
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "handleMessage: ADD_LISTENER. coreStarted? "
								+ coreStarted + "; webUIStarted? " + webUIStarted);
					}
					String state;
					if (isCoreStopping || isServiceStopping) {
						state = restartService ? "restarting" : "stopping";
					} else if (coreStarted) {
						state = "started";
					} else {
						state = "ready-to-start";
					}

					Bundle bundle = new Bundle();
					bundle.putString("data", "MSG_OUT_REPLY_ADD_LISTENER");
					bundle.putString("state", state);
					bundle.putBoolean("restarting", restartService);
					sendStuff(MSG_OUT_REPLY_ADD_LISTENER, bundle);

					if (coreStarted) {
						sendStuff(MSG_OUT_CORE_STARTED, "MSG_OUT_CORE_STARTED");
					}
					if (webUIStarted) {
						sendStuff(MSG_OUT_WEBUI_STARTED, "MSG_OUT_WEBUI_STARTED");
					}
					break;
				}
				case MSG_IN_START_CORE: {
					if (isServiceStopping) {
						if (CorePrefs.DEBUG_CORE) {
							Log.d(TAG,
									"handleMessage: ignoring START_CORE as service is stopping");
						}
						return;
					}
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "handleMessage: START_CORE. coreStarted? " + coreStarted
								+ "; webUIStarted? " + webUIStarted);
					}
					if (!coreStarted) {
						new Thread(new Runnable() {
							@Override
							public void run() {
								startCore();
							}
						}).start();
					}
					break;
				}
				case MSG_IN_REMOVE_LISTENER: {
					boolean removed = mClients.remove(msg.replyTo);
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG,
								"handleMessage: REMOVE_LISTENER  "
										+ (removed ? "success" : "failure") + ". # clients "
										+ mClients.size());
					}
					break;
				}
			}
		}
	}

	@Thunk
	class ScreenReceiver
		extends BroadcastReceiver
	{
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
				screenOff = true;
			} else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
				screenOff = false;
				updateNotification();
			}
		}
	}

	@Thunk
	AzureusCore core = null;

	private static File vuzeCoreConfigRoot = null;

	final Messenger mMessenger = new Messenger(new IncomingHandler());

	private boolean skipBind = false;

	final ArrayList<Messenger> mClients = new ArrayList<>(1);

	@Thunk
	final CorePrefs corePrefs;

	private VuzeManager vuzeManager;

	@Thunk
	boolean isCoreStopping;

	/**
	 * Static, because we exitVM on shutdown of the service to ensure
	 * all those static instances Vuze core made aren't sticking around.
	 * This variable is used on VuzeService creation to detect issues.  ie.
	 * If we find a AzureusCore available and restartService is false, the
	 * user probably tried to start up the core right after shutting it down
	 */
	@Thunk
	static boolean restartService = false;

	@Thunk
	boolean seeding_only_mode;

	private Boolean lastOnlineMobile = null;

	private Boolean lastOnline = null;

	private boolean bindToLocalHost = false;

	private int bindToLocalHostReasonID = R.string.core_noti_sleeping;

	private boolean allowNotificationUpdate = true;

	@Thunk
	boolean isServiceStopping;

	private WifiManager.WifiLock wifiLock = null;

	private BroadcastReceiver batteryReceiver = null;

	@Thunk
	boolean lowResourceMode = true;

	@Thunk
	boolean coreStarted;

	@Thunk
	boolean webUIStarted;

	@Thunk
	boolean msgOutCoreStoppedCalled = false;

	private static Object staticVar = null;

	@Thunk
	boolean screenOff = false;

	private ScreenReceiver screenReceiver;
	
	public VuzeService() {
		super();
		coreStarted = false;
		webUIStarted = false;
		corePrefs = new CorePrefs();
		corePrefs.setChangedListener(this);
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "VuzeService: Init Class ");
		}
	}

	private static boolean wouldBindToLocalHost(CorePrefs corePrefs) {
		NetworkState networkState = VuzeRemoteApp.getNetworkState();
		if (corePrefs.getPrefOnlyPluggedIn()
				&& !AndroidUtils.isPowerConnected(VuzeRemoteApp.getContext())) {
			return true;
		} else if (!corePrefs.getPrefAllowCellData()
				&& networkState.isOnlineMobile()) {
			return true;
		} else if (!networkState.isOnline()) {
			return true;
		}
		return false;
	}

	private void buildCustomFile() {
		File vuzeCustomDir = new File(vuzeCoreConfigRoot, "custom");
		vuzeCustomDir.mkdirs();
		try {
			File configFile = new File(vuzeCustomDir, "VuzeRemote_Start.config");
			FileWriter fileWriter = new FileWriter(configFile, false);

			NetworkState networkState = VuzeRemoteApp.getNetworkState();
			bindToLocalHost = false;
			if (corePrefs.getPrefOnlyPluggedIn()
					&& !AndroidUtils.isPowerConnected(VuzeRemoteApp.getContext())) {
				bindToLocalHost = true;
				bindToLocalHostReasonID = R.string.core_noti_sleeping_battery;
			} else if (!corePrefs.getPrefAllowCellData()
					&& networkState.isOnlineMobile()) {
				bindToLocalHost = true;
				bindToLocalHostReasonID = R.string.core_noti_sleeping_oncellular;
			} else if (!networkState.isOnline()) {
				bindToLocalHost = true;
				bindToLocalHostReasonID = R.string.core_noti_sleeping;
			}

			fileWriter.write("Plugin.xmwebui.Port=long:9092\n");

			if (bindToLocalHost) {
				fileWriter.write("Enforce\\ Bind\\ IP=bool:true\n");
				fileWriter.write("Check\\ Bind\\ IP\\ On\\ Start=bool:true\n");
				fileWriter.write("Bind\\ IP=string:127.0.0.1\n");
				fileWriter.write("Plugin.mldht.enable=bool:false\n");
				// this one doesn't work (case)
				fileWriter.write("Plugin.mlDHT.enable=bool:false\n");
				// but this one does!?
				fileWriter.write("Plugin.DHT.dht.enabled=bool:false\n");
				if (CorePrefs.DEBUG_CORE) {
					Log.d(TAG, "buildCustomFile: setting binding to localhost only");
				}
			} else {
				fileWriter.write("Enforce\\ Bind\\ IP=bool:false\n");
				fileWriter.write("Check\\ Bind\\ IP\\ On\\ Start=bool:false\n");
				fileWriter.write("Bind\\ IP=string:\n");
				fileWriter.write("Plugin.mldht.enable=bool:true\n");
				fileWriter.write("Plugin.mlDHT.enable=bool:true\n");
				fileWriter.write("Plugin.DHT.dht.enabled=bool:true\n");
				if (CorePrefs.DEBUG_CORE) {
					Log.d(TAG, "buildCustomFile: clearing binding");
				}
			}
			fileWriter.close();
		} catch (IOException e) {
			Log.e(TAG, "buildCustomFile: ", e);
		}

	}

	@Override
	public void corePrefAutoStartChanged(boolean autoStart) {
		// no triggering needed, on boot event, we check the pref
	}

	@Override
	public void corePrefAllowCellDataChanged(boolean allowCellData) {
		if (vuzeManager != null) {
			sendRestartServiceIntent();
		}
	}

	@Override
	public void corePrefDisableSleepChanged(boolean disableSleep) {
		adjustPowerLock();
	}

	@Override
	public void corePrefOnlyPluggedInChanged(boolean onlyPluggedIn) {
		if (onlyPluggedIn) {
			enableBatteryMonitoring(VuzeRemoteApp.getContext());
		} else {
			disableBatteryMonitoring(VuzeRemoteApp.getContext());
		}
	}

	void sendStuff(int what, @Nullable String s) {
		if (s != null) {
			Bundle bundle = new Bundle();
			bundle.putString("data", s);
			sendStuff(what, bundle);
		} else {
			sendStuff(what, (Bundle) null);
		}
	}

	void sendStuff(int what, @Nullable Bundle bundle) {
		if (bundle != null) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "sendStuff: " + what + "; " + bundle.get("data") + " to "
						+ mClients.size() + " clients");
			}
		}
		for (int i = mClients.size() - 1; i >= 0; i--) {
			try {
				Message obtain = Message.obtain(null, what, 0, 0);
				if (bundle != null) {
					obtain.setData(bundle);
				}
				mClients.get(i).send(obtain);
			} catch (RemoteException e) {
				e.printStackTrace();
				// The client is dead.  Remove it from the list;
				// we are going through the list from back to front
				// so this is safe to do inside the loop.
				mClients.remove(i);
			}
		}
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		if (skipBind) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "Skipping Bind");
			}

			return null;
		}
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "onBind " + intent);
		}

		return mMessenger.getBinder();
	}

	@Thunk
	synchronized void startCore() {
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "VuzeService: startCore");
		}

		//if (BuildConfig.DEBUG) {
		//android.os.Debug.waitForDebugger();
		//}

		if (!AndroidUtils.hasPermisssion(this,
				Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
			// TODO: implement check
			Log.d(TAG, "startCore: No WRITE_EXTERNAL_STORAGE permission");
		}

		// requires WRITE_EXTERNAL_STORAGE
		File storageRoot = Environment.getExternalStorageDirectory();

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.FROYO) {
			File dirDoc = Environment.getExternalStoragePublicDirectory("Documents");
			File dirDl = Environment.getExternalStoragePublicDirectory("Download");
			File dirVideo = Environment.getExternalStoragePublicDirectory("Movies");
			File dirAudio = Environment.getExternalStoragePublicDirectory("Music");
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "Doc=" + dirDoc + "\nDL=" + dirDl + "\nVideo=" + dirVideo
						+ "\nAudio=" + dirAudio + "\nStorage=" + storageRoot);
			}

		}

		File vuzeDownloadDir = new File(new File(storageRoot, "Vuze"), "Downloads");
		File internalRoot = this.getApplicationContext().getFilesDir();
		vuzeCoreConfigRoot = new File(internalRoot, ".vuze");

		if (!vuzeCoreConfigRoot.exists() || !vuzeDownloadDir.exists()) {
			File vuzeCustomDir = new File(vuzeCoreConfigRoot, "custom");
			vuzeCustomDir.mkdirs();
			try {
				File configFile = new File(vuzeCustomDir, "VuzeRemote.config");
				FileWriter fileWriter = new FileWriter(configFile, false);
				fileWriter.write("Default\\ save\\ path=string:"
						+ vuzeDownloadDir.getAbsolutePath().replace("\\", "\\\\"));
				fileWriter.close();
			} catch (IOException ignore) {
			}
			vuzeDownloadDir.mkdirs();
		}

		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "startCore: config root=" + vuzeCoreConfigRoot + ";manager="
					+ vuzeManager);
		}

		if (vuzeManager == null) {

			// TODO: If non-cellular only:
			// - Check ConnectivityManager.getActiveNetworkInfo().getType
			// - if TYPE_MOBILE, bind to "lo" or "127.0.0.1" before core start
			// - if non TYPE_MOBILE, check what's in UP state (ex. eth0, wlan0).
			// Should not be rmnet
			// - if not rmnet, bind before core start
			// ALSO
			// on network change, do the above, restarting core (shut down and start
			// again) if bind needs to change
			// If any network (cellular or otherwise)
			// - Ensure binding is blank, restart core if needed

			NetworkState networkState = VuzeRemoteApp.getNetworkState();
			networkState.addListener(this); // triggers

			buildCustomFile();
			try {
				vuzeManager = new VuzeManager(vuzeCoreConfigRoot);
			} catch (AzureusCoreException ex) {
				Log.e(TAG, "startCore: ", ex);
				VuzeEasyTracker.getInstance(this).logError(ex,
						(core == null) ? "noCore" : "hasCore");
				if (ex.getMessage().contains("already instantiated")) {
					sendRestartServiceIntent();
				}
				return;
			}

			core = vuzeManager.getCore();

			if (VuzeManager.isShuttingDown()) {
				if (restartService) {
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "Vuze is shutting down, will restart afterwards");
					}
				} else {
					if (CorePrefs.DEBUG_CORE) {
						Log.e(TAG, "Vuze is shutting down, setting to restart");
					}
					vuzeManager = null;
					sendRestartServiceIntent();
				}
				return;
			}

			if (!AndroidUtils.DEBUG) {
				System.setOut(new PrintStream(new OutputStream() {
					public void write(int b) {
						//DO NOTHING
					}
				}));
			}

			SimpleTimer.addPeriodicEvent("Update Notification", 10000,
					new TimerEventPerformer() {
						@Override
						public void perform(TimerEvent event) {
							updateNotification();
						}
					});

			PairingManager pairingManager = PairingManagerFactory.getSingleton();
			if (pairingManager != null) {
				try {
					Field ui = pairingManager.getClass().getDeclaredField("ui");
					ui.setAccessible(true);
					ui.set(pairingManager, this);
				} catch (Throwable t) {
					if (CorePrefs.DEBUG_CORE) {
						Log.e(TAG, "startCore: ", t);
					}
				}
			}
			if (corePrefs.getPrefOnlyPluggedIn()) {
				boolean wasConnected = AndroidUtils.isPowerConnected(
						VuzeRemoteApp.getContext());
				boolean isConnected = AndroidUtils.isPowerConnected(
						VuzeRemoteApp.getContext());

				if (wasConnected != isConnected) {
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG,
								"state changed while starting up.. stop core and try again");
					}

					sendRestartServiceIntent();
					return;
				}
			}
			core.addLifecycleListener(new AzureusCoreLifecycleAdapter() {

				@Override
				public void started(AzureusCore core) {
					// not called if listener is added after core is started!
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "started: core");
					}

					coreStarted = true;
					sendStuff(MSG_OUT_CORE_STARTED, "MSG_OUT_CORE_STARTED");

					updateNotification();

					core.getGlobalManager().addListener(new GlobalManagerListener() {

						@Override
						public void downloadManagerAdded(DownloadManager dm) {
							if (lowResourceMode && !dm.getAssumedComplete()) {
								int state = dm.getState();
								if (state == DownloadManager.STATE_QUEUED
										|| state == DownloadManager.STATE_ALLOCATING
										|| state == DownloadManager.STATE_CHECKING
										|| state == DownloadManager.STATE_DOWNLOADING
										|| state == DownloadManager.STATE_INITIALIZING
										|| state == DownloadManager.STATE_INITIALIZED
										|| state == DownloadManager.STATE_READY
										|| state == DownloadManager.STATE_WAITING) {

									AERunStateHandler.setResourceMode(
											AERunStateHandler.RS_ALL_ACTIVE);
									lowResourceMode = false;

									if (CorePrefs.DEBUG_CORE) {
										Log.d(TAG,
												"downloadManagerAdded: non-stopped download; turning off low resource mode");
									}
								}
							}

						}

						@Override
						public void downloadManagerRemoved(DownloadManager dm) {

						}

						@Override
						public void destroyInitiated() {

						}

						@Override
						public void destroyed() {
						}

						@Override
						public void seedingStatusChanged(boolean seeding_only_mode,
								boolean potentially_seeding_only_mode) {
							VuzeService.this.seeding_only_mode = seeding_only_mode;
							if (CorePrefs.DEBUG_CORE) {
								Log.d(TAG, "seedingStatusChanged: " + seeding_only_mode);
							}

							if (seeding_only_mode) {
								releasePowerLock();
							} else {
								adjustPowerLock();
							}
						}
					});
				}

				@Override
				public void componentCreated(AzureusCore core,
						AzureusCoreComponent component) {
					// GlobalManager is always called, even if already created
					if (component instanceof GlobalManager) {

						if (CorePrefs.DEBUG_CORE) {
							String s = NetworkAdmin.getSingleton().getNetworkInterfacesAsString();
							Log.d(TAG, "started: " + s);
						}

					}
					if (component instanceof PluginInterface) {
						String pluginID = ((PluginInterface) component).getPluginID();
						if (CorePrefs.DEBUG_CORE) {
							Log.d(TAG, "plugin " + pluginID + " started");
						}

						if (pluginID.equals("xmwebui")) {
							webUIStarted = true;
							sendStuff(MSG_OUT_WEBUI_STARTED, "MSG_OUT_WEBUI_STARTED");
							updateNotification();
						}
					} else {
						Log.d(TAG, "component " + component.getClass().getSimpleName()
								+ " started");
					}
				}

				@Override
				public void stopped(AzureusCore core) {
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "AZCoreLifeCycle:stopped: start");
					}

					core.removeLifecycleListener(this);

					NetworkState networkState = VuzeRemoteApp.getNetworkState();
					networkState.removeListener(VuzeService.this);

					Bundle bundle = new Bundle();
					bundle.putString("data", "MSG_OUT_CORE_STOPPED");
					bundle.putBoolean("restarting", restartService);
					sendStuff(MSG_OUT_CORE_STOPPED, bundle);
					msgOutCoreStoppedCalled = true;

					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "AZCoreLifeCycle:stopped: done");
					}
				}

				@Override
				public void stopping(AzureusCore core) {
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "stopping: core");
					}

					isCoreStopping = true;

					VuzeEasyTracker.getInstance().stop();

					Bundle bundle = new Bundle();
					bundle.putString("data", "MSG_OUT_CORE_STOPPING");
					bundle.putBoolean("restarting", restartService);
					sendStuff(MSG_OUT_CORE_STOPPING, bundle);
					releasePowerLock();

					updateNotification();
				}
			});
		} else {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "startCore: vuzeManager already created");
			}
		}
	}

	@Thunk
	void updateNotification() {
		if (!allowNotificationUpdate) {
			return;
		}

//		if (CorePrefs.DEBUG_CORE) {
//			Log.d(TAG, "updateNotification " + (screenOff ? "ScreenOff" : "ScreenOn"));
//		}
		if (screenOff) {
			return;
		}
		try {
			NotificationManager mNotificationManager = (NotificationManager) getSystemService(
					Context.NOTIFICATION_SERVICE);
			Notification notification = getNotificationBuilder().build();
			mNotificationManager.notify(1, notification);
		} catch (IllegalArgumentException ignore) {
		}
	}

	public static void sendRestartServiceIntent() {
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "sendRestartServiceIntent via "
					+ AndroidUtils.getCompressedStackTrace());
		}
		Context context = VuzeRemoteApp.getContext();
		Intent intentStop = new Intent(context, VuzeService.class);
		intentStop.setAction(VuzeService.INTENT_ACTION_RESTART);
		PendingIntent piRestart = PendingIntent.getService(context, 0, intentStop,
				PendingIntent.FLAG_CANCEL_CURRENT);

		try {
			piRestart.send();
		} catch (PendingIntent.CanceledException e) {
			Log.e(TAG, "restartService", e);
		}
	}

	public void reallyRestartService() {
		if (restartService || core == null) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "restartService skipped: "
						+ AndroidUtils.getCompressedStackTrace());
			}
			return;
		}
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "restartService: " + AndroidUtils.getCompressedStackTrace());
		}
		restartService = true;
		if (vuzeManager != null) {
			AzureusCore core = vuzeManager.getCore();
			if (core != null) {
				core.stop();
			}
			vuzeManager = null;
		} else if (core != null) {
			try {
				core.stop();
			} catch (Throwable ignore) {
			}
		}
		stopSelfAndNotify();
	}

	private void stopSelfAndNotify() {
		isServiceStopping = true;
		updateNotification();
		stopSelf();
	}

	@Override
	public void onCreate() {
		super.onCreate();

		IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		screenReceiver = new ScreenReceiver();
		registerReceiver(screenReceiver, filter);
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		boolean hadStaticVar = staticVar != null;

		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "onStartCommand: " + intent);

			if ((flags & START_FLAG_RETRY) > 0) {
				if (CorePrefs.DEBUG_CORE) {
					Log.w(TAG,
							"Starting VuzeService with START_FLAG_RETRY.  Assuming restarting from 'crash' and shutting down service if "
									+ !hadStaticVar);
				}
				if (!hadStaticVar) {
					skipBind = true;
					isServiceStopping = true;
					stopSelf();
				}
				return START_NOT_STICKY;
			}
		}

		final String intentAction = intent == null ? INTENT_ACTION_START
				: intent.getAction();

		if (intentAction != null && intentAction.startsWith("com.vuze")) {
			Thread thread = new Thread(new Runnable() {
				@Override
				public void run() {
					handleStartAction(intentAction);
				}
			}, "VuzeServiceAction");
			thread.setDaemon(true);
			thread.start();
			if (!INTENT_ACTION_START.equals(intentAction)) {
				return START_NOT_STICKY;
			}
		}

		if (!hadStaticVar) {
			staticVar = new Object();
		}

		/**
		 * Things I discovered (which may be wrong):
		 *
		 * Calling startForeground causes a Service in a separate process to be
		 * linked to the rootIntent, and prevents the rootIntent from fully
		 * being destroyed.  ie. Swiping your app away on the Recent Apps List
		 * will remove it from the list, and perhaps destroy some objects, but the
		 * Application object will remain.
		 *
		 * Without startForeground, the service will be killed when the app
		 * is swiped-away, and then started up again (1000ms on my device).  This
		 * is not a graceful destroy, but a thread kill, so you don't get a onDestroy()
		 * call.  The restart call to onStartCommand will have a null intent.
		 *
		 * Since I want to gracefully shut down, the best option for me is to
		 * startForeground, and minimize memory usage of the app when the user
		 * swipes it away. The cleanup is triggered via a dedicated local service
		 * that notifies the app onTaskRemoved.
		 */
		Notification notification = getNotificationBuilder().build();
		startForeground(1, notification);

		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "onStartCommand: Start Sticky; flags=" + flags + ";startID="
					+ startId + ";"
					+ (intent == null ? "null intent" : intent.getAction() + ";"
							+ intent.getExtras() + ";" + intent.getDataString())
					+ "; hadStaticVar=" + hadStaticVar);
		}
		return (START_STICKY);
	}

	@Thunk
	void handleStartAction(String intentAction) {
		if (INTENT_ACTION_RESTART.equals(intentAction)) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onStartCommand: Restart");
			}
			reallyRestartService();
			return;
		}

		if (INTENT_ACTION_START.equals(intentAction)) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onStartCommand: Start");
			}
			startCore();
			return;
		}

		if (INTENT_ACTION_STOP.equals(intentAction)) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onStartCommand: Stop");
			}
			stopSelfAndNotify();
			stopForeground(false);
			return;
		}

		if (INTENT_ACTION_RESUME.equals(intentAction)) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onStartCommand: Resume");
			}
			if (core != null && core.isStarted()) {
				GlobalManager gm = core.getGlobalManager();
				if (gm != null) {
					gm.resumeDownloads();
					updateNotification();
				}
			}
			return;
		}

		if (INTENT_ACTION_PAUSE.equals(intentAction)) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onStartCommand: Pause");
			}
			if (core != null && core.isStarted()) {
				GlobalManager gm = core.getGlobalManager();
				if (gm != null) {
					gm.pauseDownloads();
					updateNotification();
				}
			}
			return;
		}

	}

	private NotificationCompat.Builder getNotificationBuilder() {
		Resources resources = getResources();
		final Intent notificationIntent = new Intent(this, IntentHandler.class);
		final PendingIntent pi = PendingIntent.getActivity(this, 0,
				notificationIntent, 0);

		String title = resources.getString(R.string.core_noti_title);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(
				this).setSmallIcon(R.drawable.notification_small).setContentTitle(
						title).setOngoing(true).setCategory(
								Notification.CATEGORY_SERVICE).setContentIntent(pi).setPriority(
										Notification.PRIORITY_LOW);

		if (!isCoreStopping && !isServiceStopping) {
			Intent intentStop = new Intent(this, VuzeService.class);
			intentStop.setAction(INTENT_ACTION_STOP);
			PendingIntent piStop = PendingIntent.getService(this, 0, intentStop,
					PendingIntent.FLAG_CANCEL_CURRENT);

			builder.addAction(R.drawable.ic_power_settings_new_white_24dp,
					resources.getString(R.string.core_noti_stop_button), piStop);

			if (core != null && core.isStarted()) {
				GlobalManager gm = core.getGlobalManager();
				if (gm != null) {
					boolean canPause = gm.canPauseDownloads();
					Intent intentPR = new Intent(this, VuzeService.class);
					intentPR.setAction(
							canPause ? INTENT_ACTION_PAUSE : INTENT_ACTION_RESUME);
					PendingIntent piPR = PendingIntent.getService(this, 0, intentPR,
							PendingIntent.FLAG_CANCEL_CURRENT);

					builder.addAction(
							canPause ? R.drawable.ic_playlist_pause_n
									: R.drawable.ic_playlist_play_white_n,
							resources.getString(canPause ? R.string.core_noti_pause_button
									: R.string.core_noti_resume_button),
							piPR);
				}
			}

			if (CorePrefs.DEBUG_CORE) {
				Intent intentRestart = new Intent(this, VuzeService.class);
				intentRestart.setAction(INTENT_ACTION_RESTART);
				PendingIntent piRestart = PendingIntent.getService(this, 0,
						intentRestart, PendingIntent.FLAG_CANCEL_CURRENT);
				builder.addAction(R.drawable.ic_refresh_white_24dp, "Restart",
						piRestart);
			}

		}

		String subTitle;
		if (isCoreStopping || isServiceStopping) {
			int id = restartService ? R.string.core_noti_restarting
					: R.string.core_noti_stopping;
			subTitle = resources.getString(id);
		} else {
			if (bindToLocalHost) {
				subTitle = resources.getString(bindToLocalHostReasonID);
			} else {
				GlobalManagerStats stats = null;
				if (core != null && core.isStarted()) {
					GlobalManager gm = core.getGlobalManager();
					if (gm != null) {
						stats = gm.getStats();
					}
				}

				if (stats != null) {
					String downSpeed = DisplayFormatters.formatByteCountToKiBEtcPerSec(
							stats.getDataAndProtocolReceiveRate());
					String upSpeed = DisplayFormatters.formatByteCountToKiBEtcPerSec(
							stats.getDataAndProtocolSendRate());
					TagManager tagManager = TagManagerFactory.getTagManager();
					Tag tagActive = tagManager.getTagType(
							TagType.TT_DOWNLOAD_STATE).getTag(7);// active
					int numActive = tagActive == null ? 0 : tagActive.getTaggedCount();
					subTitle = resources.getQuantityString(R.plurals.core_noti_running,
							numActive, downSpeed, upSpeed,
							DisplayFormatters.formatNumber(numActive));
				} else {
					subTitle = resources.getString(R.string.core_noti_starting);
				}

			}
		}
		builder.setContentText(subTitle).setShowWhen(false);

		return builder;
	}

	@Override
	public boolean stopService(Intent name) {
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "stopService: " + AndroidUtils.getCompressedStackTrace());
		}
		return super.stopService(name);
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "onTaskRemoved: " + rootIntent);
		}
		// Case: User uses task manager to close app (swipe right)
		// App doesn't get notified, but this service does if the app
		// started this service
		for (int i = mClients.size() - 1; i >= 0; i--) {
			Messenger messenger = mClients.get(i);
			if (!messenger.getBinder().isBinderAlive()) {
				if (CorePrefs.DEBUG_CORE) {
					Log.d(TAG, "onTaskRemoved: removing dead binding #" + i);
				}
				mClients.remove(i);
			} else if (!messenger.getBinder().pingBinder()) {
				if (CorePrefs.DEBUG_CORE) {
					Log.d(TAG, "onTaskRemoved: removing dead-ping binding #" + i);
				}
			}
		}
	}

	@Override
	public void onDestroy() {
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "onDestroy: " + AndroidUtils.getCompressedStackTrace());
		}

		super.onDestroy();
		NetworkState networkState = VuzeRemoteApp.getNetworkState();
		networkState.removeListener(this);

		if (screenReceiver != null) {
			unregisterReceiver(screenReceiver);
		}

		boolean hadVuzeManager = vuzeManager != null;
		if (hadVuzeManager) {
			AzureusCore core = vuzeManager.getCore();
			vuzeManager = null;
			// Hopefully in most cases, core is already stopping, so the
			// likelyhood of core stopping before onDestroy is done is probably low
			if (core != null && !isCoreStopping) {
				core.stop();
			}
		}

		if (!msgOutCoreStoppedCalled) {
			Bundle bundle = new Bundle();
			bundle.putString("data", "MSG_OUT_CORE_STOPPED");
			bundle.putBoolean("restarting", restartService);
			sendStuff(MSG_OUT_CORE_STOPPED, bundle);
		}

		Bundle bundle = new Bundle();
		bundle.putString("data", "MSG_OUT_SERVICE_DESTROY");
		bundle.putBoolean("restarting", restartService);
		sendStuff(MSG_OUT_SERVICE_DESTROY, bundle);

		allowNotificationUpdate = false;
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(
				Context.NOTIFICATION_SERVICE);
		mNotificationManager.cancel(1);

		staticVar = null;

		if (restartService) {
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onDestroy: Restarting");
			}

			Intent intent = new Intent(this, VuzeService.class);
			if (coreStarted) {
				intent.setAction(INTENT_ACTION_START);
			}
			PendingIntent pendingIntent = PendingIntent.getService(this, 1, intent,
					PendingIntent.FLAG_ONE_SHOT);
			AlarmManager alarmManager = (AlarmManager) getSystemService(
					Context.ALARM_SERVICE);
			alarmManager.set(AlarmManager.RTC_WAKEUP,
					SystemClock.elapsedRealtime() + 500, pendingIntent);
			//	startService(intent);

			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onDestroy: kill old service thread");
			}
		}

		if (hadVuzeManager) {
			System.exit(0);
		}
	}

	@Override
	public void initialise(PluginInterface pi, BooleanParameter icon_enable) {
	}

	@Override
	public void recordRequest(String name, String ip, boolean good) {
	}

	private boolean isDataFlowing() {
		if (core == null) {
			return false;
		}
		GlobalManagerStats stats = core.getGlobalManager().getStats();
		int dataSendRate = stats.getDataSendRate();
		int dataReceiveRate = stats.getDataReceiveRate();
		if (dataSendRate > 0 || dataReceiveRate > 0) {
			// data flowing, no need to check smooth rate
			return true;
		}
		long smoothedReceiveRate = stats.getSmoothedReceiveRate();
		long smoothedSendRate = stats.getSmoothedSendRate();
		return smoothedReceiveRate > 1024 && smoothedSendRate > 1024;
	}

	@Override
	public char[] getSRPPassword() {
		return null;
	}

	@Override
	public void onlineStateChanged(boolean isOnline, boolean isOnlineMobile) {
		// delay restarting core due to loss of internet, in case the internet
		// comes back.  For example, our chromebook, when shut with wifi access,
		// will send a disconnect upon being opened, followed almost immediately
		// by a connect.
		if (!isOnline && lastOnline != null && lastOnline != isOnline) {
			Handler handler = new Handler();
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					NetworkState networkState = VuzeRemoteApp.getNetworkState();
					onlineStateChangedNoDelay(networkState.isOnline(),
							networkState.isOnlineMobile());
				}
			}, 10000);
			return;
		}
		onlineStateChangedNoDelay(isOnline, isOnlineMobile);
	}

	public void onlineStateChangedNoDelay(boolean isOnline,
			boolean isOnlineMobile) {
		boolean requireRestart = false;

		if (lastOnline == null) {
			lastOnline = isOnline;
		} else if (lastOnline != isOnline) {
			lastOnline = isOnline;
			requireRestart = true;
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "onlineStateChanged: isOnline changed");
			}
		}

		if (!corePrefs.getPrefAllowCellData()) {
			if (lastOnlineMobile == null) {
				lastOnlineMobile = isOnlineMobile;
			} else if (lastOnlineMobile != isOnlineMobile) {
				lastOnlineMobile = isOnlineMobile;
				requireRestart = true;
				if (CorePrefs.DEBUG_CORE) {
					Log.d(TAG, "onlineStateChanged: isOnlineMobile changed");
				}
			}
		}

		if (requireRestart && wouldBindToLocalHost(corePrefs) != bindToLocalHost) {
			sendRestartServiceIntent();
		}
	}

	public void checkForSleepModeChange() {
		if (wouldBindToLocalHost(corePrefs) != bindToLocalHost) {
			sendRestartServiceIntent();
		}
	}

	private void acquirePowerLock() {
		if (wifiLock == null || !wifiLock.isHeld()) {
			if (!AndroidUtils.hasPermisssion(VuzeRemoteApp.getContext(),
					Manifest.permission.WAKE_LOCK)) {
				if (CorePrefs.DEBUG_CORE) {
					Log.d(TAG, "No Permissions to access wake lock");
				}
				return;
			}
			WifiManager wifiManager = (WifiManager) VuzeRemoteApp.getContext().getSystemService(
					Context.WIFI_SERVICE);
			wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL,
					WIFI_LOCK_TAG);
			wifiLock.acquire();
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "Wifi lock acquired");
			}

		}
	}

	public void releasePowerLock() {
		if (wifiLock != null && wifiLock.isHeld()) {
			wifiLock.release();
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "Wifi lock released");
			}

		}
	}

	public void adjustPowerLock() {
		if (corePrefs.getPrefDisableSleep()) {
			acquirePowerLock();
		} else {
			releasePowerLock();
		}
	}

	private void disableBatteryMonitoring(Context context) {
		if (batteryReceiver != null) {
			context.unregisterReceiver(batteryReceiver);
			batteryReceiver = null;
			if (CorePrefs.DEBUG_CORE) {
				Log.d(TAG, "disableBatteryMonitoring: ");
			}

		}
	}

	private void enableBatteryMonitoring(Context context) {
		if (batteryReceiver != null) {
			return;
		}
		IntentFilter intentFilterConnected = new IntentFilter(
				Intent.ACTION_POWER_CONNECTED);
		IntentFilter intentFilterDisconnected = new IntentFilter(
				Intent.ACTION_POWER_DISCONNECTED);

		batteryReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				boolean isConnected = intent.getAction().equals(
						Intent.ACTION_POWER_CONNECTED);
				if (CorePrefs.DEBUG_CORE) {
					Log.d(TAG, "Battery connected? " + isConnected);
				}
				AzureusCore core = VuzeService.this.core;
				if (core == null) {
					if (CorePrefs.DEBUG_CORE) {
						Log.d(TAG, "Battery changed, but core not initialized yet");
					}

					return;
				}
				if (corePrefs.getPrefOnlyPluggedIn()) {
					checkForSleepModeChange();
				}
			}
		};
		context.registerReceiver(batteryReceiver, intentFilterConnected);
		context.registerReceiver(batteryReceiver, intentFilterDisconnected);
		if (CorePrefs.DEBUG_CORE) {
			Log.d(TAG, "enableBatteryMonitoring: ");
		}

	}

}
