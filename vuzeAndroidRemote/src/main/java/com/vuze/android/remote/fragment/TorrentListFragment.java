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

package com.vuze.android.remote.fragment;

import java.util.*;

import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;
import com.vuze.android.FlexibleRecyclerSelectionListener;
import com.vuze.android.FlexibleRecyclerView;
import com.vuze.android.MenuDialogHelper;
import com.vuze.android.remote.*;
import com.vuze.android.remote.AndroidUtils.ValueStringArray;
import com.vuze.android.remote.activity.DrawerActivity;
import com.vuze.android.remote.activity.TorrentViewActivity;
import com.vuze.android.remote.adapter.*;
import com.vuze.android.remote.adapter.TorrentListAdapter.TorrentFilter;
import com.vuze.android.remote.dialog.DialogFragmentDeleteTorrent;
import com.vuze.android.remote.dialog.DialogFragmentMoveData;
import com.vuze.android.remote.rpc.*;
import com.vuze.android.remote.session.*;
import com.vuze.android.remote.session.Session.RpcExecuter;
import com.vuze.android.remote.spanbubbles.SpanTags;
import com.vuze.android.util.NetworkState;
import com.vuze.android.widget.PreCachingLayoutManager;
import com.vuze.android.widget.SwipeRefreshLayoutExtra;
import com.vuze.util.MapUtils;
import com.vuze.util.Thunk;

import android.content.Context;
import android.os.*;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.util.LongSparseArray;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.support.v7.view.ActionMode.Callback;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.view.menu.SubMenuBuilder;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.*;
import android.widget.TextView;

/**
 * Handles a ListView that shows Torrents
 */
public class TorrentListFragment
	extends Fragment
	implements TorrentListReceivedListener, SessionListener,
	ActionModeBeingReplacedListener, TagListReceivedListener, View.OnKeyListener,
	SessionSettingsChangedListener, TorrentListRefreshingListener,
	NetworkState.NetworkStateListener, SideListHelper.SideSortAPI

