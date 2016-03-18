/**
 * Copyright (C) Azureus Software, Inc, All Rights Reserved.
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

package com.vuze.android.remote.adapter;

import android.content.res.Resources;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.ViewPager;

import com.astuetz.PagerSlidingTabStrip;
import com.vuze.android.remote.*;
import com.vuze.android.remote.fragment.*;

public class TorrentDetailsPagerAdapter
	extends TorrentPagerAdapter
	implements SessionSettingsChangedListener
{
	private final PagerSlidingTabStrip tabs;

	private SessionInfo sessionInfo;

	int count = 4;

	public TorrentDetailsPagerAdapter(FragmentManager fm, ViewPager pager,
			PagerSlidingTabStrip tabs) {
		super(fm);
		this.tabs = tabs;
		count = 4;
		if (pager.getContext() instanceof SessionInfoGetter) {
			SessionInfoGetter getter = (SessionInfoGetter) pager.getContext();
			sessionInfo = getter.getSessionInfo();
		}
		init(fm, pager, tabs);
	}

	@Override
	public void onResume() {
		super.onResume();

		if (sessionInfo != null) {
			sessionInfo.addSessionSettingsChangedListeners(this);
		}
	}

	@Override
	public void sessionSettingsChanged(SessionSettings newSessionSettings) {
		int newCount = sessionInfo.getSupportsTags() ? 4 : 3;
		if (newCount != count) {
			count = newCount;
			notifyDataSetChanged();
			tabs.notifyDataSetChanged();
		}
	}

	@Override
	public void speedChanged(long downloadSpeed, long uploadSpeed) {

	}

	@Override
	public void onPause() {
		super.onPause();

		if (sessionInfo != null) {
			sessionInfo.removeSessionSettingsChangedListeners(this);
		}
	}

	/* (non-Javadoc)
		 * @see com.vuze.android.remote.adapter.TorrentPagerAdapter#createItem(int)
		 */
	@Override
	public Fragment createItem(int position) {
		Fragment fragment;
		switch (position) {
			case 3:
				fragment = new TorrentTagsFragment();
				break;
			case 2:
				fragment = new PeersFragment();
				break;
			case 1:
				fragment = new TorrentInfoFragment();
				break;
			default:
				fragment = new FilesFragment();
		}

		return fragment;
	}

	@Override
	public int getCount() {
		return count;
	}

	@Override
	public CharSequence getPageTitle(int position) {
		Resources resources = VuzeRemoteApp.getContext().getResources();
		switch (position) {
			case 0:
				return resources.getText(R.string.details_tab_files);

			case 2:
				return resources.getText(R.string.details_tab_peers);

			case 3:
				return resources.getText(R.string.details_tab_tags);

			case 1:
				return resources.getText(R.string.details_tab_info);
		}
		return super.getPageTitle(position);
	}

}
