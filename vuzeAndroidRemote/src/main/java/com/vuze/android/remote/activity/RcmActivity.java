/**
 * Copyright (C) Azureus Software, Inc, All Rights Reserved.
 * <p/>
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

package com.vuze.android.remote.activity;

import java.util.*;

import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;
import com.vuze.android.FlexibleRecyclerView;
import com.vuze.android.remote.*;
import com.vuze.android.remote.SessionInfo.RpcExecuter;
import com.vuze.android.remote.adapter.RcmAdapter;
import com.vuze.android.remote.adapter.RcmAdapterFilter;
import com.vuze.android.remote.adapter.SideSortAdapter;
import com.vuze.android.remote.dialog.*;
import com.vuze.android.remote.dialog.DialogFragmentRcmAuth.DialogFragmentRcmAuthListener;
import com.vuze.android.remote.rpc.ReplyMapReceivedListener;
import com.vuze.android.remote.rpc.TransmissionRPC;
import com.vuze.android.remote.spanbubbles.SpanBubbles;
import com.vuze.android.remote.spanbubbles.SpanTags;
import com.vuze.android.widget.PreCachingLayoutManager;
import com.vuze.android.widget.SwipeRefreshLayoutExtra;
import com.vuze.util.DisplayFormatters;
import com.vuze.util.JSONUtils;
import com.vuze.util.MapUtils;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.*;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.*;
import android.widget.TextView;

/**
 * Swarm Discoveries activity.
 */