{
	@Thunk
	static final boolean DEBUG = AndroidUtils.DEBUG;

	private static final String TAG = "TorrentList";

	private static final int SIDELIST_MAX_WIDTH = VuzeRemoteApp.getContext().getResources().getDimensionPixelSize(
			R.dimen.sidelist_max_width);

	private static final int SIDELIST_MIN_WIDTH = VuzeRemoteApp.getContext().getResources().getDimensionPixelSize(
			R.dimen.sidelist_min_width);

	// Shrink sidelist, typically for 7" Tablets in Portrait
	private static final int SIDELIST_COLLAPSE_UNTIL_WIDTH_DP = 500;

	private static final int SIDELIST_COLLAPSE_UNTIL_WIDTH_PX = AndroidUtilsUI.dpToPx(
			SIDELIST_COLLAPSE_UNTIL_WIDTH_DP);

	// Sidelist always full-width, typically for 9"-11" Tablets, 7" Tablets in
	// Landscape, and TVs
	private static final int SIDELIST_KEEP_EXPANDED_AT_DP = 610;

	// Rare case when there's not enough height.  Show only active sidelist
	// header
	// This would be for Dell Streak (800x480dp) if it was API >= 13
	// Can't be >= 540, since TVs are that.
	// Each row is 42dp.  42x4=168, plus top actionbar (64dp?) and our header
	// (20dp?) ~ 252 dp.  Want to show at least 6 rows of the list.  6x42=252
	private static final int SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP = 500;

	public interface OnTorrentSelectedListener
		extends ActionModeBeingReplacedListener
	{
		void onTorrentSelectedListener(TorrentListFragment torrentListFragment,
				long[] ids, boolean inMultiMode);
	}

	@Thunk
	public RecyclerView listview;

	@Thunk
	ActionMode mActionMode;

	@Thunk
	public TorrentListAdapter torrentListAdapter;

	private Callback mActionModeCallback;

	@Thunk
	TextView tvFilteringBy;

	@Thunk
	TextView tvTorrentCount;

	@Thunk
	boolean actionModeBeingReplaced;

	private boolean rebuildActionMode;

	@Thunk
	OnTorrentSelectedListener mCallback;

	// >> SideList

	private RecyclerView listSideActions;

	private RecyclerView listSideTags;

	@Thunk
	SideActionsAdapter sideActionsAdapter;

	@Thunk
	SideTagAdapter sideTagAdapter;

	@Thunk
	SideListHelper sideListHelper;

	// << SideList

	private Boolean isSmall;

	private SortByFields[] sortByFields = null;

	@Thunk
	TextView tvEmpty;

	private String remoteProfileID;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		remoteProfileID = SessionManager.findRemoteProfileID(getActivity(), TAG);
		super.onCreate(savedInstanceState);
	}

	@Override
	public void onAttach(Context activity) {
		super.onAttach(activity);

		if (activity instanceof OnTorrentSelectedListener) {
			mCallback = (OnTorrentSelectedListener) activity;
		}

		FlexibleRecyclerSelectionListener rs = new FlexibleRecyclerSelectionListener<TorrentListAdapter, Long>() {
			@Override
			public void onItemSelected(TorrentListAdapter adapter, final int position,
					boolean isChecked) {
			}

			@Override
			public void onItemClick(TorrentListAdapter adapter, int position) {
			}

			@Override
			public boolean onItemLongClick(TorrentListAdapter adapter, int position) {
				if (AndroidUtils.usesNavigationControl()) {
					return showTorrentContextMenu();
				}
				return false;
			}

			@Override
			public void onItemCheckedChanged(TorrentListAdapter adapter, Long item,
					boolean isChecked) {
				if (mActionMode == null && isChecked) {
					showContextualActions(false);
				}

				if (adapter.getCheckedItemCount() == 0) {
					finishActionMode();
				}

				if (adapter.isMultiCheckMode()) {
					updateActionModeText(mActionMode);
				}
				updateCheckedIDs();

				AndroidUtilsUI.invalidateOptionsMenuHC(getActivity(), mActionMode);
			}
		};

		torrentListAdapter = new TorrentListAdapter(activity, rs) {
			@Override
			public void lettersUpdated(HashMap<String, Integer> mapLetters) {
				sideListHelper.lettersUpdated(mapLetters);
			}
		};
		torrentListAdapter.registerAdapterDataObserver(
				new RecyclerView.AdapterDataObserver() {
					@Override
					public void onItemRangeInserted(int positionStart, int itemCount) {
						updateTorrentCount(torrentListAdapter.getItemCount());
					}

					@Override
					public void onItemRangeRemoved(int positionStart, int itemCount) {
						updateTorrentCount(torrentListAdapter.getItemCount());
					}

					@Override
					public void onChanged() {
						updateTorrentCount(torrentListAdapter.getItemCount());
					}
				});
		torrentListAdapter.setMultiCheckModeAllowed(
				!AndroidUtils.usesNavigationControl());
	}

	@Nullable
	public View getItemView(long id) {
		if (torrentListAdapter == null || listview == null) {
			return null;
		}
		int positionForItem = torrentListAdapter.getPositionForItem(id);
		RecyclerView.ViewHolder viewHolder = listview.findViewHolderForAdapterPosition(
				positionForItem);

		if (viewHolder == null) {
			return null;
		}
		return viewHolder.itemView;
	}

	@Override
	public void uiReady(TransmissionRPC rpc) {
		if (getActivity() == null) {
			return;
		}

		Session session = getSession();
		RemoteProfile remoteProfile = session.getRemoteProfile();

		String[] sortBy = remoteProfile.getSortBy("",
				TransmissionVars.FIELD_TORRENT_NAME);
		Boolean[] sortOrder = remoteProfile.getSortOrderAsc("", true);
		if (sortBy != null) {
			int which = TorrentUtils.findSordIdFromTorrentFields(sortBy,
					getSortByFields(getContext()));
			sortBy(sortBy, sortOrder, which, false);
		}

		long filterBy = remoteProfile.getFilterBy();
		// Convert All Filter to tag if we have tags
		if (filterBy == TorrentListAdapter.FILTERBY_ALL
				&& session.getSupports(RPCSupports.SUPPORTS_TAGS)) {
			Long tagAllUID = session.tag.getTagAllUID();
			if (tagAllUID != null) {
				filterBy = tagAllUID;
			}
		}
		if (filterBy > 10) {
			Map<?, ?> tag = session.tag.getTag(filterBy);

			filterBy(filterBy, MapUtils.getMapString(tag, "name", "fooo"), false);
		} else if (filterBy >= 0) {
			final ValueStringArray filterByList = AndroidUtils.getValueStringArray(
					getResources(), R.array.filterby_list);
			for (int i = 0; i < filterByList.values.length; i++) {
				long val = filterByList.values[i];
				if (val == filterBy) {
					filterBy(filterBy, filterByList.strings[i], false);
					break;
				}
			}
		}

		if (sideActionsAdapter != null) {
			sideActionsAdapter.updateMenuItems();
		}
		getActivity().supportInvalidateOptionsMenu();
	}

	/* (non-Javadoc)
	 * @see android.support.v4.app.Fragment#onCreateView(android.view
	 * .LayoutInflater, android.view.ViewGroup, android.os.Bundle)
	 */
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {

		View fragView = inflater.inflate(R.layout.frag_torrent_list, container,
				false);

		setupActionModeCallback();

		final SwipeRefreshLayoutExtra swipeRefresh = (SwipeRefreshLayoutExtra) fragView.findViewById(
				R.id.swipe_container);
		if (swipeRefresh != null) {
			swipeRefresh.setExtraLayout(R.layout.swipe_layout_extra);

			LastUpdatedInfo lui = getLastUpdatedString();
			if (lui != null) {
				View extraView = swipeRefresh.getExtraView();
				if (extraView != null) {
					TextView tvSwipeText = (TextView) extraView.findViewById(
							R.id.swipe_text);
					tvSwipeText.setText(lui.s);
				}
			}
			swipeRefresh.setOnRefreshListener(
					new SwipeRefreshLayout.OnRefreshListener() {
						@Override
						public void onRefresh() {
							Session session = getSession();
							session.torrent.addListReceivedListener(
									new TorrentListReceivedListener() {

										@Override
										public void rpcTorrentListReceived(String callID,
												List<?> addedTorrentMaps, List<?> removedTorrentIDs) {
											AndroidUtilsUI.runOnUIThread(TorrentListFragment.this,
													new Runnable() {
														@Override
														public void run() {
															if (getActivity() == null) {
																return;
															}
															swipeRefresh.setRefreshing(false);
															LastUpdatedInfo lui = getLastUpdatedString();
															View extraView = swipeRefresh.getExtraView();
															if (extraView != null) {
																TextView tvSwipeText = (TextView) extraView.findViewById(
																		R.id.swipe_text);
																tvSwipeText.setText(lui.s);
															}
														}
													});
											Session session = getSession();
											session.torrent.removeListReceivedListener(this);
										}
									}, false);
							session.triggerRefresh(true);
						}
					});
			swipeRefresh.setOnExtraViewVisibilityChange(
					new SwipeRefreshLayoutExtra.OnExtraViewVisibilityChangeListener() {
						@Thunk
						Handler pullRefreshHandler;

						@Override
						public void onExtraViewVisibilityChange(final View view,
								int visibility) {
							if (pullRefreshHandler != null) {
								pullRefreshHandler.removeCallbacksAndMessages(null);
								pullRefreshHandler = null;
							}
							if (visibility != View.VISIBLE) {
								return;
							}

							pullRefreshHandler = new Handler(Looper.getMainLooper());

							pullRefreshHandler.postDelayed(new Runnable() {
								@Override
								public void run() {
									if (getActivity() == null) {
										return;
									}
									LastUpdatedInfo lui = getLastUpdatedString();
									if (lui == null) {
										return;
									}
									TextView tvSwipeText = (TextView) view.findViewById(
											R.id.swipe_text);
									tvSwipeText.setText(lui.s);

									if (pullRefreshHandler != null) {
										pullRefreshHandler.postDelayed(this,
												lui.sinceMS < DateUtils.MINUTE_IN_MILLIS
														? DateUtils.SECOND_IN_MILLIS
														: lui.sinceMS < DateUtils.HOUR_IN_MILLIS
																? DateUtils.MINUTE_IN_MILLIS
																: DateUtils.HOUR_IN_MILLIS);
									}
								}
							}, 0);
						}
					});
		}

		torrentListAdapter.setEmptyView(fragView.findViewById(R.id.first_list),
				fragView.findViewById(R.id.empty_list));

		listview = (RecyclerView) fragView.findViewById(R.id.listTorrents);
		listview.setLayoutManager(new PreCachingLayoutManager(getContext()));
		listview.setAdapter(torrentListAdapter);

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

		/** Handy code to watch the states of row 2
		 listview.postDelayed(new Runnable() {
		 String oldS = "";
		
		 @Override public void run() {
		
		 String s = (listview.getChildCount() < 3 ? ""
		 : AndroidUtils.getStatesString(listview.getChildAt(2).getDrawableState
		 ()));
		
		 if (!s.equals(oldS)) {
		 oldS = s;
		 Log.e(TAG, "States of 2: " + s);
		 }
		
		 listview.postDelayed(this, 500);
		 }
		 }, 500);
		 */

		setHasOptionsMenu(true);

		return fragView;
	}

	private void setupSideListArea() {
		FragmentActivity activity = getActivity();
		View view = AndroidUtilsUI.getContentView(activity);

		Toolbar abToolBar = (Toolbar) activity.findViewById(R.id.actionbar);

		boolean showActionsArea = abToolBar == null;
		if (!showActionsArea) {
			View viewToHide = activity.findViewById(R.id.sideactions_header);
			if (viewToHide != null) {
				viewToHide.setVisibility(View.GONE);
			}
			viewToHide = activity.findViewById(R.id.sideactions_list);
			if (viewToHide != null) {
				viewToHide.setVisibility(View.GONE);
			}
		}

		boolean setupForDrawer = abToolBar != null
				&& (activity instanceof DrawerActivity);

		if (sideListHelper == null || !sideListHelper.isValid()) {
			sideListHelper = new SideListHelper(getActivity(), view,
					R.id.sidelist_layout, setupForDrawer ? 0 : SIDELIST_MIN_WIDTH,
					setupForDrawer ? 0 : SIDELIST_MAX_WIDTH,
					setupForDrawer ? 0 : SIDELIST_COLLAPSE_UNTIL_WIDTH_PX,
					setupForDrawer ? 0 : SIDELIST_KEEP_EXPANDED_AT_DP,
					SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP) {
				@Override
				public void expandedStateChanged(boolean expanded) {
					super.expandedStateChanged(expanded);
					if (sideActionsAdapter != null) {
						sideActionsAdapter.notifyDataSetChanged();
					}
					if (expanded) {
						SideSortAdapter sideSortAdapter = sideListHelper.getSideSortAdapter();
						if (sideSortAdapter != null) {
							sideSortAdapter.setViewType(0);
						}
						SideFilterAdapter sideFilterAdapter = sideListHelper.getSideTextFilterAdapter();
						if (sideFilterAdapter != null) {
							sideFilterAdapter.setViewType(0);
						}
						if (sideTagAdapter != null) {
							sideTagAdapter.notifyDataSetChanged();
						}
					}
				}

				@Override
				public void expandedStateChanging(boolean expanded) {
					super.expandedStateChanging(expanded);
					if (!expanded) {
						SideSortAdapter sideSortAdapter = sideListHelper.getSideSortAdapter();
						if (sideSortAdapter != null) {
							sideSortAdapter.setViewType(1);
						}
						SideFilterAdapter sideFilterAdapter = sideListHelper.getSideTextFilterAdapter();
						if (sideFilterAdapter != null) {
							sideFilterAdapter.setViewType(1);
						}
					}
				}

			};
			if (!sideListHelper.isValid()) {
				return;
			}

			// Could have used a ExpandableListView.. oh well
			if (showActionsArea) {
				sideListHelper.addEntry(view, R.id.sideactions_header,
						R.id.sideactions_list);
			}
			sideListHelper.addEntry(view, R.id.sidesort_header, R.id.sidesort_list);
			sideListHelper.addEntry(view, R.id.sidetag_header, R.id.sidetag_list);
			sideListHelper.addEntry(view, R.id.sidetextfilter_header,
					R.id.sidetextfilter_list);
		}

		View sideListArea = view.findViewById(R.id.sidelist_layout);

		if (sideListArea != null && sideListArea.getVisibility() == View.VISIBLE) {
			sideListHelper.setupSideTextFilter(view, R.id.sidetextfilter_list,
					R.id.sidefilter_text, listview, torrentListAdapter.getFilter());

			setupSideTags(view);

			sideListHelper.setupSideSort(view, R.id.sidesort_list,
					R.id.sidelist_sort_current, this);

			if (showActionsArea) {
				setupSideActions(view);
			}

			sideListHelper.expandedStateChanging(sideListHelper.isExpanded());
			sideListHelper.expandedStateChanged(sideListHelper.isExpanded());
		} else if (DEBUG) {
			Log.d(TAG,
					"setupSideListArea: sidelist not visible -- not setting up (until "
							+ "drawer is opened)");
		}

		if (sideListHelper.hasSideTextFilterArea()) {
			torrentListAdapter.getFilter().setBuildLetters(true);
		}
	}

	private void setupSideActions(View view) {
		RecyclerView oldRV = listSideActions;
		listSideActions = (RecyclerView) view.findViewById(R.id.sideactions_list);
		if (listSideActions == null) {
			return;
		}
		if (oldRV == listSideActions) {
			return;
		}

		listSideActions.setLayoutManager(new PreCachingLayoutManager(getContext()));

		sideActionsAdapter = new SideActionsAdapter(getContext(), remoteProfileID,
				R.menu.menu_torrent_list, new int[] {
					R.id.action_refresh,
					R.id.action_add_torrent,
					R.id.action_search,
					R.id.action_swarm_discoveries,
					R.id.action_subscriptions,
					R.id.action_start_all,
					R.id.action_stop_all,
					R.id.action_settings,
					R.id.action_social,
					R.id.action_logout,
					R.id.action_shutdown
				}, new SideActionsAdapter.SideActionSelectionListener() {
					@Override
					public boolean isRefreshing() {
						Session session = getSession();
						return session.torrent.isRefreshingList();
					}

					@Override
					public void onItemClick(SideActionsAdapter adapter, int position) {
						SideActionsAdapter.SideActionsInfo item = adapter.getItem(position);
						if (item == null) {
							return;
						}
						if (getActivity().onOptionsItemSelected(item.menuItem)) {
							return;
						}
						int itemId = item.menuItem.getItemId();
						if (itemId == R.id.action_social) {
							MenuBuilder menuBuilder = new MenuBuilder(getContext());
							MenuInflater menuInflater = getActivity().getMenuInflater();
							menuInflater.inflate(R.menu.menu_torrent_list, menuBuilder);
							getActivity().onPrepareOptionsMenu(menuBuilder);
							MenuItem itemSocial = menuBuilder.findItem(R.id.action_social);
							if (itemSocial != null) {
								SubMenu subMenu = itemSocial.getSubMenu();
								if (subMenu instanceof SubMenuBuilder) {
									((SubMenuBuilder) subMenu).setCallback(
											new MenuBuilder.Callback() {
												@Override
												public boolean onMenuItemSelected(MenuBuilder menu,
														MenuItem item) {
													return getActivity().onOptionsItemSelected(item);
												}

												@Override
												public void onMenuModeChange(MenuBuilder menu) {

												}
											});
									MenuDialogHelper menuDialogHelper = new MenuDialogHelper(
											(SubMenuBuilder) subMenu);
									menuDialogHelper.show(null);
								}
							}
						}
					}

					@Override
					public boolean onItemLongClick(SideActionsAdapter adapter,
							int position) {
						return false;
					}

					@Override
					public void onItemSelected(SideActionsAdapter adapter, int position,
							boolean isChecked) {

					}

					@Override
					public void onItemCheckedChanged(SideActionsAdapter adapter,
							SideActionsAdapter.SideActionsInfo item, boolean isChecked) {

					}
				}) {
			@Override
			public void prepareActionMenus(Menu menu) {
				Session session = getSession();
				TorrentViewActivity.prepareGlobalMenu(menu, session);
			}
		};
		listSideActions.setAdapter(sideActionsAdapter);
	}

	@NonNull
	@Thunk
	Session getSession() {
		return SessionManager.getSession(remoteProfileID, null, null);
	}

	private void setupSideTags(View view) {
		RecyclerView newListSideTags = (RecyclerView) view.findViewById(
				R.id.sidetag_list);
		if (newListSideTags != listSideTags) {
			listSideTags = newListSideTags;
			if (listSideTags == null) {
				return;
			}

			listSideTags.setLayoutManager(new PreCachingLayoutManager(getContext()));

			sideTagAdapter = new SideTagAdapter(getContext(), remoteProfileID,
					new FlexibleRecyclerSelectionListener<SideTagAdapter, SideTagAdapter.SideTagInfo>() {
						@Override
						public void onItemClick(SideTagAdapter adapter, int position) {
						}

						@Override
						public boolean onItemLongClick(SideTagAdapter adapter,
								int position) {
							return false;
						}

						@Override
						public void onItemSelected(SideTagAdapter adapter, int position,
								boolean isChecked) {
						}

						@Override
						public void onItemCheckedChanged(SideTagAdapter adapter,
								SideTagAdapter.SideTagInfo item, boolean isChecked) {

							if (!isChecked) {
								return;
							}
							adapter.setItemChecked(item, false);

							filterBy(item.id, MapUtils.getMapString(item.tag, "name", ""),
									true);
						}
					});

			listSideTags.setAdapter(sideTagAdapter);
		} else {
			sideTagAdapter.removeAllItems();
		}

		Session session = getSession();
		if (DEBUG) {
			Log.d(TAG,
					"setupSideTags: supports? "
							+ session.getSupports(RPCSupports.SUPPORTS_TAGS) + "/"
							+ session.tag.getTags());
		}
		if (!session.getSupports(RPCSupports.SUPPORTS_TAGS)) {
			// TRANSMISSION
			ValueStringArray filterByList = AndroidUtils.getValueStringArray(
					getResources(), R.array.filterby_list);

			for (int i = 0; i < filterByList.strings.length; i++) {
				long id = filterByList.values[i];
				Map map = new HashMap(1);
				map.put("uid", id);
				SideTagAdapter.SideTagInfo sideTagInfo = new SideTagAdapter.SideTagInfo(
						map);
				sideTagAdapter.addItem(sideTagInfo);
			}
		} else {
			List<Map<?, ?>> tags = session.tag.getTags();
			if (tags != null && tags.size() > 0) {
				tagListReceived(tags);
			}
		}
	}

	@Override
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_UP) {
			return false;
		}
		switch (keyCode) {
			// NOTE:
			// KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_MENU);
			case KeyEvent.KEYCODE_MENU:
			case KeyEvent.KEYCODE_BUTTON_X:
			case KeyEvent.KEYCODE_INFO: {
				return showTorrentContextMenu();
			}

		}
		return false;
	}

	@Thunk
	boolean showTorrentContextMenu() {
		int selectedPosition = torrentListAdapter.getSelectedPosition();
		if (selectedPosition < 0) {
			return false;
		}
		String s;
		int checkedItemCount = torrentListAdapter.getCheckedItemCount();
		if (checkedItemCount <= 1) {
			Map<?, ?> item = torrentListAdapter.getTorrentItem(selectedPosition);
			s = getResources().getString(R.string.torrent_actions_for,
					MapUtils.getMapString(item, "name", "???"));
		} else {
			s = getResources().getQuantityString(
					R.plurals.torrent_actions_for_multiple, checkedItemCount,
					checkedItemCount);
		}

		return AndroidUtilsUI.popupContextMenu(getContext(), mActionModeCallback,
				s);
	}

	@Override
	public void sessionSettingsChanged(SessionSettings newSessionSettings) {
		Session session = getSession();
		boolean isSmallNew = session.getRemoteProfile().useSmallLists();
		if (isSmall != null && isSmallNew != isSmall) {
			torrentListAdapter.setViewType(isSmallNew ? 1 : 0);
		}
		isSmall = isSmallNew;

		if (sideActionsAdapter != null) {
			sideActionsAdapter.updateMenuItems();
		}
	}

	@Override
	public void speedChanged(long downloadSpeed, long uploadSpeed) {

	}

	@Override
	public void rpcTorrentListRefreshingChanged(final boolean refreshing) {
		AndroidUtilsUI.runOnUIThread(this, new AndroidUtils.RunnableWithActivity() {
			@Override
			public void run() {
				if (sideActionsAdapter != null) {
					sideActionsAdapter.updateRefreshButton();
				}
			}
		});
	}

	@Override
	public void onlineStateChanged(boolean isOnline, boolean isOnlineMobile) {
		if (sideActionsAdapter == null) {
			return;
		}
		AndroidUtilsUI.runOnUIThread(this, new AndroidUtils.RunnableWithActivity() {
			@Override
			public void run() {
				if (getActivity() == null) {
					return;
				}
				if (sideActionsAdapter != null) {
					sideActionsAdapter.updateMenuItems();
				}
			}
		});
	}

	private static class LastUpdatedInfo
	{
		final long sinceMS;

		final String s;

		public LastUpdatedInfo(long sinceMS, String s) {
			this.sinceMS = sinceMS;
			this.s = s;
		}

	}

	@Nullable
	@Thunk
	LastUpdatedInfo getLastUpdatedString() {
		FragmentActivity activity = getActivity();
		if (activity == null) {
			return null;
		}
		Session session = getSession();
		long lastUpdated = session.torrent.getLastListReceivedOn();
		if (lastUpdated == 0) {
			return new LastUpdatedInfo(0, "");
		}
		long sinceMS = System.currentTimeMillis() - lastUpdated;
		String since = DateUtils.getRelativeDateTimeString(activity, lastUpdated,
				DateUtils.SECOND_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString();
		String s = activity.getResources().getString(R.string.last_updated, since);

		return new LastUpdatedInfo(sinceMS, s);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		if (DEBUG) {
			Log.d(TAG, "onSaveInstanceState");
		}
		if (torrentListAdapter != null) {
			torrentListAdapter.onSaveInstanceState(outState);
		}
		if (sideListHelper != null) {
			sideListHelper.onSaveInstanceState(outState);
		}
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		if (DEBUG) {
			Log.d(TAG, "onViewStateRestored");
		}
		super.onViewStateRestored(savedInstanceState);
		if (torrentListAdapter != null) {
			torrentListAdapter.onRestoreInstanceState(savedInstanceState, listview);
		}
		if (sideListHelper != null) {
			sideListHelper.onRestoreInstanceState(savedInstanceState);
		}
		if (listview != null) {
			updateCheckedIDs();
		}
	}

	@Override
	public void onStart() {
		if (DEBUG) {
			Log.d(TAG, "onStart");
		}
		super.onStart();
		VuzeEasyTracker.getInstance(this).fragmentStart(this, TAG);
	}

	/* (non-Javadoc)
	 * @see android.support.v4.app.Fragment#onResume()
	 */
	@Override
	public void onResume() {
		if (DEBUG) {
			Log.d(TAG, "onResume");
		}
		super.onResume();

		VuzeRemoteApp.getNetworkState().addListener(this);

		Session session = getSession();
		setupSideListArea();

		torrentListAdapter.setSession(session);
		session.torrent.addListReceivedListener(TAG, this);
		session.tag.addTagListReceivedListener(this);
		session.addSessionListener(this);
		session.addSessionSettingsChangedListeners(this);
		session.torrent.addTorrentListRefreshingListener(this, false);

		if (sideListHelper != null) {
			sideListHelper.onResume();
		}
	}

	/* (non-Javadoc)
	 * @see android.support.v4.app.Fragment#onPause()
	 */
	@Override
	public void onPause() {
		VuzeRemoteApp.getNetworkState().removeListener(this);

		if (SessionManager.hasSession(remoteProfileID)) {
			Session session = getSession();
			session.tag.removeTagListReceivedListener(this);
			session.torrent.removeListReceivedListener(this);
			session.torrent.removeListRefreshingListener(this);
			session.removeSessionSettingsChangedListeners(this);
		}
		super.onPause();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		if (DEBUG) {
			Log.d(TAG, "onActivityCreated");
		}
		FragmentActivity activity = getActivity();
		tvFilteringBy = (TextView) activity.findViewById(R.id.wvFilteringBy);
		tvTorrentCount = (TextView) activity.findViewById(R.id.wvTorrentCount);
		tvEmpty = (TextView) activity.findViewById(R.id.tv_empty);
		if (tvEmpty != null) {
			tvEmpty.setText(R.string.torrent_list_empty);
		}

		super.onActivityCreated(savedInstanceState);
	}

	@Thunk
	void finishActionMode() {
		if (mActionMode != null) {
			mActionMode.finish();
			mActionMode = null;
		}
		torrentListAdapter.clearChecked();
	}

	private static Map<?, ?>[] getCheckedTorrentMaps(TorrentListAdapter adapter) {
		if (adapter == null) {
			return new Map[0];
		}
		int[] checkedItems = adapter.getCheckedItemPositions();
		if (checkedItems.length == 0) {
			int selectedPosition = adapter.getSelectedPosition();
			if (selectedPosition < 0) {
				return new Map[0];
			}
			checkedItems = new int[] {
				selectedPosition
			};
		}

		List<Map> list = new ArrayList<>(checkedItems.length);

		for (int position : checkedItems) {
			Map<?, ?> torrent = adapter.getTorrentItem(position);
			if (torrent != null) {
				list.add(torrent);
			}
		}

		return list.toArray(new Map[list.size()]);
	}

	@Thunk
	static long[] getCheckedIDs(TorrentListAdapter adapter,
			boolean includeSelected) {

		List<Long> list = getCheckedIDsList(adapter, includeSelected);

		long[] longs = new long[list.size()];
		for (int i = 0; i < list.size(); i++) {
			longs[i] = list.get(i);
		}

		return longs;
	}

	private static List<Long> getCheckedIDsList(TorrentListAdapter adapter,
			boolean includeSelected) {
		List<Long> list = new ArrayList<>();
		if (adapter == null) {
			return list;
		}
		int[] checkedItems = adapter.getCheckedItemPositions();

		if (checkedItems.length == 0) {
			if (!includeSelected) {
				return list;
			}
			int selectedPosition = adapter.getSelectedPosition();
			if (selectedPosition < 0) {
				return list;
			}
			long torrentID = adapter.getTorrentID(selectedPosition);
			if (torrentID >= 0) {
				list.add(torrentID);
			}
			return list;
		} else {
			for (int position : checkedItems) {
				long torrentID = adapter.getTorrentID(position);
				if (torrentID >= 0) {
					list.add(torrentID);
				}
			}
		}

		return list;
	}

	@Override
	public void rpcTorrentListReceived(String callID, List<?> addedTorrentMaps,
			List<?> removedTorrentIDs) {
		if ((addedTorrentMaps == null || addedTorrentMaps.size() == 0)
				&& (removedTorrentIDs == null || removedTorrentIDs.size() == 0)) {
			if (torrentListAdapter.isNeverSetItems()) {
				torrentListAdapter.triggerEmptyList();
			}
			return;
		}
		AndroidUtilsUI.runOnUIThread(this, new AndroidUtils.RunnableWithActivity() {
			@Override
			public void run() {
				if (getActivity() == null) {
					return;
				}
				if (torrentListAdapter == null) {
					return;
				}
				torrentListAdapter.refreshDisplayList();
			}
		});
	}

	/* (non-Javadoc)
		 * @see android.support.v4.app.Fragment#onOptionsItemSelected(android.view
		 * .MenuItem)
		 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "onOptionsItemSelected " + item.getTitle());
		}

		if (sideListHelper != null && sideListHelper.onOptionsItemSelected(item)) {
			return true;
		}

		return handleFragmentMenuItems(item.getItemId())
				|| super.onOptionsItemSelected(item);
	}

	@Thunk
	boolean handleFragmentMenuItems(int itemId) {
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "HANDLE MENU FRAG " + itemId);
		}
		return handleTorrentMenuActions(remoteProfileID,
				getCheckedIDs(torrentListAdapter, true), getFragmentManager(), itemId);
	}

	public static boolean handleTorrentMenuActions(String remoteProfileID,
			final long[] ids, FragmentManager fm, int itemId) {
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "HANDLE TORRENTMENU FRAG " + itemId);
		}
		if (ids == null || ids.length == 0) {
			return false;
		}
		if (itemId == R.id.action_sel_remove) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			for (long torrentID : ids) {
				Map<?, ?> map = session.torrent.getCachedTorrent(torrentID);
				long id = MapUtils.getMapLong(map, "id", -1);
				boolean isMagnetTorrent = TorrentUtils.isMagnetTorrent(
						session.torrent.getCachedTorrent(id));
				if (!isMagnetTorrent) {
					String name = MapUtils.getMapString(map, "name", "");
					// TODO: One at a time!
					DialogFragmentDeleteTorrent.open(fm, session, name, id);
				}
			}
			return true;
		} else if (itemId == R.id.action_sel_start) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			session.torrent.startTorrents(ids, false);
			return true;
		} else if (itemId == R.id.action_sel_forcestart) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			session.torrent.startTorrents(ids, true);
			return true;
		} else if (itemId == R.id.action_sel_stop) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			session.torrent.stopTorrents(ids);
			return true;
		} else if (itemId == R.id.action_sel_relocate) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			Map<?, ?> mapFirst = session.torrent.getCachedTorrent(ids[0]);
			DialogFragmentMoveData.openMoveDataDialog(mapFirst, session, fm);
			return true;
		} else if (itemId == R.id.action_sel_move_top) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			session.executeRpc(new RpcExecuter() {
				@Override
				public void executeRpc(TransmissionRPC rpc) {
					rpc.simpleRpcCall("queue-move-top", ids, null);
				}
			});
			return true;
		} else if (itemId == R.id.action_sel_move_up) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			session.executeRpc(new RpcExecuter() {
				@Override
				public void executeRpc(TransmissionRPC rpc) {
					rpc.simpleRpcCall("queue-move-up", ids, null);
				}
			});
			return true;
		} else if (itemId == R.id.action_sel_move_down) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			session.executeRpc(new RpcExecuter() {
				@Override
				public void executeRpc(TransmissionRPC rpc) {
					rpc.simpleRpcCall("queue-move-down", ids, null);
				}
			});
			return true;
		} else if (itemId == R.id.action_sel_move_bottom) {
			Session session = SessionManager.getSession(remoteProfileID, null, null);
			session.executeRpc(new RpcExecuter() {
				@Override
				public void executeRpc(TransmissionRPC rpc) {
					rpc.simpleRpcCall("queue-move-bottom", ids, null);
				}
			});
			return true;
		}
		return false;

	}

	@Thunk
	void updateActionModeText(ActionMode mode) {
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "MULTI:CHECK CHANGE");
		}

		if (mode != null) {
			String subtitle = getResources().getString(
					R.string.context_torrent_subtitle_selected,
					torrentListAdapter.getCheckedItemCount());
			mode.setSubtitle(subtitle);
		}
	}

	private void setupActionModeCallback() {
		mActionModeCallback = new Callback() {

			// Called when the action mode is created; startActionMode() was called
			@Override
			public boolean onCreateActionMode(ActionMode mode, Menu menu) {

				if (AndroidUtils.DEBUG_MENU) {
					Log.d(TAG, "onCreateActionMode");
				}

				if (mode == null && torrentListAdapter.getCheckedItemCount() == 0
						&& torrentListAdapter.getSelectedPosition() < 0) {
					return false;
				}

				if (mode != null) {
					mode.setTitle(R.string.context_torrent_title);
				}
				getActivity().getMenuInflater().inflate(
						R.menu.menu_context_torrent_details, menu);

				TorrentDetailsFragment frag = (TorrentDetailsFragment) getActivity().getSupportFragmentManager().findFragmentById(
						R.id.frag_torrent_details);
				if (frag != null) {
					frag.onCreateActionMode(mode, menu);
				}

				if (sideListHelper == null || !sideListHelper.isValid()) {
					SubMenu subMenu = menu.addSubMenu(R.string.menu_global_actions);
					subMenu.setIcon(R.drawable.ic_menu_white_24dp);
					MenuItemCompat.setShowAsAction(subMenu.getItem(),
							MenuItemCompat.SHOW_AS_ACTION_NEVER);

					try {
						// Place "Global" actions on top bar in collapsed menu
						MenuInflater mi = mode == null ? getActivity().getMenuInflater()
								: mode.getMenuInflater();
						mi.inflate(R.menu.menu_torrent_list, subMenu);
						onPrepareOptionsMenu(subMenu);
					} catch (UnsupportedOperationException e) {
						Log.e(TAG, e.getMessage());
						menu.removeItem(subMenu.getItem().getItemId());
					}
				}

				if (AndroidUtils.usesNavigationControl()) {
					MenuItem add = menu.add(R.string.select_multiple_items);
					add.setCheckable(true);
					add.setChecked(torrentListAdapter.isMultiCheckMode());
					add.setOnMenuItemClickListener(
							new MenuItem.OnMenuItemClickListener() {
								@Override
								public boolean onMenuItemClick(MenuItem item) {
									boolean turnOn = !torrentListAdapter.isMultiCheckModeAllowed();

									torrentListAdapter.setMultiCheckModeAllowed(turnOn);
									if (turnOn) {
										torrentListAdapter.setMultiCheckMode(true);
										torrentListAdapter.setItemChecked(
												torrentListAdapter.getSelectedPosition(), true);
									}
									return true;
								}
							});
				}

				return true;
			}

			// Called each time the action mode is shown. Always called after
			// onCreateActionMode, but
			// may be called multiple times if the mode is invalidated.
			@Override
			public boolean onPrepareActionMode(ActionMode mode, Menu menu) {

				if (AndroidUtils.DEBUG_MENU) {
					Log.d(TAG, "MULTI:onPrepareActionMode " + mode);
				}

				// Must be called first, because our drawer sets all menu items
				// visible.. :(
				getActivity().onPrepareOptionsMenu(menu);

				prepareContextMenu(menu);

				TorrentDetailsFragment frag = (TorrentDetailsFragment) getActivity().getSupportFragmentManager().findFragmentById(
						R.id.frag_torrent_details);
				if (frag != null) {
					frag.onPrepareActionMode(menu);
				}

				AndroidUtils.fixupMenuAlpha(menu);

				return true;
			}

			// Called when the user selects a contextual menu item
			@Override
			public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
				if (AndroidUtils.DEBUG_MENU) {
					Log.d(TAG, "onActionItemClicked " + item.getTitle());
				}

				if (TorrentListFragment.this.handleFragmentMenuItems(
						item.getItemId())) {
					return true;
				}
				if (getActivity().onOptionsItemSelected(item)) {
					return true;
				}
				TorrentDetailsFragment frag = (TorrentDetailsFragment) getActivity().getSupportFragmentManager().findFragmentById(
						R.id.frag_torrent_details);
				if (frag != null) {
					if (frag.onActionItemClicked(item)) {
						return true;
					}
				}
				return false;
			}

			// Called when the user exits the action mode
			@Override
			public void onDestroyActionMode(ActionMode mode) {
				if (AndroidUtils.DEBUG_MENU) {
					Log.d(TAG,
							"onDestroyActionMode. BeingReplaced?" + actionModeBeingReplaced);
				}

				mActionMode = null;

				if (!actionModeBeingReplaced) {
					listview.post(new Runnable() {
						@Override
						public void run() {
							torrentListAdapter.setMultiCheckMode(false);
							torrentListAdapter.clearChecked();
							updateCheckedIDs();
						}
					});

					listview.post(new Runnable() {
						@Override
						public void run() {
							if (mCallback != null) {
								mCallback.actionModeBeingReplacedDone();
							}
						}
					});

					listview.setLongClickable(true);
					listview.requestLayout();
					AndroidUtilsUI.invalidateOptionsMenuHC(getActivity(), mActionMode);
				}
			}
		};
	}

	@Thunk
	void prepareContextMenu(Menu menu) {
		Session session = getSession();
		boolean isLocalHost = session.getRemoteProfile().isLocalHost();
		boolean isOnlineOrLocal = VuzeRemoteApp.getNetworkState().isOnline()
				|| isLocalHost;

		MenuItem menuMove = menu.findItem(R.id.action_sel_move);
		if (menuMove != null) {
			boolean enabled = isOnlineOrLocal
					&& (torrentListAdapter.getCheckedItemCount() > 0
							|| torrentListAdapter.getSelectedPosition() >= 0);
			menuMove.setEnabled(enabled);
		}

		Map<?, ?>[] checkedTorrentMaps = getCheckedTorrentMaps(torrentListAdapter);
		boolean canStart = false;
		boolean canStop = false;
		if (isOnlineOrLocal) {
			boolean allMagnets = checkedTorrentMaps.length > 0;
			for (Map<?, ?> mapTorrent : checkedTorrentMaps) {
				int status = MapUtils.getMapInt(mapTorrent,
						TransmissionVars.FIELD_TORRENT_STATUS,
						TransmissionVars.TR_STATUS_STOPPED);
				boolean isMagnet = TorrentUtils.isMagnetTorrent(mapTorrent);
				if (!isMagnet) {
					allMagnets = false;
					canStart |= status == TransmissionVars.TR_STATUS_STOPPED;
					canStop |= status != TransmissionVars.TR_STATUS_STOPPED;
				}
				if (AndroidUtils.DEBUG_MENU) {
					Log.d(TAG,
							"prepareContextMenu: " + canStart + "/" + canStop + "/" + status);
				}
			}

			if (allMagnets) {
				AndroidUtilsUI.setManyMenuItemsVisible(false, menu, new int[] {
					R.id.action_sel_forcestart,
					R.id.action_sel_move,
					R.id.action_sel_relocate
				});
			}
		}
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "prepareContextMenu: " + canStart + "/" + canStop);
		}

		MenuItem menuStart = menu.findItem(R.id.action_sel_start);
		if (menuStart != null) {
			menuStart.setVisible(canStart);
			menuStart.setEnabled(canStart);
		} else {
			Log.d(TAG, "prepareContextMenu: No Start Menu!");
		}

		MenuItem menuStop = menu.findItem(R.id.action_sel_stop);
		if (menuStop != null) {
			menuStop.setVisible(canStop);
			menuStop.setEnabled(canStop);
		}

		AndroidUtilsUI.setManyMenuItemsEnabled(isOnlineOrLocal, menu, new int[] {
			R.id.action_sel_remove,
			R.id.action_sel_forcestart,
			R.id.action_sel_move,
			R.id.action_sel_relocate
		});
	}

	@Thunk
	boolean showContextualActions(boolean forceRebuild) {
		if (AndroidUtils.isTV()) {
			// TV doesn't get action bar changes, because it's impossible to get to
			// with remote control when you are on row 4000
			return false;
		}
		if (mActionMode != null && !forceRebuild) {
			if (AndroidUtils.DEBUG_MENU) {
				Log.d(TAG, "showContextualActions: invalidate existing");
			}
			mActionMode.invalidate();
			return false;
		}

		if (mCallback != null) {
			mCallback.setActionModeBeingReplaced(mActionMode, true);
		}
		// Start the CAB using the ActionMode.Callback defined above
		FragmentActivity activity = getActivity();
		if (activity instanceof AppCompatActivity) {
			AppCompatActivity abActivity = (AppCompatActivity) activity;
			ActionBar ab = abActivity.getSupportActionBar();

			if (AndroidUtils.DEBUG_MENU) {
				Log.d(TAG,
						"showContextualActions: startAB. mActionMode = " + mActionMode
								+ "; isShowing=" + (ab == null ? null : ab.isShowing()));
			}

			actionModeBeingReplaced = true;

			mActionMode = abActivity.startSupportActionMode(mActionModeCallback);
			actionModeBeingReplaced = false;
			if (mActionMode != null) {
				mActionMode.setSubtitle(R.string.multi_select_tip);
				mActionMode.setTitle(R.string.context_torrent_title);
			}
		}
		if (mCallback != null) {
			mCallback.setActionModeBeingReplaced(mActionMode, false);
		}

		return true;
	}

	@Thunk
	void filterBy(final long filterMode, final String name, boolean save) {
		if (DEBUG) {
			Log.d(TAG, "FILTER BY " + name);
		}

		AndroidUtilsUI.runOnUIThread(this, new Runnable() {
			public void run() {
				if (getActivity() == null) {
					return;
				}
				if (torrentListAdapter == null) {
					if (DEBUG) {
						Log.d(TAG, "No torrentListAdapter in filterBy");
					}
					return;
				}
				// java.lang.RuntimeException: Can't create handler inside thread that
				// has not called Looper.prepare()
				TorrentFilter filter = torrentListAdapter.getFilter();
				filter.setFilterMode(filterMode);
				if (tvFilteringBy != null) {
					Session session = getSession();
					Map<?, ?> tag = session.tag.getTag(filterMode);
					SpanTags spanTags = new SpanTags();
					spanTags.init(getContext(), session, tvFilteringBy, null);
					spanTags.setCountFontRatio(0.8f);
					if (tag == null) {
						spanTags.addTagNames(Collections.singletonList(name));
					} else {
						ArrayList<Map<?, ?>> arrayList = new ArrayList<>(1);
						arrayList.add(tag);
						spanTags.setTagMaps(arrayList);
					}
					spanTags.setShowIcon(false);
					spanTags.updateTags();
				} else {
					if (DEBUG) {
						Log.d(TAG, "null field in filterBy");
					}
				}
			}
		});
		if (save) {
			Session session = getSession();
			session.getRemoteProfile().setFilterBy(filterMode);
			session.saveProfile();
		}
	}

	@Override
	public void sortBy(final String[] sortFieldIDs, final Boolean[] sortOrderAsc,
			final int which, boolean save) {
		if (DEBUG) {
			Log.d(TAG, "SORT BY " + Arrays.toString(sortFieldIDs));
		}
		if (torrentListAdapter != null) {
			torrentListAdapter.setSort(sortFieldIDs, sortOrderAsc);
		}
		AndroidUtilsUI.runOnUIThread(this, new Runnable() {
			public void run() {
				if (getActivity() == null) {
					return;
				}
				sideListHelper.setCurrentSort(TorrentListFragment.this.getContext(),
						which, sortOrderAsc[0]);
			}
		});

		Session session = getSession();
		if (save) {
			session.getRemoteProfile().setSortBy("", sortFieldIDs, sortOrderAsc);
			session.saveProfile();
		}
	}

	@Override
	public SortByFields[] getSortByFields(Context context) {
		if (sortByFields != null) {
			return sortByFields;
		}
		String[] sortNames = context.getResources().getStringArray(
				R.array.sortby_list);

		sortByFields = new SortByFields[sortNames.length];
		int i = 0;

		// <item>Queue Order</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_POSITION
		}, new Boolean[] {
			true
		});

		i++; // <item>Activity</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_RATE_DOWNLOAD,
			TransmissionVars.FIELD_TORRENT_RATE_UPLOAD
		}, new Boolean[] {
			false
		});

		i++; // <item>Age</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_DATE_ADDED
		}, new Boolean[] {
			false
		});

		i++; // <item>Progress</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_PERCENT_DONE
		}, new Boolean[] {
			false
		});

		i++; // <item>Ratio</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_UPLOAD_RATIO
		}, new Boolean[] {
			false
		});

		i++; // <item>Size</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_SIZE_WHEN_DONE
		}, new Boolean[] {
			false
		});

		i++; // <item>State</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_STATUS
		}, new Boolean[] {
			false
		});

		i++; // <item>ETA</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_ETA,
			TransmissionVars.FIELD_TORRENT_PERCENT_DONE
		}, new Boolean[] {
			true,
			false
		});

		i++; // <item>Count</item>
		sortByFields[i] = new SortByFields(i, sortNames[i], new String[] {
			TransmissionVars.FIELD_TORRENT_FILE_COUNT,
			TransmissionVars.FIELD_TORRENT_SIZE_WHEN_DONE
		}, new Boolean[] {
			true,
			false
		});

		return sortByFields;
	}

	@Override
	public void flipSortOrder() {
		Session session = getSession();
		RemoteProfile remoteProfile = session.getRemoteProfile();
		Boolean[] sortOrder = remoteProfile.getSortOrderAsc("", true);
		if (sortOrder == null) {
			return;
		}
		for (int i = 0; i < sortOrder.length; i++) {
			sortOrder[i] = !sortOrder[i];
		}
		String[] sortBy = remoteProfile.getSortBy("",
				TransmissionVars.FIELD_TORRENT_NAME);
		sortBy(sortBy, sortOrder, TorrentUtils.findSordIdFromTorrentFields(sortBy,
				getSortByFields(getContext())), true);
	}

	@Thunk
	void updateTorrentCount(final long total) {
		if (tvTorrentCount == null) {
			return;
		}
		AndroidUtilsUI.runOnUIThread(this, new Runnable() {
			public void run() {
				if (getActivity() == null) {
					return;
				}
				String s = "";
				if (total != 0) {
					String constraint = torrentListAdapter.getFilter().getConstraint();
					if (constraint != null && constraint.length() > 0) {
						s = getResources().getQuantityString(R.plurals.torrent_count,
								(int) total, total);
					}
				} else {

					if (tvEmpty != null) {
						Session session = getSession();
						LongSparseArray<Map<?, ?>> torrentList = session.torrent.getListAsSparseArray();
						int size = torrentList.size();
						tvEmpty.setText(size > 0 ? R.string.list_filtered_empty
								: R.string.torrent_list_empty);
					}

				}
				tvTorrentCount.setText(s);
			}
		});
	}

	/* (non-Javadoc)
	 * @see com.vuze.android.remote.fragment
	 * .ActionModeBeingReplacedListener#setActionModeBeingReplaced(boolean)
	 */
	@Override
	public void setActionModeBeingReplaced(ActionMode actionMode,
			boolean actionModeBeingReplaced) {
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG,
					"setActionModeBeingReplaced: replaced? " + actionModeBeingReplaced
							+ "; hasActionMode? " + (mActionMode != null));
		}
		this.actionModeBeingReplaced = actionModeBeingReplaced;
		if (actionModeBeingReplaced) {
			rebuildActionMode = mActionMode != null;
			if (rebuildActionMode) {
				mActionMode.finish();
				mActionMode = null;
			}
		}
	}

	/* (non-Javadoc)
	 * @see com.vuze.android.remote.fragment
	 * .ActionModeBeingReplacedListener#actionModeBeingReplacedDone()
	 */
	@Override
	public void actionModeBeingReplacedDone() {
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "actionModeBeingReplacedDone: rebuild? " + rebuildActionMode);
		}
		if (rebuildActionMode) {
			rebuildActionMode = false;

			rebuildActionMode();
			torrentListAdapter.setMultiCheckMode(false);
		}
	}

	/* (non-Javadoc)
	 * @see com.vuze.android.remote.fragment
	 * .ActionModeBeingReplacedListener#getActionMode()
	 */
	@Override
	public ActionMode getActionMode() {
		return mActionMode;
	}

	public void clearSelection() {
		finishActionMode();
	}

	@Thunk
	void updateCheckedIDs() {
		List<Long> checkedTorrentIDs = getCheckedIDsList(torrentListAdapter, false);
		if (mCallback != null) {

			long[] longs = new long[checkedTorrentIDs.size()];
			for (int i = 0; i < checkedTorrentIDs.size(); i++) {
				longs[i] = checkedTorrentIDs.get(i);
			}

			mCallback.onTorrentSelectedListener(TorrentListFragment.this, longs,
					torrentListAdapter.isMultiCheckMode());
		}
		if (checkedTorrentIDs.size() == 0 && mActionMode != null) {
			mActionMode.finish();
		}
	}

	@Override
	public void rebuildActionMode() {
		showContextualActions(true);
	}

	public void startStopTorrents() {
		Map<?, ?>[] checkedTorrentMaps = getCheckedTorrentMaps(torrentListAdapter);
		if (checkedTorrentMaps == null || checkedTorrentMaps.length == 0) {
			return;
		}
		//boolean canStart = false;
		boolean canStop = false;
		for (Map<?, ?> mapTorrent : checkedTorrentMaps) {
			int status = MapUtils.getMapInt(mapTorrent,
					TransmissionVars.FIELD_TORRENT_STATUS,
					TransmissionVars.TR_STATUS_STOPPED);
			//canStart |= status == TransmissionVars.TR_STATUS_STOPPED;
			canStop |= status != TransmissionVars.TR_STATUS_STOPPED;
		}

		Session session = getSession();
		if (!canStop) {
			long[] ids = getCheckedIDs(torrentListAdapter, true);
			session.torrent.stopTorrents(ids);
		} else {
			long[] ids = getCheckedIDs(torrentListAdapter, true);
			session.torrent.startTorrents(ids, false);
		}
	}

	@Override
	public Callback getActionModeCallback() {
		return mActionModeCallback;
	}

	@Override
	public void tagListReceived(List<Map<?, ?>> tags) {
		if (sideTagAdapter == null || tags == null) {
			return;
		}
		List<SideTagAdapter.SideTagInfo> list = new ArrayList<>(tags.size());
		for (Map tag : tags) {
			if (MapUtils.getMapLong(tag, TransmissionVars.FIELD_TAG_COUNT, 0) > 0) {
				list.add(new SideTagAdapter.SideTagInfo(tag));
			}
		}
		sideTagAdapter.setItems(list);

		getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (tvFilteringBy == null || getActivity() == null) {
					return;
				}
				tvFilteringBy.invalidate();
			}
		});
	}

	public void onDrawerOpened(View view) {
		setupSideListArea();
	}
}
