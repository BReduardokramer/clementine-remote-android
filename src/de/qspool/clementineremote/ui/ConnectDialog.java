/* This file is part of the Android Clementine Remote.
 * Copyright (C) 2013, Andreas Muttscheller <asfa194@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package de.qspool.clementineremote.ui;

import javax.jmdns.ServiceInfo;

import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;

import de.qspool.clementineremote.App;
import de.qspool.clementineremote.R;
import de.qspool.clementineremote.backend.Clementine;
import de.qspool.clementineremote.backend.ClementineService;
import de.qspool.clementineremote.backend.elements.Disconnected;
import de.qspool.clementineremote.backend.elements.Disconnected.DisconnectReason;
import de.qspool.clementineremote.backend.mdns.ClementineMDnsDiscovery;
import de.qspool.clementineremote.backend.requests.RequestConnect;
import de.qspool.clementineremote.backend.requests.RequestDisconnect;
import de.qspool.clementineremote.ui.adapter.CustomClementinesAdapter;
import de.qspool.clementineremote.utils.Utilities;

/**
 * The connect dialog
 */
public class ConnectDialog extends SherlockActivity {
	private final static int ANIMATION_DURATION = 2000;
	
	private final int ID_PLAYER_DIALOG = 1;
	private final int ID_SETTINGS = 2;
	public final static int RESULT_CONNECT = 1;
	public final static int RESULT_DISCONNECT = 2;
	public final static int RESULT_RESTART = 3;
	
	private Button mBtnConnect;
	private ImageButton mBtnClementine;
	private EditText mEtIp;
	
	ProgressDialog mPdConnect;
	
	private SharedPreferences mSharedPref;
    private ConnectDialogHandler mHandler = new ConnectDialogHandler(this);
    private int mAuthCode = 0;
    
    private ClementineMDnsDiscovery mClementineMDns;
    private AlphaAnimation mAlphaDown; 
    private AlphaAnimation mAlphaUp;
    private boolean mAnimationCancel;
    private boolean mFirstCall;
    
    private Intent mServiceIntent;
    private boolean doAutoConnect = true;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
	    super.onCreate(savedInstanceState);
	    
	    App.mApp = getApplication();
    	
	    setContentView(R.layout.connectdialog);
	    
	    getSupportActionBar().setDisplayHomeAsUpEnabled(false);
	    
	    // Create a progress dialog
	    mPdConnect = new ProgressDialog(this);
	    mPdConnect.setCancelable(true);
	    mPdConnect.setOnCancelListener(oclProgressDialog);
	    
	    initializeUi();
	    
	    // When we have a download notifitication and it was clicked, cancel the download.
	    // We have to do it here because otherwise the intent stack will be wrong. This dialog
	    // would not shown when disconnecting
		if (getIntent().hasExtra(App.NOTIFICATION_ID)) {
			int i = getIntent().getIntExtra(App.NOTIFICATION_ID, -1);
			// Check if the asynctask is running
			if (i != -1
			 && App.downloaders != null
			 && App.downloaders.get(i) != null
			 && App.downloaders.get(i).getStatus() == AsyncTask.Status.RUNNING) {
				App.downloaders.get(i).cancel(true);
			}
		}
		
