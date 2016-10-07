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

package com.vuze.android.remote;

import java.util.*;

import com.vuze.android.FlexibleRecyclerSelectionListener;
import com.vuze.android.remote.activity.DrawerActivity;
import com.vuze.android.remote.adapter.LetterFilter;
import com.vuze.android.remote.adapter.SideFilterAdapter;
import com.vuze.android.remote.adapter.SideSortAdapter;
import com.vuze.android.util.AnimatorEndListener;
import com.vuze.android.util.OnSwipeTouchListener;
import com.vuze.android.widget.FlingLinearLayout;
import com.vuze.android.widget.PreCachingLayoutManager;

import android.animation.Animator;
import android.animation.LayoutTransition;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.graphics.drawable.DrawerArrowDrawable;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.*;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Created by TuxPaper on 6/14/16.
 */
public abstract class SideListHelper
{
	static final String TAG = "SideListHelper";

	/* @Thunk */ final FragmentActivity activity;

	private final View parentView;

	private OnSwipeTouchListener expandTouchListener;

	/* @Thunk */ LinearLayout sideListArea;

	/* @Thunk */ Boolean sidelistIsExpanded = null;

	/* @Thunk */ Boolean sidelistInFocus = null;

	/* @Thunk */ ViewGroup sidebarViewActive = null;

	/* @Thunk */ boolean hideUnselectedSideHeaders = false;

	/* @Thunk */ List<ViewGroup> listHeaderViewGroups = new ArrayList<>();

	private List<ViewGroup> listBodyViewGroups = new ArrayList<>();

	private final int sideListAreaID;

	private int SIDELIST_COLLAPSE_UNTIL_WIDTH_PX;

	private final int SIDELIST_KEEP_EXPANDED_AT_DP;

	private final int SIDELIST_MIN_WIDTH;

	private final int SIDELIST_MAX_WIDTH;

	/* @Thunk */ final int SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP;

	private final int SIDELIST_DURATION_MS = 300;

	private DrawerArrowDrawable mDrawerArrow;

	private Animation.AnimationListener animationListener;

	// >> SideSort
	private RecyclerView listSideSort;

	private TextView tvSortCurrent;

	private SideSortAdapter sideSortAdapter;

	/* @Thunk */ SideSortAPI sidesortAPI;

	// << SideSort

	// >> SideTextFilter
	/* @Thunk */ RecyclerView listSideTextFilter;

	/* @Thunk */ TextView tvSideFilterText;

	/* @Thunk */ SideFilterAdapter sideTextFilterAdapter;

	/* @Thunk */ LetterFilter letterFilter;
	// << SideTextFilter

