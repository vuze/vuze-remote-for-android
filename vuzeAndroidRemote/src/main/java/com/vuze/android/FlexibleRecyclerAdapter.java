/*
 * *
 *  * Copyright (C) Azureus Software, Inc, All Rights Reserved.
 *  *
 *  * This program is free software; you can redistribute it and/or
 *  * modify it under the terms of the GNU General Public License
 *  * as published by the Free Software Foundation; either version 2
 *  * of the License, or (at your option) any later version.
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program; if not, write to the Free Software
 *  * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 *  USA.
 *
 *
 */

package com.vuze.android;

import java.util.*;

import com.vuze.android.remote.AndroidUtils;
import com.vuze.android.remote.AndroidUtilsUI;
import com.vuze.android.remote.R;
import com.vuze.util.Thunk;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

/**
 * This adapter requires only having one RecyclerView attached to it.
 *
 * @param <VH> ViewHolder class for an item
 * @param <T>  Data representation class of an item
 */
public abstract class FlexibleRecyclerAdapter<VH extends RecyclerView.ViewHolder, T extends Comparable<T>>
	extends RecyclerView.Adapter<VH>
	implements FlexibleRecyclerViewHolder.RecyclerSelectorInternal<VH>
{

	private static final String TAG = "FlexibleRecyclerAdapter";

	public static final int NO_CHECK_ON_SELECTED = -1;

	private static final String KEY_SUFFIX_CHECKED = ".checked";

	private static final String KEY_SUFFIX_SEL_POS = ".selPos";

	private static final String KEY_SUFFIX_FIRST_POS = ".firstPos";

	@Thunk
	final Object mLock = new Object();

	/** List of they keys of all entries displayed, in the display order */
	@Thunk
	List<T> mItems = new ArrayList<>();

	private int selectedPosition = -1;

	private T selectedItem;

	private FlexibleRecyclerSelectionListener selector;

	private final List<T> checkedItems = new ArrayList<>();

	private boolean mIsMultiSelectMode;

	private int checkOnSelectedAfterMS = NO_CHECK_ON_SELECTED;

	@Thunk
	Runnable runnableDelayedCheck;

	@Thunk
	RecyclerView recyclerView;

	private boolean mAllowMultiSelectMode = true;

	private boolean mAlwaysMultiSelectMode = false;

	@Thunk
	View emptyView;

	private RecyclerView.AdapterDataObserver observer;

	private View initialView;

	private boolean neverSetItems = true;

	public FlexibleRecyclerAdapter() {
		super();
	}

	public FlexibleRecyclerAdapter(FlexibleRecyclerSelectionListener rs) {
		super();
		selector = rs;
	}

	public FlexibleRecyclerSelectionListener getRecyclerSelector() {
		return selector;
	}

	@Override
	public void onAttachedToRecyclerView(RecyclerView recyclerView) {
		if (this.recyclerView != null) {
			Log.e(TAG, "Multiple RecyclerViews not allowed on Adapter " + this);
		}
		this.recyclerView = recyclerView;
	}

	@Override
	public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
		super.onDetachedFromRecyclerView(recyclerView);
		this.recyclerView = null;
	}

	public void setRecyclerSelector(FlexibleRecyclerSelectionListener rs) {
		selector = rs;
	}

	public void notifyDataSetInvalidated() {
		int count = getItemCount();
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("setItems: invalidate all (" + count + ")");
		}
		notifyItemRangeChanged(0, count);
		if (count == 0) {
			neverSetItems = false;
			checkEmpty();
		}
	}

	/**
	 * @return The positions of the checked items
	 */
	public int[] getCheckedItemPositions() {
		synchronized (mLock) {
			int[] positions = new int[checkedItems.size()];
			for (int i = 0; i < positions.length; i++) {
				positions[i] = getPositionForItem(checkedItems.get(i));
			}
			return positions;
		}
	}

	public List<T> getCheckedItems() {
		return new ArrayList<>(checkedItems);
	}

	private void setCheckedPositions(@Nullable int[] positions) {
		// TODO: notify before clearing
		checkedItems.clear();
		if (positions == null || positions.length == 0) {
			return;
		}
		for (int position : positions) {
			T item = getItem(position);
			if (item != null) {
				checkedItems.add(item);
			}
		}
	}

	/**
	 * Saves the state of the current selection on the items.
	 *
	 * @param outState Current state
	 */
	public void onSaveInstanceState(Bundle outState) {
		outState.putIntArray(TAG + KEY_SUFFIX_CHECKED, getCheckedItemPositions());
		outState.putInt(TAG + KEY_SUFFIX_SEL_POS, selectedPosition);
		if (recyclerView instanceof FlexibleRecyclerView) {
			int pos = ((FlexibleRecyclerView) recyclerView).findFirstVisibleItemPosition();
			if (pos >= 0) {
				outState.putInt(TAG + KEY_SUFFIX_FIRST_POS, pos);
			}
		}
	}

	/**
	 * Restores the previous state of the selection on the items.
	 *
	 * @param savedInstanceState Previous state
	 */
	public void onRestoreInstanceState(Bundle savedInstanceState,
			RecyclerView rv) {
		if (savedInstanceState == null) {
			return;
		}
		int[] checkedPositions = savedInstanceState.getIntArray(
				TAG + KEY_SUFFIX_CHECKED);
		setCheckedPositions(checkedPositions);
		selectedPosition = savedInstanceState.getInt(TAG + KEY_SUFFIX_SEL_POS, -1);
		if (selectedPosition >= 0) {
			if (AndroidUtils.DEBUG_ADAPTER) {
				Log.d(TAG, "onRestoreInstanceState: scroll to #" + selectedPosition);
			}
			selectedItem = getItem(selectedPosition);
			rv.scrollToPosition(selectedPosition);
		} else {
			int firstPosition = savedInstanceState.getInt(TAG + KEY_SUFFIX_FIRST_POS,
					-1);
			if (AndroidUtils.DEBUG_ADAPTER) {
				Log.d(TAG,
						"onRestoreInstanceState: scroll to first, #" + firstPosition);
			}
			rv.scrollToPosition(firstPosition);
		}
	}

	//////////////////
	// Item functions
	//////////////////

	/**
	 * Retrieve the position of the Item in the Adapter
	 *
	 * @param item The item
	 * @return The position in the Adapter if found, -1 otherwise
	 */
	public int getPositionForItem(T item) {
		if (item == null) {
			return -1;
		}
		int position = mItems.indexOf(item);
		if (position >= 0) {
			return position;
		}
		// Direct comparison failed, maybe item is in list, but as a different
		// object
		int s = mItems.size();
		for (int i = 0; i < s; i++) {
			if (mItems.get(i).compareTo(item) == 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Retrieve the position of the Items in the Adapter
	 *
	 * @param items list of item Objects
	 * @return list of positions
	 */
	public int[] getPositionForItems(T[] items) {
		int positions[] = new int[items.length];
		int i = 0;
		for (T torrentID : items) {
			positions[i] = getPositionForItem(torrentID);
		}

		return positions;
	}

	/**
	 * Returns the custom object "Item".
	 *
	 * @param position The position of the item in the list
	 * @return The custom "Item" object or null if item not found
	 */
	public T getItem(int position) {
		if (position < 0 || position >= mItems.size()) {
			return null;
		}
		return mItems.get(position);
	}

	//int countC = 0; // For tracking/debuging if we are creating too many
	// holders
	//int countB = 0; // instead of just rebinding them

	@Override
	public final VH onCreateViewHolder(ViewGroup parent, int viewType) {
		//log("onCreateViewHolder: " + (++countC));
		return onCreateFlexibleViewHolder(parent, viewType);
	}

	@Override
	public final void onBindViewHolder(VH holder, int position,
			List<Object> payloads) {
		//log("onBindViewHolder: " + (++countB));
		onBindFlexibleViewHolder(holder, position, payloads);
	}

	private void onBindFlexibleViewHolder(VH holder, int position,
			List<Object> payloads) {
		onBindViewHolder(holder, position);
	}

	public abstract VH onCreateFlexibleViewHolder(ViewGroup parent, int viewType);

	@SuppressWarnings("ResourceType")
	@Override
	public final void onBindViewHolder(VH holder, int position) {
		onBindFlexibleViewHolder(holder, position);

		if ((recyclerView instanceof FlexibleRecyclerView)
				&& (holder instanceof FlexibleRecyclerViewHolder)) {
			int fixedVerticalHeight = ((FlexibleRecyclerView) recyclerView).getFixedVerticalHeight();
			if (fixedVerticalHeight > 0) {
				// Torrent List goes to bottom of TV screen, past the overscan area
				// Adjust last item to have overscan gap, to ensure user can view
				// the whole row

				// Setting bottomMargin on itemView doesn't work on FireTV
				// Try layoutRow instead.  The side affect is that we will be extending
				// the selector state color to the bottom of the screen, which doesn't
				// look great
				//View v = holder.layoutRow == null ? holder.itemView : holder
				// .layoutRow;
				View v = holder.itemView;
				ViewGroup.LayoutParams lp = v.getLayoutParams();
				int paddingBottom = position + 1 == getItemCount()
						? AndroidUtilsUI.dpToPx(48) : 0;

				if (lp instanceof RecyclerView.LayoutParams) {
					((RecyclerView.LayoutParams) lp).bottomMargin = paddingBottom;
				} else if (lp instanceof RelativeLayout.LayoutParams) {
					((RelativeLayout.LayoutParams) lp).bottomMargin = paddingBottom;
				} else if (lp instanceof FrameLayout.LayoutParams) {
					// shouldn't happen, but this is the layout param type for the row
					((FrameLayout.LayoutParams) lp).bottomMargin = paddingBottom;
				}
				v.requestLayout();

			}
		}
		boolean checked = isItemChecked(position);
		boolean selected = isItemSelected(position);
		if (holder.itemView != null) {
			holder.itemView.setSelected(selected);
			AndroidUtilsUI.setViewChecked(holder.itemView, checked);
		}
	}

	public abstract void onBindFlexibleViewHolder(VH holder, final int position);

	@Override
	public int getItemCount() {
		return mItems.size();
	}

	public boolean isEmpty() {
		return getItemCount() == 0;
	}

	@SuppressWarnings("WeakerAccess")
	public void updateItem(final int position, final T item) {
		if (!AndroidUtilsUI.isUIThread()) {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					updateItem(position, item);
				}
			});
			return;
		}
		if (position < 0) {
			return;
		}
		synchronized (mLock) {
			mItems.set(position, item);

			if (selectedPosition == position) {
				selectedItem = item;
			}
		}

		if (AndroidUtils.DEBUG_ADAPTER) {
			Log.v(TAG, "updateItem: " + position);
		}
		notifyItemChanged(position);
	}

	public void addItem(final T item) {
		if (!AndroidUtilsUI.isUIThread()) {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					if (AndroidUtils.DEBUG_ADAPTER) {
						log("addItem: delayed");
					}
					addItem(item);
				}
			});
			return;
		}
		int position;
		synchronized (mLock) {
			mItems.add(item);
			position = mItems.size() - 1;
		}
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("addItem: " + position);
		}
		notifyItemInserted(position);
	}

	/**
	 * Insert given Item at position or Add Item at last position.
	 *
	 * @param position Position of the item to add
	 * @param item     The item to add
	 */
	@SuppressWarnings("WeakerAccess")
	public void addItem(int position, final T item) {
		if (!AndroidUtilsUI.isUIThread()) {
			final int finalPosition = position;
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					if (AndroidUtils.DEBUG_ADAPTER) {
						log("addItem: delayed. " + finalPosition);
					}
					addItem(finalPosition, item);
				}
			});
			return;
		}
		if (position < 0) {
			Log.w(TAG, "Cannot addItem on negative position");
			return;
		}
		//Insert Item
		if (position < mItems.size()) {
			synchronized (mLock) {
				mItems.add(position, item);
				if (selectedPosition >= 0 && selectedPosition < position) {
					selectedPosition++;
				}
			}
		} else { //Add Item at the last position
			synchronized (mLock) {
				mItems.add(item);
				position = mItems.size() - 1;
			}
		}
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("addItem: " + position);
		}
		notifyItemInserted(position);
	}

	@SuppressWarnings("WeakerAccess")
	public void removeItem(final int position) {
		if (position < 0) {
			return;
		}

		if (!AndroidUtilsUI.isUIThread()) {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					removeItem(position);
				}
			});
			return;
		}

		T itemRemoved;
		synchronized (mLock) {
			itemRemoved = mItems.remove(position);
		}
		if (itemRemoved == null) {
			return;
		}

		if (selectedPosition == position) {
			selectedItem = null;
			selectedPosition = -1;
		} else if (selectedPosition > position) {
			selectedPosition--;
			if (selector != null && selectedItem != null) {
				selector.onItemSelected(this, selectedPosition,
						isItemChecked(selectedItem));
			}
		}

		if (checkedItems.size() > 0) {
			boolean removed = checkedItems.remove(itemRemoved);
			if (removed && selector != null) {
				selector.onItemCheckedChanged(this, itemRemoved, false);
			}
		}

		notifyItemRangeRemoved(position, 1);
	}

	public void removeAllItems() {
		if (!AndroidUtilsUI.isUIThread()) {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					if (AndroidUtils.DEBUG_ADAPTER) {
						log("removeAllItems: delayed");
					}
					removeAllItems();
				}
			});
			return;
		}
		int count;
		synchronized (mLock) {
			count = mItems.size();
			mItems.clear();
		}
		if (selectedPosition >= 0) {
			selectedPosition = -1;
			selectedItem = null;
			// trigger some unselection event?
		}
		if (checkedItems.size() > 0) {
			if (selector != null) {
				for (T checkedItem : checkedItems) {
					selector.onItemCheckedChanged(this, checkedItem, false);
				}
			}
			checkedItems.clear();
		}
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("removeAllItems: " + count);
		}
		if (count > 0) {
			notifyItemRangeRemoved(0, count);
		}
	}

	public void setItems(final List<T> items) {
		neverSetItems = false;
		if (!AndroidUtilsUI.isUIThread()) {
			if (AndroidUtils.DEBUG_ADAPTER) {
				log("setItems: delay " + recyclerView);
			}
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					setItems(items);
				}
			});
			return;
		}

		List<T> notifyUncheckedList;
		int oldCount;
		int newCount;
		synchronized (mLock) {
			oldCount = mItems.size();
			newCount = items.size();

			mItems = new ArrayList<>();
			mItems.clear();
			mItems.addAll(items);
			if (selectedItem != null) {
				// relink, since we may have a new object with the same stableId
				selectedPosition = getPositionForItem(selectedItem);
				selectedItem = getItem(selectedPosition);
			}

			notifyUncheckedList = relinkCheckedItems();
		}

		if (selector != null) {
			for (T item : notifyUncheckedList) {
				selector.onItemCheckedChanged(this, item, false);
			}
		}

		if (AndroidUtils.DEBUG_ADAPTER) {
			log("setItems: oldCount=" + oldCount + ";new=" + newCount + ";" + this);
		}

		// prevent
		// java.lang.IndexOutOfBoundsException: Inconsistency detected. Invalid
		// view holder adapter position

		// This isn't what I want to do, but this is the only way I can get it to
		// work on some devices.
		// notifyItemRangeRemoved(0, oldCount);
		// notifyItemRangeInserted(0, newCount);

		// This is how I want to do it.  Works only when setItemAnimator is null
		// It's like RecyclerView is trying to run predictive animations after
		// dataset has changed and not laid out (despite it checking
		// mDataSetHasChangedAfterLayout?)
		if (oldCount > newCount) {
			notifyItemRangeRemoved(newCount, oldCount - newCount);
			notifyItemRangeChanged(0, newCount);
			if (AndroidUtils.DEBUG_ADAPTER) {
				log("setItems: remove from " + newCount + " size "
						+ (oldCount - newCount));
				log("setItems: change from 0, size " + newCount);
			}
		} else if (newCount > oldCount) {
			notifyItemRangeInserted(oldCount, newCount - oldCount);
			if (oldCount != 0) {
				notifyItemRangeChanged(0, oldCount);
			}
			if (AndroidUtils.DEBUG_ADAPTER) {
				log("setItems: insert from " + oldCount + " size "
						+ (newCount - oldCount));
				if (oldCount != 0) {
					log("setItems: change 0 to " + oldCount);
				}
			}
		} else {
			notifyDataSetInvalidated();
		}
	}

	private List<T> relinkCheckedItems() {
		if (checkedItems.size() == 0) {
			return Collections.emptyList();
		}

		List<T> notifyUncheckedList = new ArrayList<>();
		ListIterator<T> checkedItemsIterator = checkedItems.listIterator();
		while (checkedItemsIterator.hasNext()) {
			T item = checkedItemsIterator.next();

			int newPosition = getPositionForItem(item);

			if (newPosition < 0) {
				checkedItemsIterator.remove();
				notifyUncheckedList.add(item);
			} else {
				checkedItemsIterator.set(getItem(newPosition));
			}
		}
		return notifyUncheckedList;
	}

	public void sortItems(final Comparator<Object> sorter) {
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("sortItems");
		}

		if (getItemCount() == 0) {
			return;
		}

		new Thread(new Runnable() {
			@Override
			public void run() {
				synchronized (mLock) {
					List<T> itemsNew = doSort(mItems, sorter, true);
					setItems(itemsNew);
				}

				new Handler(Looper.getMainLooper()).post(new Runnable() {
					@Override
					public void run() {
						notifyDataSetInvalidated();
					}
				});
			}
		}, "sortItems " + this.getClass().getSimpleName()).start();

	}

	public List<T> doSort(List<T> items, Comparator<Object> sorter,
			boolean createNewList) {
		if (AndroidUtilsUI.isUIThread()) {
			Log.w(TAG,
					"Sorting on UIThread! " + AndroidUtils.getCompressedStackTrace());
		}

		List<T> itemsNew = createNewList ? new ArrayList<>(items) : items;

		// java.lang.IllegalArgumentException: Comparison method violates its
		// general contract!
		try {
			Collections.sort(itemsNew, sorter);
		} catch (Throwable t) {
			Log.e(TAG, "doSort: ", t);
		}

		return itemsNew;
	}

	///////////////////////
	// Selection Functions
	///////////////////////

	@Override
	public void onItemClick(VH holder, View view) {
		int position = holder.getLayoutPosition();
		boolean alreadyChecked = isItemChecked(position);
		// clear previous selection when not in multimode
		if (!mIsMultiSelectMode && getCheckedItemCount() > 0) {
			if (getCheckedItemCount() > 1
					|| getPositionForItem(checkedItems.get(0)) != position) {
				clearChecked();
			}
		}

		setItemSelected(position, holder);

		if (mIsMultiSelectMode || !alreadyChecked) {
			toggleItemChecked(holder);
		}

		if (selector != null) {
			selector.onItemClick(this, position);
		}
	}

	@Override
	public boolean onItemLongClick(VH holder, View view) {
		int position = holder.getLayoutPosition();

		if (!mIsMultiSelectMode) {
			if (mAllowMultiSelectMode) {
				mIsMultiSelectMode = true;
			} else {
				if (getCheckedItemCount() > 0 && (getCheckedItemCount() > 1
						|| getPositionForItem(checkedItems.get(0)) != position)) {
					clearChecked();
				}
			}
		}
		setItemSelected(position, holder);

		if (selector != null) {
			if (selector.onItemLongClick(this, position)) {
				return true;
			}
		}

		// Only toggle checked if selector didn't handle it
		toggleItemChecked(holder, true);

		return true;
	}

	@SuppressWarnings("WeakerAccess")
	public boolean isItemSelected(int position) {
		return position != -1 && position == selectedPosition;
	}

	public void onFocusChange(VH holder, View v, boolean hasFocus) {
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("onFocusChange: " + hasFocus + ";" + this + ";"
					+ AndroidUtils.getCompressedStackTrace());
		}
		if (!hasFocus) {
			return;
		}
		final int position = holder.getLayoutPosition();
		setItemSelected(position, holder);

		// Check item on selection
		if (checkOnSelectedAfterMS >= 0) {
			if (runnableDelayedCheck != null) {
				v.getRootView().removeCallbacks(runnableDelayedCheck);
				runnableDelayedCheck = null;
			}

			boolean isChecked = isItemChecked(position);
			if (mIsMultiSelectMode || isChecked) {
				// Multiselect: We don't want to auto-check when focus changes
				// Already checked? If we aren't multimode, then something has already
				// done our job (such as a tap)
				return;
			}
			runnableDelayedCheck = new Runnable() {
				@Override
				public void run() {
					if (runnableDelayedCheck != this || !isItemSelected(position)) {
						return;
					}
					clearChecked();
					setItemChecked(position, true);
					runnableDelayedCheck = null;
				}
			};
			v.getRootView().postDelayed(runnableDelayedCheck, checkOnSelectedAfterMS);
		}

	}

	private void setItemSelected(int position, VH holder) {
		RecyclerView.ViewHolder selectedHolder = selectedPosition < 0 ? null
				: recyclerView.findViewHolderForAdapterPosition(selectedPosition);

		if (selectedHolder != null && selectedHolder != holder) {
			if (AndroidUtils.DEBUG_ADAPTER) {
				log("setItemSelected: Unselect previous position of "
						+ selectedPosition);
			}
			selectedHolder.itemView.setSelected(false);
			notifyItemChanged(selectedPosition);
		}
		selectedPosition = position;
		selectedItem = getItem(selectedPosition);
		holder.itemView.setSelected(selectedItem != null);

		if (AndroidUtils.DEBUG_ADAPTER) {
			log("setItemSelected: changed selected to " + selectedPosition);
		}

		if (selector != null) {
			selector.onItemSelected(this, position, isItemChecked(position));
		}
	}

	public boolean isItemChecked(int position) {
		return checkedItems.contains(getItem(position));
	}

	private boolean isItemChecked(T item) {
		return checkedItems.contains(item);
	}

	private void toggleItemChecked(RecyclerView.ViewHolder holder) {
		Integer position = holder.getLayoutPosition();
		boolean nowChecked;
		T item = getItem(position);
		if (item == null) {
			return;
		}
		if (isItemChecked(item)) {
			checkedItems.remove(item);
			nowChecked = false;
			if (checkedItems.size() == 0) {
				setMultiCheckMode(false);
			}
		} else {
			checkedItems.add(item);
			nowChecked = true;
		}
		if (AndroidUtils.DEBUG) {
			log("toggleItemChecked to " + nowChecked + " for " + position);
		}
		AndroidUtilsUI.setViewChecked(holder.itemView, nowChecked);

		notifyItemChanged(position);
		if (selector != null) {
			selector.onItemCheckedChanged(this, item, nowChecked);
		}

	}

	private void toggleItemChecked(RecyclerView.ViewHolder holder, boolean on) {
		Integer position = holder.getLayoutPosition();
		T item = getItem(position);
		boolean alreadyChecked = checkedItems.contains(item);
		if (AndroidUtils.DEBUG) {
			log("toggleItemChecked to " + on + " for " + position + "; was "
					+ alreadyChecked);
		}
		if (on != alreadyChecked) {
			if (on) {
				checkedItems.add(item);
			} else {
				checkedItems.remove(item);
				if (checkedItems.size() == 0) {
					setMultiCheckMode(false);
				}
			}
			AndroidUtilsUI.setViewChecked(holder.itemView, on);
			notifyItemChanged(position);

			if (selector != null) {
				selector.onItemCheckedChanged(this, item, on);
			}
		}
	}

	/**
	 * Flips the checked state of an item
	 *
	 * @param position The position of the item to flip the state of
	 */
	// doesn't immediately update check state visually
	public void toggleItemChecked(int position) {
		toggleItemChecked(position, true);
	}

	private void toggleItemChecked(Integer position, boolean notifySelector) {
		boolean checked;
		T item = getItem(position);
		if (checkedItems.contains(item)) {
			checkedItems.remove(item);
			checked = false;
			if (checkedItems.size() == 0) {
				setMultiCheckMode(false);
			}
		} else {
			checkedItems.add(item);
			checked = true;
		}

		if (AndroidUtils.DEBUG) {
			log("toggleItemChecked to " + checked + " for " + position + ";"
					+ AndroidUtils.getCompressedStackTrace(8));
		}
		notifyItemChanged(position);

		if (selector != null && notifySelector) {
			selector.onItemCheckedChanged(this, item, checked);
		}
	}

	public void setItemChecked(T item, boolean checked) {
		if (item == null) {
			return;
		}
		setItemChecked(item, getPositionForItem(item), checked);
	}

	// doesn't immediately update check state visually
	public void setItemChecked(int position, boolean checked) {
		if (position < 0) {
			return;
		}
		T item = getItem(position);
		setItemChecked(item, position, checked);
	}

	private void setItemChecked(T item, int position, boolean checked) {
		boolean alreadyChecked = checkedItems.contains(item);
		if (checked != alreadyChecked) {
			if (checked) {
				checkedItems.add(item);
			} else {
				checkedItems.remove(item);
				if (checkedItems.size() == 0) {
					setMultiCheckMode(false);
				}
			}
			if (AndroidUtils.DEBUG) {
				Log.d(TAG,
						"setItemChecked to " + checked + " for " + position + "; was "
								+ alreadyChecked + ";"
								+ AndroidUtils.getCompressedStackTrace(4));
			}

			notifyItemChanged(position);
			if (selector != null) {
				selector.onItemCheckedChanged(this, item, checked);
			}
		}
	}

	/**
	 * Unchecks all checked items
	 */
	public void clearChecked() {
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("Clear " + checkedItems.size() + " checked via "
					+ AndroidUtils.getCompressedStackTrace(4));
		}
		Object[] checkedItemsArray = checkedItems.toArray();
		for (Object checkedItem : checkedItemsArray) {
			int position = getPositionForItem((T) checkedItem);
			if (position >= 0) {
				toggleItemChecked(position, false);
				notifyItemChanged(position);
			}
		}
	}

	public int getCheckedItemCount() {
		return checkedItems.size();
	}

	public boolean isMultiCheckMode() {
		return mIsMultiSelectMode;
	}

	public void setMultiCheckMode(boolean on) {
		if (mAlwaysMultiSelectMode && !on) {
			return;
		}
		if (AndroidUtils.DEBUG_ADAPTER) {
			log("setMultiCheckMode " + on + "; "
					+ AndroidUtils.getCompressedStackTrace(4));
		}
		mIsMultiSelectMode = on;
		if (!on && getCheckedItemCount() > 1) {
			clearChecked();
		}
	}

	/**
	 * Get the selected position.  May not be checked.
	 * Will not be focused if RecyclerView is not focus.
	 *
	 * @return The selected position.  < 0 if nothing is selected
	 */
	public int getSelectedPosition() {
		return selectedPosition;
	}

	public T getSelectedItem() {
		return selectedItem;
	}

	/**
	 * Retrieves the delay time before a seleced item becomes checked while in
	 * single select mode.  Multi-select mode ignores this parameter.
	 *
	 * @return Time in MS, or NO_CHECK_ON_SELECTED to disabled auto checking
	 * selected items
	 */
	public int getCheckOnSelectedAfterMS() {
		return checkOnSelectedAfterMS;
	}

	/**
	 * Sets the delay time before a selected item becomes checked while in
	 * single select mode.  Multi-select mode ignores this parameter.
	 *
	 * @param checkOnSelectedAfterMS Time in MS, or NO_CHECK_ON_SELECTED to
	 *                               disabled auto checking selected items
	 */
	public void setCheckOnSelectedAfterMS(int checkOnSelectedAfterMS) {
		this.checkOnSelectedAfterMS = checkOnSelectedAfterMS;
	}

	public RecyclerView getRecyclerView() {
		return recyclerView;
	}

	public void setMultiCheckModeAllowed(boolean allowed) {
		mAllowMultiSelectMode = allowed;
		if (!allowed) {
			setMultiCheckMode(false);
		}
	}

	public boolean isMultiCheckModeAllowed() {
		return mAllowMultiSelectMode;
	}

	@Thunk
	void log(String s) {
		Log.d(TAG, getClass().getSimpleName() + "] " + s);
	}

	public boolean isAlwaysMultiSelectMode() {
		return mAlwaysMultiSelectMode;
	}

	public void setAlwaysMultiSelectMode(boolean mAlwaysMultiSelectMode) {
		this.mAlwaysMultiSelectMode = mAlwaysMultiSelectMode;
	}

	public void setEmptyView(View _initialView, View _emptyView) {
		this.emptyView = _emptyView;
		this.initialView = _initialView;

		if (emptyView == null) {
			if (observer != null) {
				unregisterAdapterDataObserver(observer);
			}
		} else {
			observer = new RecyclerView.AdapterDataObserver() {
				@Override
				public void onChanged() {
					checkEmpty();
				}

				@Override
				public void onItemRangeRemoved(int positionStart, int itemCount) {
					checkEmpty();
				}

				@Override
				public void onItemRangeInserted(int positionStart, int itemCount) {
					checkEmpty();
				}

			};
			registerAdapterDataObserver(observer);
		}

		if (neverSetItems && initialView != null) {
			initialView.setVisibility(View.VISIBLE);
			View view = initialView.findViewById(R.id.wait_frog);
			if (view != null) {
				Animation animation = new AlphaAnimation(1, 0.1f);
				animation.setInterpolator(new LinearInterpolator());
				animation.setDuration(1500);

				animation.setRepeatMode(Animation.REVERSE);
				animation.setRepeatCount(Animation.INFINITE);
				view.startAnimation(animation);
			}
		} else {
			checkEmpty();
		}
	}

	public boolean isNeverSetItems() {
		return neverSetItems;
	}

	public void triggerEmptyList() {
		neverSetItems = false;
		checkEmpty();
	}

	@Thunk
	void checkEmpty() {
		if (!AndroidUtilsUI.isUIThread()) {
			recyclerView.post(new Runnable() {
				@Override
				public void run() {
					checkEmpty();
				}
			});
			return;
		}
		if (initialView != null && initialView.getVisibility() == View.VISIBLE) {
			initialView.setVisibility(View.GONE);

			View view = initialView.findViewById(R.id.wait_frog);
			if (view != null) {
				view.setAnimation(null);
			}
		}
		if (emptyView == null || recyclerView == null) {
			return;
		}
		boolean shouldShowEmptyView = getItemCount() == 0;
		boolean showingEmptyView = emptyView.getVisibility() == View.VISIBLE;
		if (showingEmptyView != shouldShowEmptyView) {
			emptyView.setVisibility(shouldShowEmptyView ? View.VISIBLE : View.GONE);
			recyclerView.setVisibility(
					shouldShowEmptyView ? View.GONE : View.VISIBLE);
		}
	}
}
