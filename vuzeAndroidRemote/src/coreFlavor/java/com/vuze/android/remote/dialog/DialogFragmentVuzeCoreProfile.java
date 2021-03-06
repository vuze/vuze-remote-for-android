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

package com.vuze.android.remote.dialog;

import java.util.ArrayList;

import com.vuze.android.remote.*;
import com.vuze.android.remote.AndroidUtilsUI.AlertDialogBuilder;
import com.vuze.android.remote.dialog.DialogFragmentGenericRemoteProfile.GenericRemoteProfileListener;
import com.vuze.android.remote.session.RemoteProfile;
import com.vuze.util.JSONUtils;
import com.vuze.util.Thunk;

import android.Manifest;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;

public class DialogFragmentVuzeCoreProfile
	extends DialogFragment
{

	private static final String TAG = "VuzeProfileEdit";

	private GenericRemoteProfileListener mListener;

	private RemoteProfile remoteProfile;

	private EditText textNick;

	private CompoundButton switchCoreStartup;

	private CompoundButton switchCoreAllowCellData;

	private CompoundButton switchCoreDisableSleep;

	private CompoundButton switchCoreOnlyPluggedIn;

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {

		Bundle arguments = getArguments();

		String remoteAsJSON = arguments == null ? null
				: arguments.getString(RemoteUtils.KEY_REMOTE_JSON);
		if (remoteAsJSON != null) {
			try {
				remoteProfile = new RemoteProfile(JSONUtils.decodeJSON(remoteAsJSON));
			} catch (Exception e) {
				throw new IllegalStateException("No profile");
			}
		} else {
			throw new IllegalStateException("No remote.json");
		}

		AlertDialogBuilder alertDialogBuilder = AndroidUtilsUI.createAlertDialogBuilder(
				getActivity(), R.layout.dialog_vuze_core_preferences);

		Builder builder = alertDialogBuilder.builder;

		// Add action buttons
		builder.setPositiveButton(android.R.string.ok,
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						saveAndClose();
					}
				});
		builder.setNegativeButton(android.R.string.cancel,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						DialogFragmentVuzeCoreProfile.this.getDialog().cancel();
					}
				});

		final View view = alertDialogBuilder.view;

		textNick = (EditText) view.findViewById(R.id.profile_nick);
		textNick.setText(remoteProfile.getNick());

		AppPreferences appPreferences = VuzeRemoteApp.getAppPreferences();
		boolean alreadyExists = appPreferences.remoteExists(remoteProfile.getID());

		CorePrefs corePrefs = new CorePrefs();
		switchCoreStartup = (CompoundButton) view.findViewById(
				R.id.profile_core_startup);
		Boolean prefAutoStart = !alreadyExists ? true
				: corePrefs.getPrefAutoStart();
		switchCoreStartup.setChecked(prefAutoStart);

		switchCoreAllowCellData = (CompoundButton) view.findViewById(
				R.id.profile_core_allowcelldata);
		switchCoreAllowCellData.setVisibility(
				VuzeRemoteApp.getNetworkState().hasMobileDataCapability() ? View.VISIBLE
						: View.GONE);
		switchCoreAllowCellData.setChecked(corePrefs.getPrefAllowCellData());

		switchCoreDisableSleep = (CompoundButton) view.findViewById(
				R.id.profile_core_disablesleep);
		switchCoreDisableSleep.setVisibility(
				getContext().getPackageManager().hasSystemFeature(
						PackageManager.FEATURE_WIFI)
						&& AndroidUtils.hasPermisssion(VuzeRemoteApp.getContext(),
								Manifest.permission.WAKE_LOCK) ? View.VISIBLE : View.GONE);
		switchCoreDisableSleep.setChecked(corePrefs.getPrefDisableSleep());

		switchCoreOnlyPluggedIn = (CompoundButton) view.findViewById(
				R.id.profile_core_onlypluggedin);
		// Would be nice to have a way to detect a device that doesn't have a battery
		// Unfortunately, TV's return present=true for BatteryManager, and no
		// flag to indicate no-battery (plugged=BATTERY_PLUGGED_AC can be a
		// device temporarily attached to wall USB charger)
		switchCoreOnlyPluggedIn.setVisibility(
				AndroidUtils.isTV() ? View.GONE : View.VISIBLE);
		switchCoreOnlyPluggedIn.setChecked(corePrefs.getPrefOnlyPluggedIn());

		return builder.create();
	}

	@Override
	public void onAttach(Context activity) {
		super.onAttach(activity);

		if (activity instanceof GenericRemoteProfileListener) {
			mListener = (GenericRemoteProfileListener) activity;
		}
	}

	@Thunk
	void saveAndClose() {

		remoteProfile.setNick(textNick.getText().toString());

		AppPreferences appPreferences = VuzeRemoteApp.getAppPreferences();
		appPreferences.addRemoteProfile(remoteProfile);

		SharedPreferences sharedPreferences = appPreferences.getSharedPreferences();
		SharedPreferences.Editor edit = sharedPreferences.edit();

		ArrayList<String> permissionsNeeded = new ArrayList<>(4);
		permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

		if (switchCoreStartup.getVisibility() == View.VISIBLE) {
			boolean b = switchCoreStartup.isChecked();
			edit.putBoolean(CorePrefs.PREF_CORE_AUTOSTART, b);
			if (b) {
				permissionsNeeded.add(Manifest.permission.RECEIVE_BOOT_COMPLETED);
			}
		}
		if (switchCoreDisableSleep.getVisibility() == View.VISIBLE) {
			boolean b = switchCoreDisableSleep.isChecked();
			edit.putBoolean(CorePrefs.PREF_CORE_DISABLESLEEP, b);
			if (b) {
				permissionsNeeded.add(Manifest.permission.WAKE_LOCK);
			}
		}
		if (switchCoreAllowCellData.getVisibility() == View.VISIBLE) {
			boolean b = switchCoreAllowCellData.isChecked();
			edit.putBoolean(CorePrefs.PREF_CORE_ALLOWCELLDATA, b);
		}
		if (switchCoreOnlyPluggedIn.getVisibility() == View.VISIBLE) {
			boolean b = switchCoreOnlyPluggedIn.isChecked();
			edit.putBoolean(CorePrefs.PREF_CORE_ONLYPLUGGEDIN, b);
		}
		edit.commit();

		if (permissionsNeeded.size() > 0) {
			AndroidUtilsUI.requestPermissions(getActivity(),
					permissionsNeeded.toArray(new String[permissionsNeeded.size()]), null,
					null);
		}

		if (mListener != null) {
			mListener.profileEditDone(remoteProfile, remoteProfile);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		VuzeEasyTracker.getInstance(this).fragmentStart(this, TAG);
	}

	@Override
	public void onStop() {
		super.onStop();
		VuzeEasyTracker.getInstance(this).fragmentStop(this);
	}
}