	public SideListHelper(FragmentActivity activity, View parentView,
			int sideListAreaID, int SIDELIST_MIN_WIDTH, int SIDELIST_MAX_WIDTH,
			int SIDELIST_COLLAPSE_UNTIL_WIDTH_PX, int SIDELIST_KEEP_EXPANDED_AT_DP,
			int SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP) {
		this.activity = activity;
		this.parentView = parentView;
		this.sideListAreaID = sideListAreaID;
		this.SIDELIST_COLLAPSE_UNTIL_WIDTH_PX = SIDELIST_COLLAPSE_UNTIL_WIDTH_PX;
		this.SIDELIST_KEEP_EXPANDED_AT_DP = SIDELIST_KEEP_EXPANDED_AT_DP;
		this.SIDELIST_MIN_WIDTH = SIDELIST_MIN_WIDTH;
		this.SIDELIST_MAX_WIDTH = SIDELIST_MAX_WIDTH;
		this.SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP = SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP;

		sideListArea = (LinearLayout) parentView.findViewById(sideListAreaID);
		if (sideListArea != null) {
			if (!AndroidUtils.hasTouchScreen()) {
				// Switch SideList width based on focus.  For touch screens, we use
				// touch events.  For non-touch screens (TV) we watch for focus changes
				ViewTreeObserver vto = sideListArea.getViewTreeObserver();
				vto.addOnGlobalFocusChangeListener(
						new ViewTreeObserver.OnGlobalFocusChangeListener() {

							@Override
							public void onGlobalFocusChanged(View oldFocus, View newFocus) {

								boolean isChildOfSideList = AndroidUtilsUI.isChildOf(newFocus,
										sideListArea);
								boolean isHeader = AndroidUtilsUI.childOrParentHasTag(newFocus,
										"sideheader");
								if ((sidelistIsExpanded == null || sidelistIsExpanded)
										&& !isChildOfSideList) {
									//left focus
									sidelistInFocus = false;
									expandSideListWidth(false);
								} else if ((sidelistIsExpanded == null || !sidelistIsExpanded)
										&& isHeader) {
									sidelistInFocus = true;
									expandSideListWidth(true);
								}
							}
						});
			}

			expandTouchListener = new OnSwipeTouchListener(activity) {

				@Override
				public void onSwipeLeft() {
					expandSideListWidth(false);
				}

				@Override
				public void onSwipeRight() {
					expandSideListWidth(true);
				}
			};
			if (sideListArea instanceof FlingLinearLayout) {
				((FlingLinearLayout) sideListArea).setOnSwipeListener(
						new FlingLinearLayout.OnSwipeListener() {
							@Override
							public void onSwipe(View view, int direction) {
								expandSideListWidth(
										direction == FlingLinearLayout.LEFT_TO_RIGHT);
							}
						});
			} else {
				sideListArea.setOnTouchListener(expandTouchListener);
			}

			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
				if (!isInDrawer()) {
					parentView.addOnLayoutChangeListener(new View.OnLayoutChangeListener()

					{
						int lastWidth = -1;

						@Override
						public void onLayoutChange(View v, int left, int top, int right,
								int bottom, int oldLeft, int oldTop, int oldRight,
								int oldBottom) {
							int width = right - left;
							if (width != lastWidth) {
								lastWidth = width;
								expandSideListWidth(sidelistInFocus);
							}
						}
					});
				}
			}

			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
				LayoutTransition layoutTransition = new LayoutTransition();
				layoutTransition.setDuration(400);
				sideListArea.setLayoutTransition(layoutTransition);
			}