public class RcmActivity
	extends DrawerActivity
	implements RefreshTriggerListener, DialogFragmentRcmAuthListener,
	SwipeRefreshLayoutExtra.OnExtraViewVisibilityChangeListener,
	SideListHelper.SideSortAPI, DialogFragmentDateRange.DateRangeDialogListener,
	DialogFragmentSizeRange.SizeRangeDialogListener,
	DialogFragmentNumberPicker.NumberPickerDialogListener
{

	@SuppressWarnings("hiding")
	static final String TAG = "RCM";

	private static final String ID_SORT_FILTER = "-rcm";

	public static final String FILTER_PREF_LAST_SEEN = ID_SORT_FILTER
			+ "-lastSeen";

	public static final String FILTER_PREF_MINRANK = ID_SORT_FILTER + "-minRank";

	public static final String FILTER_PREF_MINSEEDS = ID_SORT_FILTER
			+ "-minSeeds";

	private static final int SHOW_SIDELIST_MINWIDTH_DP = 768;

	/* @Thunk */ static final int FILTER_INDEX_AGE = 0;

	/* @Thunk */ static final int FILTER_INDEX_SIZE = 1;

	/* @Thunk */ static final int FILTER_INDEX_LAST_SEEN = 2;

	/* @Thunk */ static final int FILTER_INDEX_RANK = 3;

	/* @Thunk */ static final int FILTER_INDEX_SEEDS = 4;

	private static final String DEFAULT_SORT_FIELD = TransmissionVars.FIELD_RCM_NAME;

	private static final boolean DEFAULT_SORT_ASC = false;

	private static SortByFields[] sortByFields;

	private SessionInfo sessionInfo;

	private RecyclerView listview;

	/* @Thunk */ long lastUpdated;

	/* @Thunk */ RcmAdapter adapter;

	/* @Thunk */ long rcmGotUntil;

	/* @Thunk */ boolean enabled;

	private boolean supportsRCM;

	/* @Thunk */ SwipeRefreshLayoutExtra swipeRefresh;

	/* @Thunk */ Handler pullRefreshHandler;

	/* @Thunk */ Map<String, Map<?, ?>> mapResults = new HashMap<>();

	/* @Thunk */ SideListHelper sideListHelper;

	private final Object mLock = new Object();

	private RemoteProfile remoteProfile;

	private TextView tvFilterAgeCurrent;

	private TextView tvFilterSizeCurrent;

	private TextView tvFilterLastSeenCurrent;

	private TextView tvFilterMinRankCurrent;

	private TextView tvFilterMinSeedsCurrent;

	private TextView tvFilterCurrent;

	private TextView tvFilterTop;

	private long maxSize;

	private TextView tvDrawerFilter;

	private SpanTags.SpanTagsListener listenerSpanTags = null;

	private TextView tvHeader;

	private TextView tvEmptyList;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		AndroidUtilsUI.onCreate(this);
		super.onCreate(savedInstanceState);

		Intent intent = getIntent();

		final Bundle extras = intent.getExtras();
		if (extras == null) {
			Log.e(TAG, "No extras!");
			finish();
			return;
		}

		final String remoteProfileID = extras.getString(
				SessionInfoManager.BUNDLE_KEY);
		sessionInfo = SessionInfoManager.getSessionInfo(remoteProfileID, this);

		if (sessionInfo == null) {
			Log.e(TAG, "No sessionInfo!");
			finish();
			return;
		}

		supportsRCM = sessionInfo.getSupportsRCM();

		if (supportsRCM) {
			sessionInfo.executeRpc(new RpcExecuter() {

				@Override
				public void executeRpc(TransmissionRPC rpc) {
					rpc.simpleRpcCall("rcm-is-enabled", new ReplyMapReceivedListener() {

						@Override
						public void rpcSuccess(String id, Map<?, ?> optionalMap) {
							if (optionalMap == null) {
								return;
							}

							if (!optionalMap.containsKey("ui-enabled")) {
								// old version
								return;
							}
							enabled = MapUtils.getMapBoolean(optionalMap, "ui-enabled",
									false);
							if (enabled) {
								triggerRefresh();
								VuzeEasyTracker.getInstance().sendEvent("RCM", "Show", null,
										null);
							} else {
								if (isFinishing()) {
									// Hopefully fix IllegalStateException in v2.1
									return;
								}
								DialogFragmentRcmAuth.openDialog(RcmActivity.this,
										remoteProfileID);
							}
						}

						@Override
						public void rpcFailure(String id, String message) {
						}

						@Override
						public void rpcError(String id, Exception e) {
						}
					});
				}
			});
		}

		int contentViewID = supportsRCM
				? (AndroidUtils.isTV() ? R.layout.activity_rcm_tv
						: AndroidUtilsUI.getScreenWidthDp(this) >= SHOW_SIDELIST_MINWIDTH_DP
								? R.layout.activity_rcm : R.layout.activity_rcm_drawer)
				: R.layout.activity_rcm_na;
		setContentView(contentViewID);
		setupActionBar();

		if (supportsRCM) {
			CollapsingToolbarLayout collapsingToolbarLayout = (CollapsingToolbarLayout) findViewById(
					R.id.collapsing_toolbar);
			if (collapsingToolbarLayout != null
					&& AndroidUtilsUI.getScreenHeightDp(this) > 1000) {
				// Disable scroll-to-hide for long views
				((AppBarLayout.LayoutParams) collapsingToolbarLayout.getLayoutParams()).setScrollFlags(
						0);
			}

			remoteProfile = sessionInfo.getRemoteProfile();
			setupListView();

			onCreate_setupDrawer();

			setupRCMViews();

		} else {
			TextView tvNA = (TextView) findViewById(R.id.rcm_na);

			new SpanBubbles().setSpanBubbles(tvNA, "|",
					AndroidUtilsUI.getStyleColor(this, R.attr.login_text_color),
					AndroidUtilsUI.getStyleColor(this, R.attr.login_textbubble_color),
					AndroidUtilsUI.getStyleColor(this, R.attr.login_text_color), null);
		}
	}

	private void setupRCMViews() {

		tvHeader = (TextView) findViewById(R.id.rcm_header);
		if (tvHeader != null) {
			tvHeader.setText(R.string.title_activity_rcm);
		}

		tvFilterTop = (TextView) findViewById(R.id.rcm_top_filterarea);

		if (tvFilterTop != null) {

			tvFilterTop.setMovementMethod(LinkMovementMethod.getInstance());

			listenerSpanTags = new SpanTags.SpanTagsListener() {

				@Override
				public void tagClicked(int index, Map mapTag, String name) {
					{
						switch (index) {
							case FILTER_INDEX_AGE:
								ageRow_clicked(null);
								break;
							case FILTER_INDEX_LAST_SEEN:
								lastSeenRow_clicked(null);
								break;
							case FILTER_INDEX_RANK:
								minRankRow_clicked(null);
								break;
							case FILTER_INDEX_SEEDS:
								minSeedsRow_clicked(null);
								break;
							case FILTER_INDEX_SIZE:
								fileSizeRow_clicked(null);
								break;
						}
					}
				}

				@Override
				public int getTagState(int index, Map mapTag, String name) {
					return SpanTags.TAG_STATE_SELECTED;
				}
			};
		}
		tvDrawerFilter = (TextView) findViewById(R.id.sidelist_topinfo);

		View viewFileSizeRow = findViewById(R.id.sidefilter_filesize);
		if (viewFileSizeRow != null) {
			viewFileSizeRow.setOnKeyListener(new View.OnKeyListener() {
				@Override
				public boolean onKey(View v, int keyCode, KeyEvent event) {
					return handleFileSizeRowKeyListener(keyCode, event);
				}
			});
		}

		View viewAgeRow = findViewById(R.id.sidefilter_age_row);
		if (viewAgeRow != null) {
			viewAgeRow.setOnKeyListener(new View.OnKeyListener() {
				@Override
				public boolean onKey(View v, int keyCode, KeyEvent event) {
					return handleAgeRowKeyListener(keyCode, event);
				}
			});
		}

		View viewLastSeenRow = findViewById(R.id.sidefilter_lastseen_row);
		if (viewLastSeenRow != null) {
			viewLastSeenRow.setOnKeyListener(new View.OnKeyListener() {
				@Override
				public boolean onKey(View v, int keyCode, KeyEvent event) {
					return handleLastSeenRowKeyListener(keyCode, event);
				}
			});
		}

		View viewMinSeedsRow = findViewById(R.id.sidefilter_minseeds_row);
		if (viewMinSeedsRow != null) {
			viewMinSeedsRow.setOnKeyListener(new View.OnKeyListener() {
				@Override
				public boolean onKey(View v, int keyCode, KeyEvent event) {
					return handleMinSeedRowKeyListener(keyCode, event);
				}
			});
		}
	}

	boolean handleMinSeedRowKeyListener(int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return false;
		}
		if (adapter == null) {
			return false;
		}

		if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP
				|| keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
			RcmAdapterFilter filter = adapter.getFilter();
			int filterVal = filter.getFilterMinSeeds();

			if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
				filterVal++;
			}
			if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
				filterVal--;
			}

			filter.setFilterMinSeeds(filterVal);
			filter.refilter();
			updateFilterTexts();
		}
		return false;
	}

	boolean handleLastSeenRowKeyListener(int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return false;
		}
		if (adapter == null) {
			return false;
		}

		if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP
				|| keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
			long[] filter = adapter.getFilter().getFilterLastSeenTimes();

			if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
				if (filter[0] <= 0) {
					filter[0] = AndroidUtils.getTodayMS();
				} else {
					filter[0] -= DateUtils.DAY_IN_MILLIS;
				}
			}
			if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
				filter[0] += DateUtils.DAY_IN_MILLIS;
				if (filter[0] > AndroidUtils.getTodayMS()) {
					filter[0] = -1;
				}
			}

			adapter.getFilter().setFilterLastSeenTimes(filter[0], filter[1]);
			adapter.getFilter().refilter();
			updateFilterTexts();
		}
		return false;
	}

	boolean handleAgeRowKeyListener(int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return false;
		}
		if (adapter == null) {
			return false;
		}

		if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP
				|| keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
			long[] filter = adapter.getFilter().getFilterPublishTimes();

			if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
				if (filter[0] <= 0) {
					filter[0] = AndroidUtils.getTodayMS();
				} else {
					filter[0] -= DateUtils.DAY_IN_MILLIS;
				}
			}
			if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
				if (filter == null) {
					return true;
				}
				filter[0] += DateUtils.DAY_IN_MILLIS;
				if (filter[0] > AndroidUtils.getTodayMS()) {
					filter[0] = -1;
				}
			}

			adapter.getFilter().setFilterPublishTimes(filter[0], filter[1]);
			adapter.getFilter().refilter();
			updateFilterTexts();
		}
		return false;
	}

	boolean handleFileSizeRowKeyListener(int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return false;
		}
		if (adapter == null) {
			return false;
		}

		if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP
				|| keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
			long[] filter = adapter.getFilter().getFilterSizes();

			if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
				filter[0] += 1024 * 1024L * 100; // 100M
			}
			if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
				filter[0] -= 1024 * 1024L * 100; // 100M
				if (filter[0] < 0) {
					filter[0] = 0;
				}
			}

			adapter.getFilter().setFilterSizes(filter[0], filter[1]);
			adapter.getFilter().refilter();
			updateFilterTexts();
		}
		return false;
	}

	private void setupListView() {

		tvEmptyList = (TextView) findViewById(R.id.tv_empty);

		tvEmptyList.setText(R.string.rcm_list_empty);

		RcmAdapter.RcmSelectionListener selectionListener = new RcmAdapter.RcmSelectionListener() {

			@Override
			public Map getSearchResultMap(String id) {
				return mapResults.get(id);
			}

			@Override
			public List<String> getSearchResultList() {
				return new ArrayList<>(mapResults.keySet());
			}

			@Override
			public void onItemClick(RcmAdapter adapter, int position) {
				if (!AndroidUtils.usesNavigationControl()) {
					// touch users have their own download button
					// nav pad people have click to download, hold-click for menu
					return;
				}

				String id = adapter.getItem(position);
				downloadResult(id);
			}

			@Override
			public void downloadResult(String id) {
				RcmActivity.this.downloadResult(id);
			}

			@Override
			public boolean onItemLongClick(RcmAdapter adapter, int position) {
				return false;
			}

			@Override
			public void onItemSelected(RcmAdapter adapter, int position,
					boolean isChecked) {
			}

			@Override
			public void onItemCheckedChanged(RcmAdapter adapter, String item,
					boolean isChecked) {
				AndroidUtils.invalidateOptionsMenuHC(RcmActivity.this);
			}
		};

		adapter = new RcmAdapter(this, selectionListener) {
			@Override
			public void lettersUpdated(HashMap<String, Integer> mapLetters) {
				sideListHelper.lettersUpdated(mapLetters);
			}

			@Override
			public void setItems(List<String> items) {
				super.setItems(items);
				updateFilterTexts();
			}
		};
		adapter.setMultiCheckModeAllowed(false);
		adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
			@Override
			public void onChanged() {
				updateFilterTexts();
			}

			@Override
			public void onItemRangeInserted(int positionStart, int itemCount) {
				updateFilterTexts();
			}

			@Override
			public void onItemRangeRemoved(int positionStart, int itemCount) {
				updateFilterTexts();
			}
		});
		adapter.setEmptyView(findViewById(R.id.empty_view_switcher));

		listview = (RecyclerView) findViewById(R.id.rcm_list);
		listview.setLayoutManager(new PreCachingLayoutManager(this));
		listview.setAdapter(adapter);

		if (AndroidUtils.isTV()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				listview.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_LEFT);
			}
			((FastScrollRecyclerView) listview).setEnableFastScrolling(false);
			((FlexibleRecyclerView) listview).setFixedVerticalHeight(
					AndroidUtilsUI.dpToPx(48));
			listview.setVerticalFadingEdgeEnabled(true);
			listview.setFadingEdgeLength(AndroidUtilsUI.dpToPx((int) (48 * 1.5)));
		}

		swipeRefresh = (SwipeRefreshLayoutExtra) findViewById(R.id.swipe_container);
		if (swipeRefresh != null) {
			swipeRefresh.setExtraLayout(R.layout.swipe_layout_extra);

			swipeRefresh.setOnRefreshListener(
					new SwipeRefreshLayout.OnRefreshListener() {
						@Override
						public void onRefresh() {
							triggerRefresh();
						}
					});
			swipeRefresh.setOnExtraViewVisibilityChange(this);
		}

		RemoteProfile remoteProfile = sessionInfo.getRemoteProfile();
		setupSideListArea(this.getWindow().getDecorView());

		String[] sortBy = remoteProfile.getSortBy(ID_SORT_FILTER,
				DEFAULT_SORT_FIELD);
		Boolean[] sortOrder = remoteProfile.getSortOrderAsc(ID_SORT_FILTER,
				DEFAULT_SORT_ASC);
		if (sortBy != null) {
			int which = TorrentUtils.findSordIdFromTorrentFields(this, sortBy,
					getSortByFields(this));
			sortBy(sortBy, sortOrder, which, false);
		}
	}

	/* @Thunk */
	void downloadResult(String id) {
		Map<?, ?> map = mapResults.get(id);
		String hash = MapUtils.getMapString(map, "hash", null);
		String name = MapUtils.getMapString(map, "title", null);
		if (hash != null && sessionInfo != null) {
			// TODO: When opening torrent, directory is "dunno" from here!!
			sessionInfo.openTorrent(RcmActivity.this, hash, name);
		}
	}

	private void setupSideListArea(View view) {
		if (sideListHelper == null || !sideListHelper.isValid()) {
			sideListHelper = new SideListHelper(this, view, R.id.sidelist_layout, 0,
					0, 0, 0, 500) {
				@Override
				public void expandedStateChanging(boolean expanded) {

				}

				@Override
				public void expandedStateChanged(boolean expanded) {
					SideSortAdapter sideSortAdapter = sideListHelper.getSideSortAdapter();
					if (sideSortAdapter != null) {
						sideSortAdapter.setViewType(expanded ? 0 : 1);
					}
				}

				@Override
				protected void sectionVisibiltyChanged(ViewGroup vgNewlyVisible) {
					super.sectionVisibiltyChanged(vgNewlyVisible);
				}

			};
			if (!sideListHelper.isValid()) {
				return;
			}

			sideListHelper.addEntry(view, R.id.sidesort_header, R.id.sidesort_list);
			sideListHelper.addEntry(view, R.id.sidefilter_header,
					R.id.sidefilter_list);
			sideListHelper.addEntry(view, R.id.sidetextfilter_header,
					R.id.sidetextfilter_list);
		}

		View sideListArea = view.findViewById(R.id.sidelist_layout);

		if (sideListArea != null && sideListArea.getVisibility() == View.VISIBLE) {
			sideListHelper.setupSideTextFilter(view, R.id.sidetextfilter_list,
					R.id.sidefilter_text, listview, adapter.getFilter());

			setupSideFilters(view);

			sideListHelper.setupSideSort(view, R.id.sidesort_list,
					R.id.rcm_sort_current, R.array.sortby_rcm_list, this);

			sideListHelper.expandedStateChanging(sideListHelper.isExpanded());
			sideListHelper.expandedStateChanged(sideListHelper.isExpanded());
		}

		if (sideListHelper.hasSideTextFilterArea()) {
			adapter.getFilter().setBuildLetters(true);
		}
	}

	private void setupSideFilters(View view) {
		tvFilterAgeCurrent = (TextView) view.findViewById(
				R.id.rcm_filter_age_current);
		tvFilterSizeCurrent = (TextView) view.findViewById(
				R.id.rcm_filter_size_current);
		tvFilterLastSeenCurrent = (TextView) view.findViewById(
				R.id.rcm_filter_lastseen_current);
		tvFilterMinSeedsCurrent = (TextView) view.findViewById(
				R.id.rcm_filter_min_seeds);
		tvFilterMinRankCurrent = (TextView) view.findViewById(
				R.id.rcm_filter_min_rank);
		tvFilterCurrent = (TextView) view.findViewById(R.id.rcm_filter_current);

		updateFilterTexts();
	}

	public SortByFields[] getSortByFields(Context context) {
		if (sortByFields != null) {
			return sortByFields;
		}
		String[] sortNames = context.getResources().getStringArray(
				R.array.sortby_rcm_list);

		sortByFields = new SortByFields[sortNames.length - 1];
		int i = 0;

		//<item>Rank</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_RCM_RANK
		}, new Boolean[] {
			false
		});

		i++; // <item>Name</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_RCM_NAME
		}, new Boolean[] {
			true
		});

		i++; // <item>Seeds</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_RCM_SEEDS,
			TransmissionVars.FIELD_RCM_PEERS
		}, new Boolean[] {
			false,
			false
		});

		i++; // <item>size</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_RCM_SIZE
		}, new Boolean[] {
			false
		});

		i++; // <item>PublishDate</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_RCM_PUBLISHDATE
		}, new Boolean[] {
			false
		});

		i++; // <item>Last Seen</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_RCM_LAST_SEEN_SECS
		}, new Boolean[] {
			false
		});

		return sortByFields;
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (supportsRCM) {
			VuzeEasyTracker.getInstance(this).screenStart(TAG);
		} else {
			VuzeEasyTracker.getInstance(this).screenStart(TAG + ":NA");
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		VuzeEasyTracker.getInstance(this).activityStop(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (sessionInfo != null) {
			sessionInfo.activityPaused();
			sessionInfo.removeRefreshTriggerListener(this);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (adapter != null) {
			adapter.onSaveInstanceState(outState);
		}
		if (sideListHelper != null) {
			sideListHelper.onSaveInstanceState(outState);
		}
		outState.putLong("rcmGotUntil", rcmGotUntil);
		outState.putString("list", JSONUtils.encodeToJSON(mapResults));
	}

	@Override
	public void onRestoreInstanceState(Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);
		if (adapter != null) {
			adapter.onRestoreInstanceState(savedInstanceState, listview);
		}
		if (sideListHelper != null) {
			sideListHelper.onRestoreInstanceState(savedInstanceState);
		}
		updateFilterTexts();

		rcmGotUntil = savedInstanceState.getLong("rcmGotUntil", 0);
		if (rcmGotUntil > 0) {
			String list = savedInstanceState.getString("list");
			if (list != null) {
				Map<String, Object> map = JSONUtils.decodeJSONnoException(list);

				if (map != null) {
					for (String key : map.keySet()) {
						Object o = map.get(key);
						if (o instanceof Map) {
							mapResults.put(key, (Map) o);
						}
					}
				}
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (sessionInfo != null) {
			sessionInfo.activityResumed(this);
			sessionInfo.addRefreshTriggerListener(this);
		}
		if (sideListHelper != null) {
			sideListHelper.onResume();
		}
	}

	private void setupActionBar() {
		Toolbar toolBar = (Toolbar) findViewById(R.id.actionbar);
		if (toolBar != null) {
			setSupportActionBar(toolBar);
		}

		// enable ActionBar app icon to behave as action to toggle nav drawer
		ActionBar actionBar = getSupportActionBar();
		if (actionBar == null) {
			System.err.println("actionBar is null");
			return;
		}

		RemoteProfile remoteProfile = sessionInfo.getRemoteProfile();
		if (remoteProfile != null) {
			actionBar.setTitle(remoteProfile.getNick());
			// Text usually too long for phone ui
//			actionBar.setTitle(
//					getResources().getString(R.string.title_with_profile_name, getTitle(),
//							remoteProfile.getNick()));
		}

		// enable ActionBar app icon to behave as action to toggle nav drawer
		actionBar.setDisplayHomeAsUpEnabled(true);
		actionBar.setHomeButtonEnabled(true);
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		if (onOptionsItemSelected_drawer(item)) {
			return true;
		}
		int itemId = item.getItemId();
		if (itemId == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void triggerRefresh() {
		if (sessionInfo == null) {
			return;
		}
		if (!enabled) {
			return;
		}
		sessionInfo.executeRpc(new RpcExecuter() {

			@Override
			public void executeRpc(TransmissionRPC rpc) {
				Map<String, Object> map = new HashMap<>();
				if (rcmGotUntil > 0) {
					map.put("since", rcmGotUntil);
				}
				rpc.simpleRpcCall("rcm-get-list", map, new ReplyMapReceivedListener() {

					@Override
					public void rpcSuccess(String id, final Map<?, ?> map) {
						lastUpdated = System.currentTimeMillis();
						try {
							Log.d(TAG, "rcm-get-list: " + map);
						} catch (Throwable ignored) {
						}
						runOnUiThread(new Runnable() {

							@Override
							public void run() {
								if (isFinishing()) {
									return;
								}
								if (swipeRefresh != null) {
									swipeRefresh.setRefreshing(false);
								}

								long until = MapUtils.getMapLong(map, "until", 0);
								updateList(MapUtils.getMapList(map, "related", null));
								rcmGotUntil = until + 1;
							}
						});

					}

					@Override
					public void rpcFailure(String id, String message) {
					}

					@Override
					public void rpcError(String id, Exception e) {
					}
				});
			}
		});
	}

	public void updateList(List<?> listRCMs) {
		if (listRCMs == null || listRCMs.isEmpty()) {
			if (mapResults.size() == 0) {
				// triggers display of "empty"
				adapter.notifyDataSetInvalidated();
			}
			return;
		}
		synchronized (mLock) {
			for (Object object : listRCMs) {
				Map<?, ?> mapRCM = (Map<?, ?>) object;
				String hash = MapUtils.getMapString(mapRCM,
						TransmissionVars.FIELD_RCM_HASH, null);

				Map<?, ?> old = mapResults.put(hash, mapRCM);
				if (old == null) {
					//adapter.addItem(hash);

					long size = MapUtils.getMapLong(mapRCM,
							TransmissionVars.FIELD_RCM_SIZE, 0);
					if (size > maxSize) {
						maxSize = size;
					}

				}
				List listTags = MapUtils.getMapList(mapRCM,
						TransmissionVars.FIELD_RCM_TAGS, null);
				if (listTags != null && listTags.size() > 0) {
					Iterator iterator = listTags.iterator();
					while (iterator.hasNext()) {
						Object o = iterator.next();

						if ("_i2p_".equals(o)) {
							iterator.remove();
							break;
						}
					}
				}
			}
		}

		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				adapter.getFilter().refilter();
			}
		});
	}

	@Override
	public SessionInfo getSessionInfo() {
		return sessionInfo;
	}

	@Override
	public void onDrawerClosed(View view) {
	}

	@Override
	public void onDrawerOpened(View view) {
		setupSideListArea(view);
		updateFilterTexts();
	}

	@Override
	public void rcmEnabledChanged(boolean enable, boolean all) {
		this.enabled = enable;
		if (enabled) {
			triggerRefresh();
		} else {
			finish();
		}
	}

	@Override
	public void onExtraViewVisibilityChange(final View view, int visibility) {
		{
			if (visibility != View.VISIBLE) {
				if (pullRefreshHandler != null) {
					pullRefreshHandler.removeCallbacksAndMessages(null);
					pullRefreshHandler = null;
				}
				return;
			}

			if (pullRefreshHandler != null) {
				pullRefreshHandler.removeCallbacks(null);
				pullRefreshHandler = null;
			}
			pullRefreshHandler = new Handler(Looper.getMainLooper());

			pullRefreshHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if (isFinishing()) {
						return;
					}

					long sinceMS = System.currentTimeMillis() - lastUpdated;
					String since = DateUtils.getRelativeDateTimeString(RcmActivity.this,
							lastUpdated, DateUtils.SECOND_IN_MILLIS, DateUtils.WEEK_IN_MILLIS,
							0).toString();
					String s = getResources().getString(R.string.last_updated, since);

					TextView tvSwipeText = (TextView) view.findViewById(R.id.swipe_text);
					tvSwipeText.setText(s);

					if (pullRefreshHandler == null) {
						return;
					}
					pullRefreshHandler.postDelayed(this,
							sinceMS < DateUtils.MINUTE_IN_MILLIS ? DateUtils.SECOND_IN_MILLIS
									: sinceMS < DateUtils.HOUR_IN_MILLIS
											? DateUtils.MINUTE_IN_MILLIS : DateUtils.HOUR_IN_MILLIS);
				}
			}, 0);
		}
	}

	public void sortBy(final String[] sortFieldIDs, final Boolean[] sortOrderAsc,
			final int which, boolean save) {
		if (AndroidUtils.DEBUG) {
			Log.d(TAG, "SORT BY " + Arrays.toString(sortFieldIDs));
		}
		if (adapter != null) {
			adapter.setSort(sortFieldIDs, sortOrderAsc);
		}
		runOnUiThread(new Runnable() {
			public void run() {
				if (isFinishing()) {
					return;
				}
				SideSortAdapter sideSortAdapter = sideListHelper.getSideSortAdapter();
				if (sideSortAdapter != null) {
					sideSortAdapter.setCurrentSort(which, sortOrderAsc[0]);
				}

				String[] sortNames = getResources().getStringArray(
						R.array.sortby_rcm_list);
				String s = "";
				if (which >= 0 && which < sortNames.length) {
					s = sortNames[which] + " " + (sortOrderAsc[0] ? "▲" : "▼");
				}
				sideListHelper.setSideSortCurrentText(s);

			}
		});

		if (save && sessionInfo != null) {
			sessionInfo.getRemoteProfile().setSortBy(ID_SORT_FILTER, sortFieldIDs,
					sortOrderAsc);
			sessionInfo.saveProfile();
		}
	}

	public void flipSortOrder() {
		if (sessionInfo == null) {
			return;
		}
		RemoteProfile remoteProfile = sessionInfo.getRemoteProfile();
		if (remoteProfile == null) {
			return;
		}
		Boolean[] sortOrder = remoteProfile.getSortOrderAsc(ID_SORT_FILTER,
				DEFAULT_SORT_ASC);
		if (sortOrder == null) {
			return;
		}
		for (int i = 0; i < sortOrder.length; i++) {
			sortOrder[i] = !sortOrder[i];
		}
		String[] sortBy = remoteProfile.getSortBy(ID_SORT_FILTER,
				DEFAULT_SORT_FIELD);
		sortBy(sortBy, sortOrder, findSordIdFromTorrentFields(this, sortBy), true);
	}

	public int findSordIdFromTorrentFields(Context context, String[] fields) {
		SortByFields[] sortByFields = getSortByFields(context);
		return TorrentUtils.findSordIdFromTorrentFields(context, fields,
				sortByFields);
	}

	public void fileSizeRow_clicked(View view) {
		if (adapter == null) {
			return;
		}
		long[] sizeRange = adapter.getFilter().getFilterSizes();

		DialogFragmentSizeRange.openDialog(getSupportFragmentManager(),
				ID_SORT_FILTER, sessionInfo.getRemoteProfile().getID(), maxSize,
				sizeRange[0], sizeRange[1]);
	}

	public void ageRow_clicked(View view) {
		if (adapter == null) {
			return;
		}
		long[] timeRange = adapter.getFilter().getFilterPublishTimes();

		DialogFragmentDateRange.openDialog(getSupportFragmentManager(),
				ID_SORT_FILTER, sessionInfo.getRemoteProfile().getID(), timeRange[0],
				timeRange[1]);
	}

	public void lastSeenRow_clicked(View view) {
		if (adapter == null) {
			return;
		}
		long[] timeRange = adapter.getFilter().getFilterLastSeenTimes();

		DialogFragmentDateRange.openDialog(getSupportFragmentManager(),
				FILTER_PREF_LAST_SEEN, sessionInfo.getRemoteProfile().getID(),
				timeRange[0], timeRange[1]);
	}

	public void minRankRow_clicked(View view) {
		if (adapter == null) {
			return;
		}
		int val = adapter.getFilter().getFilterMinRank();
		DialogFragmentNumberPicker.openDialog(getSupportFragmentManager(),
				FILTER_PREF_MINRANK, sessionInfo.getRemoteProfile().getID(),
				R.string.filterby_header_minimum_rank, val, 0, 100);
	}

	public void minSeedsRow_clicked(View view) {
		if (adapter == null) {
			return;
		}
		int val = adapter.getFilter().getFilterMinSeeds();
		DialogFragmentNumberPicker.openDialog(getSupportFragmentManager(),
				FILTER_PREF_MINSEEDS, sessionInfo.getRemoteProfile().getID(),
				R.string.filterby_header_minimum_seeds, val, 0, 99);
	}

	@Override
	public void onSizeRangeChanged(String callbackID, long start, long end) {
		if (adapter == null) {
			return;
		}
		adapter.getFilter().setFilterSizes(start, end);
		adapter.getFilter().refilter();
		updateFilterTexts();
	}

	@Override
	public void onDateRangeChanged(String callbackID, long start, long end) {
		if (adapter == null) {
			return;
		}
		RcmAdapterFilter filter = adapter.getFilter();
		if (ID_SORT_FILTER.equals(callbackID)) {
			filter.setFilterPublishTimes(start, end);
		} else {
			filter.setFilterLastSeenTimes(start, end);
		}
		filter.refilter();
		updateFilterTexts();
	}

	@Override
	public void onNumberPickerChange(String callbackID, int val) {
		if (adapter == null) {
			return;
		}
		if (FILTER_PREF_MINSEEDS.equals(callbackID)) {
			adapter.getFilter().setFilterMinSeeds(val);
		} else if (FILTER_PREF_MINRANK.equals(callbackID)) {
			adapter.getFilter().setFilterMinRank(val);
		}
		adapter.getFilter().refilter();
		updateFilterTexts();
	}

	public void clearFilters_clicked(View view) {

		RcmAdapterFilter filter = adapter.getFilter();

		filter.clearFilter();
		filter.refilter();
		updateFilterTexts();
	}

	/* @Thunk */
	void updateFilterTexts() {

		if (!AndroidUtilsUI.isUIThread()) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					updateFilterTexts();
				}
			});
			return;
		}

		if (adapter == null) {
			return;
		}

		String sCombined = "";

		Resources resources = getResources();

		String filterTimeText;
		String filterSizeText;
		String filterLastSeenText;
		String filterMinRankText;
		String filterMinSeedsText;

		RcmAdapterFilter filter = adapter.getFilter();

		long[] timeRange = filter.getFilterPublishTimes();
		if (timeRange[0] <= 0 && timeRange[1] <= 0) {
			filterTimeText = resources.getString(R.string.filter_age_none);
		} else {
			if (timeRange[1] > 0 && timeRange[0] > 0) {
				filterTimeText = DateUtils.formatDateRange(this, timeRange[0],
						timeRange[1],
						DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
			} else if (timeRange[0] > 0) {
				filterTimeText = resources.getString(R.string.filter_date_starting,
						DateUtils.getRelativeTimeSpanString(this, timeRange[0], true));
			} else {
				filterTimeText = resources.getString(R.string.filter_date_until,
						DateUtils.getRelativeTimeSpanString(timeRange[1],
								System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS));
			}
			sCombined += filterTimeText;
		}

		if (tvFilterAgeCurrent != null) {
			tvFilterAgeCurrent.setText(filterTimeText);
		}

		long[] sizeRange = filter.getFilterSizes();
		if (sizeRange[0] <= 0 && sizeRange[1] <= 0) {
			filterSizeText = resources.getString(R.string.filter_size_none);
		} else {
			if (sizeRange[1] > 0 && sizeRange[0] > 0) {
				filterSizeText = resources.getString(R.string.filter_size,
						DisplayFormatters.formatByteCountToKiBEtc(sizeRange[0], true),
						DisplayFormatters.formatByteCountToKiBEtc(sizeRange[1], true));
			} else if (sizeRange[1] > 0) {
				filterSizeText = resources.getString(R.string.filter_size_upto,
						DisplayFormatters.formatByteCountToKiBEtc(sizeRange[1], true));
			} else {
				filterSizeText = resources.getString(R.string.filter_size_atleast,
						DisplayFormatters.formatByteCountToKiBEtc(sizeRange[0], true));
			}
			if (sCombined.length() > 0) {
				sCombined += "\n";
			}
			sCombined += filterSizeText;
		}

		if (tvFilterSizeCurrent != null) {
			tvFilterSizeCurrent.setText(filterSizeText);
		}

		long[] lastSeenRange = filter.getFilterLastSeenTimes();
		if (lastSeenRange[0] <= 0 && lastSeenRange[1] <= 0) {
			filterLastSeenText = resources.getString(R.string.filter_lastseen_none);
		} else {
			if (lastSeenRange[1] > 0 && lastSeenRange[0] > 0) {
				filterLastSeenText = DateUtils.formatDateRange(this, lastSeenRange[0],
						lastSeenRange[1],
						DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
			} else if (lastSeenRange[0] > 0) {
				CharSequence s = DateUtils.getRelativeTimeSpanString(lastSeenRange[0],
						System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
						DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
				filterLastSeenText = resources.getString(R.string.filter_date_starting,
						s);
			} else {
				CharSequence s = DateUtils.getRelativeTimeSpanString(lastSeenRange[0],
						System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
						DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
				filterLastSeenText = resources.getString(R.string.filter_date_until, s);
			}
			if (sCombined.length() > 0) {
				sCombined += "\n";
			}
			sCombined += filterLastSeenText;
		}

		if (tvFilterLastSeenCurrent != null) {
			tvFilterLastSeenCurrent.setText(filterLastSeenText);
		}

		int minSeeds = filter.getFilterMinSeeds();
		if (minSeeds <= 0) {
			filterMinSeedsText = resources.getString(R.string.filter_seeds_none);
		} else {
			filterMinSeedsText = resources.getQuantityString(R.plurals.filter_seeds,
					minSeeds, minSeeds);
			if (sCombined.length() > 0) {
				sCombined += "\n";
			}
			sCombined += filterMinSeedsText;
		}
		if (tvFilterMinSeedsCurrent != null) {
			tvFilterMinSeedsCurrent.setText(filterMinSeedsText);
		}

		int minRank = filter.getFilterMinRank();
		if (minRank <= 0) {
			filterMinRankText = resources.getString(R.string.filter_rank_none);
		} else {
			filterMinRankText = resources.getString(R.string.filter_rank, minRank);
			if (sCombined.length() > 0) {
				sCombined += "\n";
			}
			sCombined += filterMinRankText;
		}
		if (tvFilterMinRankCurrent != null) {
			tvFilterMinRankCurrent.setText(filterMinRankText);
		}

		if (tvFilterCurrent != null) {
			tvFilterCurrent.setText(sCombined);
		}

		if (tvFilterTop != null) {
			SpanTags spanTag = new SpanTags(this, sessionInfo, tvFilterTop,
					listenerSpanTags);
			spanTag.setLinkTags(false);
			spanTag.setShowIcon(false);
			List<Map<?, ?>> listFilters = new ArrayList<>();
			listFilters.add(makeFilterListMap(FILTER_INDEX_AGE, filterTimeText,
					filter.hasPublishTimeFilter()));
			listFilters.add(makeFilterListMap(FILTER_INDEX_SIZE, filterSizeText,
					filter.hasSizeFilter()));
			listFilters.add(makeFilterListMap(FILTER_INDEX_LAST_SEEN,
					filterLastSeenText, filter.hasLastSeenFilter()));
			listFilters.add(makeFilterListMap(FILTER_INDEX_RANK, filterMinRankText,
					filter.hasMinRankFilter()));
			listFilters.add(makeFilterListMap(FILTER_INDEX_SEEDS, filterMinSeedsText,
					filter.hasMinSeedsFilter()));
			spanTag.setTagMaps(listFilters);
			spanTag.updateTags();
		}

		int count = mapResults == null ? 0 : mapResults.size();
		int filteredCount = adapter.getItemCount();
		String countString = DisplayFormatters.formatNumber(count);
		ActionBar actionBar = getSupportActionBar();
		String sResultsCount;
		if (count == filteredCount) {
			sResultsCount = getResources().getQuantityString(
					R.plurals.rcm_results_count, count, countString);
		} else {
			sResultsCount = getResources().getQuantityString(
					R.plurals.rcm_filtered_results_count, count,
					DisplayFormatters.formatNumber(filteredCount), countString);
		}
		if (tvDrawerFilter != null) {
			tvDrawerFilter.setText(sResultsCount);
		}
		if (actionBar != null) {
			actionBar.setSubtitle(sResultsCount);
		}
		if (tvHeader != null) {
			tvHeader.setText(sResultsCount);
		}

	}

	private HashMap<Object, Object> makeFilterListMap(int uid, String name,
			boolean enabled) {
		HashMap<Object, Object> map = new HashMap<>();
		map.put("uid", Long.valueOf(uid));
		map.put("name", name);
		map.put("rounded", true);
		map.put("color", enabled ? 0xFF000000 : 0xA0000000);
		map.put("fillColor", enabled ? 0xFF80ffff : 0x4080ffff);
		return map;
	}

}