		// Check if we got a stack trace
		CrashReportDialog crashReportDialog = new CrashReportDialog(this);
		crashReportDialog.showDialogIfTraceExists();
	}
	
	@Override 
	public void onResume() {
		super.onResume();
		
		// Check if we are currently connected, then open the player dialog
		if (!mPdConnect.isShowing() && App.mClementine.isConnected()) {
			showPlayerDialog();
			return;
		}
		
		// Start the background service
		startBackgroundService();
		
	    mFirstCall = doAutoConnect;
	    
	    // mDNS Discovery
    	mClementineMDns = new ClementineMDnsDiscovery(mHandler);
	    
	    // Check if Autoconnect is enabled
	    if (mSharedPref.getBoolean(App.SP_KEY_AC, false) && doAutoConnect) {
	    	// Post delayed, so the service has time to start
	    	mHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					connect();
				}
	    	}, 250);
	    	
	    } else {
	    	mClementineMDns.discoverServices();
		}
	    doAutoConnect = true;
	    
	    // Remove still active notifications
	    NotificationManager mNotificationManager = (NotificationManager) App.mApp.getSystemService(Context.NOTIFICATION_SERVICE);
	    mNotificationManager.cancel(App.NOTIFY_ID);
	}
	
	@Override
	public void onConfigurationChanged(Configuration newConfig)
	{
	    super.onConfigurationChanged(newConfig);
	    setContentView(R.layout.connectdialog);
	    
	    initializeUi();
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
	    switch (item.getItemId()) {
	    case R.id.settings:		
	    	Intent settingsIntent = new Intent(this, ClementineSettings.class);
			startActivity(settingsIntent);
			doAutoConnect = false;
	        return true;
	    default:
	        return super.onOptionsItemSelected(item);
	    }
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inf = getSupportMenuInflater();
		inf.inflate(R.menu.connectdialog_menu, menu);
		
		return true;
	}
	
	/**
	 * Start the background service
	 */
	private void startBackgroundService() {
		// Start the background service
		mServiceIntent = new Intent(this, ClementineService.class);
    	mServiceIntent.putExtra(App.SERVICE_ID, App.SERVICE_START);
    	startService(mServiceIntent);
	}
	
	private void initializeUi() {
	    // Get the Layoutelements
	    mBtnConnect = (Button) findViewById(R.id.btnConnect);
	    mBtnConnect.setOnClickListener(oclConnect);
	    mBtnConnect.requestFocus();
	    
	    mBtnClementine = (ImageButton) findViewById(R.id.btnClementineIcon);
	    mBtnClementine.setOnClickListener(oclClementine);
	    
	    // Setup the animation for the Clementine icon
	    mAlphaDown = new AlphaAnimation(1.0f, 0.3f);
	    mAlphaUp = new AlphaAnimation(0.3f, 1.0f);
	    mAlphaDown.setDuration(ANIMATION_DURATION);
	    mAlphaUp.setDuration(ANIMATION_DURATION);
	    mAlphaDown.setFillAfter(true);
	    mAlphaUp.setFillAfter(true);
	    mAlphaUp.setAnimationListener(mAnimationListener);
	    mAlphaDown.setAnimationListener(mAnimationListener);
	    mAnimationCancel = false;
	    
	    // Ip and Autoconnect
	    mEtIp = (EditText) findViewById(R.id.etIp);
	    mEtIp.setRawInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
	    
	    // Get old ip and auto-connect from shared prefences
	    mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);
	    mEtIp.setText(mSharedPref.getString(App.SP_KEY_IP, ""));
	    mEtIp.setSelection(mEtIp.length());
	    
	    // Get the last auth code
	    mAuthCode = mSharedPref.getInt(App.SP_LAST_AUTH_CODE, 0);
	    
	    // First time called? Show an info screen
	    if (!mSharedPref.getBoolean(App.SP_FIRST_TIME, false)) {
	    	// Save for next calls
	    	Editor edit = mSharedPref.edit();
	    	edit.putBoolean(App.SP_FIRST_TIME, true);
	    	edit.commit();
	    	
	    	// Show the info screen
	    	showFirstTimeScreen();
	    }
	}

	private OnClickListener oclConnect = new OnClickListener() {
		
		@Override
		public void onClick(View v) {
			// And connect
			connect();
		}
	};
	
	private OnClickListener oclClementine = new OnClickListener() {

		@Override
		public void onClick(View v) {
			// Only when we have Jelly Bean or higher
			if (!mClementineMDns.getServices().isEmpty()) {
				mAnimationCancel = true;
				final Dialog listDialog = new Dialog(ConnectDialog.this, R.style.Dialog_Transparent);
				listDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
				listDialog.setContentView(R.layout.dialog_list);
				
				// Set the title
				TextView tvTitle = (TextView) listDialog.findViewById(R.id.tvListTitle);
				tvTitle.setText(R.string.connectdialog_services);
				
				// Set the close button
				Button closeButton = (Button) listDialog.findViewById(R.id.btnListClose);
				closeButton.setOnClickListener(new OnClickListener() {
					@Override
					public void onClick(View v) {
						listDialog.dismiss();
					}
			    });
				
				// Set the list adapter
				ListView listView = (ListView) listDialog.findViewById(R.id.lvClementines);
				CustomClementinesAdapter adapter = new CustomClementinesAdapter(ConnectDialog.this,
						R.layout.dialog_list_item, mClementineMDns.getServices());
				listView.setAdapter(adapter);
				listView.setOnItemClickListener(new OnItemClickListener() {
					@Override
					public void onItemClick(AdapterView<?> parent, View view,
							int position, long id) {
						listDialog.dismiss();
						ServiceInfo service = mClementineMDns.getServices().get(position);
						// Insert the host
						String ip = service.getInet4Addresses()[0].toString().split("/")[1];
						mEtIp.setText(ip);
						
						// Update the port
						SharedPreferences.Editor editor = mSharedPref.edit();
						editor.putString(App.SP_KEY_PORT, 
								String.valueOf(service.getPort()));
						editor.commit();
						connect();
					}
				});
				
				listDialog.show();
			}
		}
	};
	
	private OnCancelListener oclProgressDialog = new OnCancelListener() {
		@Override
		public void onCancel(DialogInterface dialog) {
			// Create a new request
			RequestDisconnect r = new RequestDisconnect();
			
			// Move the request to the message
			Message msg = Message.obtain();
			msg.obj = r;
			
			// Send the request to the thread
			App.mClementineConnection.mHandler.sendMessage(msg);
		}
		
	};
	
	/**
	 * Connect to clementine
	 */
	private void connect() {
		// Do not connect if the activity has finished!
		if (this.isFinishing())
			return;
		
		// Check if the service is running. If not, then restart it and call
		// this function later!
		if (App.mClementineConnection == null) {
			startBackgroundService();
			mHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					connect();
				}
	    	}, 250);
			return;
		}
		
		// Save the data
		SharedPreferences.Editor editor = mSharedPref.edit();
		editor.putString(App.SP_KEY_IP, mEtIp.getText().toString());
		editor.putInt(App.SP_LAST_AUTH_CODE, mAuthCode);
		editor.commit();
		
    	// Set the handler
	    App.mClementineConnection.setUiHandler(mHandler);
	    
	    mPdConnect.setMessage(getString(R.string.connectdialog_connecting));
		mPdConnect.show();
		
		// Get the port to connect to
		int port;
		try {
			port = Integer.valueOf(mSharedPref.getString(App.SP_KEY_PORT, String.valueOf(Clementine.DefaultPort)));			
		} catch (NumberFormatException e) {
			port = Clementine.DefaultPort;
		}
		
					
		// Create a new connect request
		RequestConnect r = new RequestConnect(mEtIp.getText().toString(), port, mAuthCode, true, false);
		
		// Move the request to the message
		Message msg = Message.obtain();
		msg.obj = r;
		
		// Send the request to the thread
		App.mClementineConnection.mHandler.sendMessage(msg);
	}
	
	/**
	 * Show the user the dialog to enter the auth code
	 */
	void showAuthCodePromt() {
		final Dialog authCodeDialog = new Dialog(this, R.style.Dialog_Transparent);
		authCodeDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		authCodeDialog.setContentView(R.layout.dialog_auth_code);
		
		// Set the Views
		Button connectButton = (Button) authCodeDialog.findViewById(R.id.btnConnectAuth);
		final EditText etAuthCode = (EditText) authCodeDialog.findViewById(R.id.etAuthCode);
		connectButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				try {
					mAuthCode = Integer.parseInt(etAuthCode.getText().toString());
					authCodeDialog.dismiss();
	        	    connect();
				} catch (NumberFormatException e) {
					Toast.makeText(ConnectDialog.this, R.string.invalid_code, Toast.LENGTH_SHORT).show();
				}
			}
	    });
		// Show the keyboard directly
		authCodeDialog.getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		authCodeDialog.show();
	}
	
	/**
	 * Show the user the first time called dialog
	 */
	private void showFirstTimeScreen() {
		Utilities.ShowHtmlMessageDialog(this, R.string.first_time_title, R.string.first_time_text);
	}
	
	/**
	 * We connected to clementine successfully. Now open other view
	 */
	void showPlayerDialog() {
		if (mClementineMDns != null) {
			mClementineMDns.stopServiceDiscovery();
		}
		
		// Start the player dialog
		Intent playerDialog = new Intent(this, Player.class);
    	playerDialog.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    	startActivityForResult(playerDialog, ID_PLAYER_DIALOG);
	}
	
	/**
	 * We couldn't connect to clementine. Inform the user
	 */
	void noConnection() {
		// Do not display dialog if the activity has finished!
		if (this.isFinishing())
			return;
		
		// Check if we have not a local ip
		WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		WifiInfo wifiInfo = wifiManager.getConnectionInfo();
		int ip = wifiInfo.getIpAddress();
		
		// Get the current wifi state
		ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		
		if (!networkInfo.isConnected()) {
			Utilities.ShowMessageDialog(this, R.string.connectdialog_error, R.string.wifi_disabled);
		} else if (!Utilities.ToInetAddress(ip).isSiteLocalAddress()) {
			Utilities.ShowMessageDialog(this, R.string.connectdialog_error, R.string.no_private_ip);
		} else {
			Utilities.ShowMessageDialog(this, R.string.connectdialog_error, R.string.check_ip);
		}
	}
	
	/**
	 * We have an old Proto version. User has to update Clementine
	 */
	void oldProtoVersion() {
		Utilities.ShowMessageDialog(this, R.string.error_versions, R.string.old_proto);
	}
	
	/**
	 * Clementine closed the connection
	 * @param disconnected The object to work with
	 */
	void disconnected(Disconnected disconnected) {
		// Restart the background service
		mServiceIntent = new Intent(this, ClementineService.class);
    	mServiceIntent.putExtra(App.SERVICE_ID, App.SERVICE_START);
    	startService(mServiceIntent);
    	
		if (disconnected.getReason() == DisconnectReason.WRONG_AUTH_CODE) {
			showAuthCodePromt();
		}
	}
	
	 @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    	if (requestCode == ID_PLAYER_DIALOG) {
    		if (resultCode == Activity.RESULT_CANCELED) {
    			finish();
    		} else {
    			doAutoConnect = false;
    		}
    	} else if (requestCode == ID_SETTINGS) {
    		doAutoConnect = false;
    	}
    }
	
	/**
	 * A service was found. Now show a toast and animate the icon
	 */
	void serviceFound() {
		if (mClementineMDns.getServices().isEmpty()) {
			mBtnClementine.clearAnimation();
		} else {
			// Start the animation
			mBtnClementine.startAnimation(mAlphaDown);
		}
		
		// On the first call show a toast that we found a host
        if (mFirstCall) {
        	mFirstCall = false;
        	Toast.makeText(this, R.string.connectdialog_mdns_found, Toast.LENGTH_LONG).show();
        }
	}
	
	private AnimationListener mAnimationListener = new AnimationListener() {
		@Override
		public void onAnimationEnd(Animation animation) {
			if (!mAnimationCancel) {
				if (animation.equals(mAlphaDown)) {
					mBtnClementine.startAnimation(mAlphaUp);
				} else {
					mBtnClementine.startAnimation(mAlphaDown);
				}
			} else {
				mBtnClementine.clearAnimation();
			}
		}

		@Override
		public void onAnimationRepeat(Animation animation) {
		}

		@Override
		public void onAnimationStart(Animation animation) {
		}

	};
}