			animationListener = new Animation.AnimationListener() {

				@Override
				public void onAnimationStart(Animation animation) {
				}

				@Override
				public void onAnimationEnd(Animation animation) {
					if (!sidelistIsExpanded) {
						for (ViewGroup header : listHeaderViewGroups) {
							View sideheader_text = header.findViewWithTag("sideheader_text");
							if (sideheader_text != null) {
								sideheader_text.setVisibility(View.GONE);
							}
						}
					}
					expandedStateChanged(sidelistIsExpanded);
				}

				@Override
				public void onAnimationRepeat(Animation animation) {
				}
			};
		} else {
			if (AndroidUtils.DEBUG) {
				Log.d(TAG, "setupSideListArea: no sidelistArea");
			}
		}

		// used to be in onStart()
		if (activity instanceof AppCompatActivity) {
			AppCompatActivity abActivity = (AppCompatActivity) activity;

			if (AndroidUtilsUI.getScreenWidthDp(
					activity) < SIDELIST_KEEP_EXPANDED_AT_DP || isInDrawer()) {
				if (mDrawerArrow == null) {
					ActionBarDrawerToggle.Delegate drawerToggleDelegate = abActivity.getDrawerToggleDelegate();
					if (drawerToggleDelegate != null) {
						Context themedContext = drawerToggleDelegate.getActionBarThemedContext();

						mDrawerArrow = new DrawerArrowDrawable(themedContext);
						mDrawerArrow.setSpinEnabled(true);
					}
				}

				ActionBar ab = abActivity.getSupportActionBar();
				if (ab != null) {
					ab.setHomeAsUpIndicator(mDrawerArrow);
					ab.setDisplayHomeAsUpEnabled(true);
				}
			}
		}

		if (sideListArea != null) {
			parentView.post(new Runnable() {
				@Override
				public void run() {
					if (SideListHelper.this.activity == null) {
						return;
					}
					Window window = SideListHelper.this.activity.getWindow();
					if (window == null) {
						return;
					}
					int pxHeight = window.getDecorView().getHeight();
					if (pxHeight == 0) {
						hideUnselectedSideHeaders = AndroidUtilsUI.getScreenHeightDp(
								VuzeRemoteApp.getContext()) < SideListHelper.this.SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP;
					} else {
						hideUnselectedSideHeaders = pxHeight < AndroidUtilsUI.dpToPx(
								SideListHelper.this.SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP);
					}
					expandSideListWidth(sidelistInFocus);
					if (AndroidUtils.DEBUG) {
						Log.d(TAG, "onAttach: hide? " + hideUnselectedSideHeaders + ";"
								+ pxHeight);
					}
				}
			});
		} else {
			int dpHeight = AndroidUtilsUI.getScreenHeightDp(activity);
			hideUnselectedSideHeaders = dpHeight < SIDELIST_HIDE_UNSELECTED_HEADERS_MAX_DP;
		}
	}

	private boolean isInDrawer() {
		if (activity instanceof DrawerActivity) {
			DrawerLayout drawerLayout = ((DrawerActivity) activity).getDrawerLayout();
			if (drawerLayout != null) {
				View viewInDrawer = drawerLayout.findViewById(sideListAreaID);
				if (viewInDrawer != null) {
					View viewInActivity = activity.findViewById(sideListAreaID);
					return viewInActivity == null || viewInDrawer == viewInActivity;
				}
			}
		}
		return false;
	}

			/* @Thunk */ boolean expandSideListWidth(Boolean expand) {
		if (sideListArea == null || SIDELIST_KEEP_EXPANDED_AT_DP == 0) {
			return false;
		}
		int width = parentView.getWidth();
		boolean noExpanding = width < SIDELIST_COLLAPSE_UNTIL_WIDTH_PX;
		// We have a Motorola Xoom on Android 4.0.4 that can't handle shrinking
		// (torrent list view overlays)
		boolean noShrinking = width >= AndroidUtilsUI.dpToPx(
				SIDELIST_KEEP_EXPANDED_AT_DP)
				|| Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN;

		if (expand == null) {
			if (noExpanding && noShrinking) {
				return false;
			}
			expand = noShrinking;
		}

		if (sidelistIsExpanded != null) {
			// before listening to caller, do our checks
			if (sidelistIsExpanded && noExpanding && !noShrinking) {
				expand = false;
			}
			if (!sidelistIsExpanded && noShrinking && !noExpanding) {
				expand = true;
			}
		}

		if (expand && noExpanding && !noShrinking) {
			expand = false;
		}
		if (!expand && noShrinking && !noExpanding) {
			expand = true;
		}

		if (sidelistIsExpanded != null && expand == sidelistIsExpanded) {
			return false;
		}

		expandedStateChanging(expand);
		sidelistIsExpanded = expand;

		if (sidelistIsExpanded) {
			for (ViewGroup header : listHeaderViewGroups) {
				View sideheader_text = header.findViewWithTag("sideheader_text");
				if (sideheader_text != null) {
					sideheader_text.setVisibility(View.VISIBLE);
				}
			}
		}
		if (expand) {
			sizeTo(sideListArea, SIDELIST_MAX_WIDTH, SIDELIST_DURATION_MS,
					animationListener);
		} else {
			sizeTo(sideListArea, SIDELIST_MIN_WIDTH, SIDELIST_DURATION_MS,
					animationListener);
		}

		if (mDrawerArrow != null) {
			mDrawerArrow.setProgress(sidelistIsExpanded ? 1.0f : 0.0f);
		}
		return true;
	}

	public abstract void expandedStateChanged(boolean expanded);

	public abstract void expandedStateChanging(boolean expanded);

	public static void sizeTo(final View v, int finalWidth, int durationMS,
			Animation.AnimationListener listener) {
		final int initalWidth = v.getMeasuredWidth();

		final int diff = finalWidth - initalWidth;

		final int multiplier = diff < 0 ? -1 : 0;

		Animation a = new Animation() {
			@Override
			protected void applyTransformation(float interpolatedTime,
					Transformation t) {
				v.getLayoutParams().width = initalWidth
						+ ((int) (diff * interpolatedTime));
				v.requestLayout();
			}

			@Override
			public boolean willChangeBounds() {
				return true;
			}
		};

		if (listener != null) {
			a.setAnimationListener(listener);
		}

		if (durationMS < 0) {
			// 1dp/ms
			a.setDuration((int) ((diff * multiplier)
					/ v.getContext().getResources().getDisplayMetrics().density));
		} else {
			a.setDuration(durationMS);
		}
		v.startAnimation(a);
	}

	public LinearLayout getSideListArea() {
		return sideListArea;
	}

	public boolean isValid() {
		return sideListArea != null;
	}

			/* @Thunk */ void hideAllBodies() {
		for (ViewGroup contentArea : listBodyViewGroups) {
			contentArea.setVisibility(View.GONE);
		}
	}

	public void addEntry(View view, int id_header, int id_body) {
		ViewGroup vgHeader = (ViewGroup) view.findViewById(id_header);
		final ViewGroup vgBody = (ViewGroup) view.findViewById(id_body);
		if (vgBody == null || vgHeader == null) {
			return;
		}
		listHeaderViewGroups.add(vgHeader);
		listBodyViewGroups.add(vgBody);

		if (vgBody.getVisibility() == View.VISIBLE && sidebarViewActive != vgBody) {
			sidebarViewActive = vgBody;
			sectionVisibiltyChanged(sidebarViewActive);
		}

		if (!(vgBody instanceof FlingLinearLayout)) {
			vgBody.setOnTouchListener(expandTouchListener);
		}

//		LayoutTransition layoutTransition = null;
//		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES
// .HONEYCOMB) {
//			layoutTransition = new LayoutTransition();
//			layoutTransition.setDuration(400);
////			vgBody.setLayoutTransition(layoutTransition);
//		}

		View.OnClickListener onClickListener = new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				boolean doTrigger = true;
				boolean same = sidebarViewActive == vgBody;
				if (same) {
					if (AndroidUtils.DEBUG) {
						Log.d(TAG, "onClick: Hide All Bodies");
					}
					if (sidebarViewActive != null) {
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
							sideListArea.setLayoutTransition(new LayoutTransition());
						}
						hideAllBodies();
						// Could just set the active GONE, since it's the only one that
						// should be visible.  The problem is "should" isn't "will"
						//sidebarViewActive.setVisibility(View.GONE);
					}
					sidebarViewActive = null;
					if (hideUnselectedSideHeaders) {
						if (AndroidUtils.DEBUG) {
							Log.d(TAG, "onClick: Hide Headers");
						}
						for (ViewGroup vgHeader : listHeaderViewGroups) {
							vgHeader.setVisibility(View.VISIBLE);
						}
					}
				} else {
					if (sidebarViewActive != null) {

						// 1) Make current view invisible
						// 2) Move header(s) up or down
						// 3) Show new view
						sidebarViewActive.setVisibility(View.INVISIBLE);

						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
							if (AndroidUtils.DEBUG) {
								Log.d(TAG, "onClick: Animate new view in");
							}
							doTrigger = false;
							ViewGroup parent = (ViewGroup) sidebarViewActive.getParent();
							int iOld = parent.indexOfChild(sidebarViewActive);
							int iNew = parent.indexOfChild(vgBody);
							int direction = iNew > iOld ? 1 : -1;
							int y = direction * -1 * sidebarViewActive.getHeight();

							if (AndroidUtils.DEBUG) {
								Log.d(TAG, "onClick: " + iOld + "/" + iNew);
							}
							// headers are one position up in parent

							List<View> viewsToMove = new ArrayList<>(1);
							if (direction > 0) {
								// If new is lower, we need tomove the header of new, and
								// any headers above it, up to the header of old.
								for (int i = iNew - 1; i > iOld; i--) {
									View view = parent.getChildAt(i);
									if ("sideheader".equals(view.getTag())) {
										viewsToMove.add(view);
									}
								}
							} else {
								// if new is higher, we need to move the header of old, and
								// and headers above it, up to header of new.
								for (int i = iOld - 1; i > iNew; i--) {
									View view = parent.getChildAt(i);
									if ("sideheader".equals(view.getTag())) {
										viewsToMove.add(view);
									}
								}
							}

							for (final View header : viewsToMove) {
								Animator.AnimatorListener l = new AnimatorEndListener() {
									ViewGroup old = sidebarViewActive;

									@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
									@Override
									public void onAnimationEnd(
											android.animation.Animator animation) {
										header.setTranslationY(0);
										sideListArea.setLayoutTransition(null);
										// These two don't need to be called everytime
										old.setVisibility(View.GONE);
										vgBody.setAlpha(0.0f);
										vgBody.setVisibility(View.VISIBLE);
										vgBody.animate().alpha(1.0f);

										sectionVisibiltyChanged(vgBody);
									}
								};

								header.animate().translationY(y).setListener(l).setDuration(
										300);
							}
						} else { // old API
							if (AndroidUtils.DEBUG) {
								Log.d(TAG, "onClick: Flip new view in");
							}
							hideAllBodies();
							vgBody.setVisibility(View.VISIBLE);
						}
					} else { // sidebarviewactive is null
						if (AndroidUtils.DEBUG) {
							Log.d(TAG, "onClick: show body (none visible yet)");
						}
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
							sideListArea.setLayoutTransition(new LayoutTransition());
						}
						vgBody.setVisibility(View.VISIBLE);
					}

					sidebarViewActive = vgBody;

					if (hideUnselectedSideHeaders) {
						if (AndroidUtils.DEBUG) {
							Log.d(TAG, "onClick: Hide Headers");
						}
						for (ViewGroup vgHeader : listHeaderViewGroups) {
							vgHeader.setVisibility(vgHeader == v ? View.VISIBLE : View.GONE);
						}
					}
				}

				if (doTrigger) {
					sectionVisibiltyChanged(sidebarViewActive);
				}

			}
		};

		vgHeader.setOnClickListener(onClickListener);
	}

	protected void sectionVisibiltyChanged(ViewGroup vgNewlyVisible) {
		boolean isSideTextFilterVisible = vgNewlyVisible == listSideTextFilter;
		if (tvSideFilterText != null && listSideTextFilter != null) {
			tvSideFilterText.setVisibility(
					tvSideFilterText.getText().length() == 0 && !isSideTextFilterVisible
							? View.GONE : View.VISIBLE);
		}
	}

	public void onResume() {
		if (tvSideFilterText != null && tvSideFilterText.length() > 0) {
			tvSideFilterText.setVisibility(View.VISIBLE);
			letterFilter.filter(tvSideFilterText.getText());
		}
	}

	private boolean canSideListExpand() {
		if (parentView == null) {
			return false;
		}
		int width = parentView.getWidth();
		boolean noExpanding = width < SIDELIST_COLLAPSE_UNTIL_WIDTH_PX;
		return !noExpanding;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		if (item != null && item.getItemId() == android.R.id.home) {
			boolean expand = true;
			if (activity instanceof DrawerActivity) {
				DrawerActivity abActivity = (DrawerActivity) activity;
				expand = abActivity.getDrawerLayout() == null;
			}

			if (expand) {
				expandSideListWidth(
						sidelistIsExpanded == null ? true : !sidelistIsExpanded);
				return true;
			}
		}

		return false;
	}

	public boolean hasSideTextFilterArea() {
		return listSideTextFilter != null;
	}

	public void setupSideTextFilter(View view, int id_sidetextfilter_list,
			int id_sidefilter_text, RecyclerView lvResults,
			LetterFilter _letterFilter) {
		this.letterFilter = _letterFilter;
		RecyclerView oldRV = listSideTextFilter;
		listSideTextFilter = (RecyclerView) view.findViewById(
				id_sidetextfilter_list);
		if (listSideTextFilter == null) {
			return;
		}
		if (oldRV == listSideTextFilter) {
			return;
		}

		final Context context = activity;

		if (lvResults != null) {
			letterFilter.setBuildLetters(true);
		}

		//This was in TorrentListFragment.. not sure if we need it
		//listSideTextFilter.setItemAnimator(new DefaultItemAnimator());

		tvSideFilterText = (TextView) view.findViewById(id_sidefilter_text);

		tvSideFilterText.addTextChangedListener(new TextWatcher() {

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
				letterFilter.filter(s);
			}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});

		listSideTextFilter.setLayoutManager(new PreCachingLayoutManager(context));

		sideTextFilterAdapter = new SideFilterAdapter(context,
				new FlexibleRecyclerSelectionListener<SideFilterAdapter, SideFilterAdapter.SideFilterInfo>() {
					@Override
					public void onItemCheckedChanged(SideFilterAdapter adapter,
							SideFilterAdapter.SideFilterInfo item, boolean isChecked) {
						if (!isChecked) {
							return;
						}
						adapter.setItemChecked(item, false);

						String s = item.letters;
						if (s.equals(FilterConstants.LETTERS_NUMBERS)) {
							letterFilter.setCompactDigits(false);
							letterFilter.refilter();
							return;
						}
						if (s.equals(FilterConstants.LETTERS_NON)) {
							letterFilter.setCompactOther(false);
							letterFilter.refilter();
							return;
						}
						if (s.equals(FilterConstants.LETTERS_PUNCTUATION)) {
							letterFilter.setCompactPunctuation(false);
							letterFilter.refilter();
							return;
						}
						if (s.equals(FilterConstants.LETTERS_BS)) {
							CharSequence text = tvSideFilterText.getText();
							if (text.length() > 0) {
								text = text.subSequence(0, text.length() - 1);
								tvSideFilterText.setText(text);
							} else {
								letterFilter.setCompactPunctuation(true);
								letterFilter.setCompactDigits(true);
								letterFilter.setCompactOther(true);
								letterFilter.refilter();
							}
							return;
						}
						s = tvSideFilterText.getText() + s;
						tvSideFilterText.setText(s);
					}

					@Override
					public boolean onItemLongClick(SideFilterAdapter adapter,
							int position) {
						return false;
					}

					@Override
					public void onItemSelected(SideFilterAdapter adapter, int position,
							boolean isChecked) {

					}

					@Override
					public void onItemClick(SideFilterAdapter adapter, int position) {

					}
				});
		listSideTextFilter.setAdapter(sideTextFilterAdapter);
	}

	public void setupSideSort(View view, int id_sidesort_list,
			int id_sort_current, int id_strings, SideSortAPI _sidesortAPI) {
		sidesortAPI = _sidesortAPI;
		RecyclerView oldRV = listSideSort;
		listSideSort = (RecyclerView) view.findViewById(id_sidesort_list);
		if (listSideSort == null) {
			return;
		}
		if (oldRV == listSideSort) {
			return;
		}

		tvSortCurrent = (TextView) view.findViewById(id_sort_current);

		final Context context = activity;

		// Nice flow between expanded/shrunk view
		listSideSort.setItemAnimator(new DefaultItemAnimator());

		listSideSort.setLayoutManager(new PreCachingLayoutManager(context));

		sideSortAdapter = new SideSortAdapter(context,
				new FlexibleRecyclerSelectionListener<SideSortAdapter, SideSortAdapter.SideSortInfo>() {
					@Override
					public void onItemClick(SideSortAdapter adapter, int position) {
					}

					@Override
					public boolean onItemLongClick(SideSortAdapter adapter,
							int position) {
						return false;
					}

					@Override
					public void onItemSelected(SideSortAdapter adapter, int position,
							boolean isChecked) {

					}

					@Override
					public void onItemCheckedChanged(SideSortAdapter adapter,
							SideSortAdapter.SideSortInfo item, boolean isChecked) {

						if (!isChecked) {
							return;
						}
						adapter.setItemChecked(item, false);

						SortByFields sortByFields = sidesortAPI.getSortByFields(
								context)[((int) item.id)];
						if (sortByFields != null) {
							if (item.id == adapter.getCurrentSort()) {
								sidesortAPI.flipSortOrder();
							} else {
								sidesortAPI.sortBy(sortByFields.sortFieldIDs,
										sortByFields.sortOrderAsc, (int) item.id, true);
							}
						}

					}
				});
		//String[] sortNames = context.getResources().getStringArray(id_strings);
		// last on is "reverse".. so ignore it
		List<SideSortAdapter.SideSortInfo> list = new ArrayList<>();
		SortByFields[] sortByFields = sidesortAPI.getSortByFields(context);
		for (int i = 0; i < sortByFields.length - 1; i++) {
			list.add(new SideSortAdapter.SideSortInfo(i, sortByFields[i].name,
					sortByFields[i].flipArrow));
		}
		sideSortAdapter.setItems(list);
		listSideSort.setAdapter(sideSortAdapter);
	}

	public void setCurrentSort(Context context, int which, boolean sortOrderAsc) {
		if (sideSortAdapter != null) {
			sideSortAdapter.setCurrentSort(which, sortOrderAsc);
		}
		if (tvSortCurrent != null) {
			SortByFields[] sortByFields = sidesortAPI.getSortByFields(context);
			String s = "";
			if (which >= 0 && which < sortByFields.length) {
				if (sortByFields[which].flipArrow) {
					sortOrderAsc = !sortOrderAsc;
				}
				s = sortByFields[which].name + " " + (sortOrderAsc ? "▲" : "▼");
			}

			tvSortCurrent.setText(s);
		}
	}

	public interface SideSortAPI
	{
		SortByFields[] getSortByFields(Context context);

		void flipSortOrder();

		void sortBy(String[] sortFieldIDs, Boolean[] sortOrderAsc, int id,
				boolean b);
	}

	/**
	 * Map of available (next) letters and count of hits, is available.
	 * Updates the SideFiilterText list with new map
	 * <p/>
	 * Call this from lettersUpdated(HashMap<String, Integer>) of the adapter
	 * you want to show the letters for
	 *
	 * @param mapLetters
	 */
	public void lettersUpdated(HashMap<String, Integer> mapLetters) {
		if (sideTextFilterAdapter == null) {
			return;
		}
		if (AndroidUtils.DEBUG_ADAPTER) {
			Log.d(TAG, "lettersUpdated: " + mapLetters.size());
		}
		String[] keys = mapLetters.keySet().toArray(new String[mapLetters.size()]);
		Arrays.sort(keys, new Comparator<String>() {
			@Override
			public int compare(String lhs, String rhs) {
				int rsh_length = rhs.length();
				if ((rsh_length > 1) == (lhs.length() > 1)) {
					return lhs.compareTo(rhs);
				}
				return rsh_length > 1 ? -1 : 1;
			}
		});
		final ArrayList<SideFilterAdapter.SideFilterInfo> list = new ArrayList<>();
		for (String c : keys) {
			Integer count = mapLetters.get(c);
			SideFilterAdapter.SideFilterInfo info = new SideFilterAdapter.SideFilterInfo(
					c, count);
			list.add(info);
		}
		if (tvSideFilterText.getText().length() > 0
				|| !letterFilter.getCompactDigits()
				|| !letterFilter.getCompactNonLetters()
				|| !letterFilter.getCompactPunctuation()) {
			list.add(0,
					new SideFilterAdapter.SideFilterInfo(FilterConstants.LETTERS_BS, 0));
		}

		activity.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (activity.isFinishing()) {
					return;
				}
				boolean hadFocus = AndroidUtilsUI.isChildOf(activity.getCurrentFocus(),
						listSideTextFilter);
				sideTextFilterAdapter.setItems(list);

				if (hadFocus) {
					listSideTextFilter.post(new Runnable() {
						@Override
						public void run() {
							listSideTextFilter.requestFocus();
						}
					});
				}
			}
		});
	}

	public SideSortAdapter getSideSortAdapter() {
		return sideSortAdapter;
	}

	public SideFilterAdapter getSideTextFilterAdapter() {
		return sideTextFilterAdapter;
	}

	public boolean isExpanded() {
		return sidelistIsExpanded == null ? true : sidelistIsExpanded;
	}

	public void onSaveInstanceState(Bundle outState) {
		if (sideSortAdapter != null) {
			sideSortAdapter.onSaveInstanceState(outState);
		}
		if (sideTextFilterAdapter != null) {
			sideTextFilterAdapter.onSaveInstanceState(outState);
		}

	}

	public void onRestoreInstanceState(Bundle savedInstanceState) {
		if (sideSortAdapter != null) {
			sideSortAdapter.onRestoreInstanceState(savedInstanceState, listSideSort);
		}
		if (sideTextFilterAdapter != null) {
			sideTextFilterAdapter.onRestoreInstanceState(savedInstanceState,
					listSideTextFilter);
		}
	}

}
