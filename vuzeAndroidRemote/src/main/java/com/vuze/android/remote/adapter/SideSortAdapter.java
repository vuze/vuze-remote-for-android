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

package com.vuze.android.remote.adapter;

import android.content.Context;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.vuze.android.FlexibleRecyclerAdapter;
import com.vuze.android.FlexibleRecyclerSelectionListener;
import com.vuze.android.FlexibleRecyclerViewHolder;
import com.vuze.android.remote.AndroidUtils;
import com.vuze.android.remote.R;

/**
 * Created by TuxPaper on 2/13/16.
 */
public class SideSortAdapter
	extends
	FlexibleRecyclerAdapter<SideSortAdapter.SideSortHolder, SideSortAdapter.SideSortInfo>
{

	private static final String TAG = "SideSortAdapter";

	private final Context context;

	private int currentSortID = -1;

	private boolean currentSortOrderAsc;

	private int paddingLeft = 0;

	public static final class SideSortInfo
		implements Comparable<SideSortInfo>
	{
		public String name;

		public long id;

		public boolean flipArrow;

		public SideSortInfo(long id, String sortName, boolean flipArrow) {
			this.id = id;
			name = sortName;
			this.flipArrow = flipArrow;
		}

		@Override
		public int compareTo(@NonNull SideSortInfo another) {
			return AndroidUtils.longCompare(id, another.id);
		}
	}

	static final public class SideSortHolder
		extends FlexibleRecyclerViewHolder
	{

		final TextView tvText;

		final ImageView iv;

		public SideSortHolder(RecyclerSelectorInternal selector, View rowView) {
			super(selector, rowView);

			tvText = (TextView) rowView.findViewById(R.id.sidesort_row_text);
			iv = (ImageView) rowView.findViewById(R.id.sidesort_row_image);
		}
	}

	private int viewType;

	public SideSortAdapter(Context context,
			FlexibleRecyclerSelectionListener selector) {
		super(selector);
		this.context = context;
		setHasStableIds(true);
	}

	@Override
	public SideSortHolder onCreateFlexibleViewHolder(ViewGroup parent,
			int viewType) {
		LayoutInflater inflater = (LayoutInflater) context.getSystemService(
				Context.LAYOUT_INFLATER_SERVICE);

		boolean isSmall = viewType == 1;
		View rowView = inflater.inflate(
				isSmall ? R.layout.row_sidesort_small : R.layout.row_sidesort, parent,
				false);

		SideSortHolder vh = new SideSortHolder(this, rowView);

		return vh;
	}

	@Override
	public void onBindFlexibleViewHolder(SideSortHolder holder, int position) {
		SideSortInfo item = getItem(position);
		holder.tvText.setText(item.name);

		int sortImageID;
		String contentDescription;
		if (currentSortID == item.id) {
			boolean adjustedSortOrder = item.flipArrow ? !currentSortOrderAsc : currentSortOrderAsc;
			if (adjustedSortOrder) {
				sortImageID = R.drawable.ic_arrow_upward_white_24dp;
				contentDescription = context.getResources().getString(R.string.spoken_sorted_ascending);
			} else {
				sortImageID = R.drawable.ic_arrow_downward_white_24dp;
				contentDescription = context.getResources().getString(R.string.spoken_sorted_descending);
			}
			holder.iv.setScaleType(adjustedSortOrder ? ImageView.ScaleType.FIT_START
					: ImageView.ScaleType.FIT_END);
		} else {
			sortImageID = 0;
			contentDescription = null;
		}
		holder.iv.setImageResource(sortImageID);
		holder.iv.setContentDescription(contentDescription);
		holder.tvText.setPadding(paddingLeft, 0, holder.tvText.getPaddingRight(),
				0);
	}

	@Override
	public long getItemId(int position) {
		SideSortInfo item = getItem(position);
		return item.id;
	}

	public void setCurrentSort(int id, boolean sortOrderAsc) {
		// TODO: Only invalidate old and new sort rows
		this.currentSortID = id;
		this.currentSortOrderAsc = sortOrderAsc;
		notifyDataSetInvalidated();
	}

	public void setPaddingLeft(int paddingLeft) {
		this.paddingLeft = paddingLeft;
		notifyDataSetInvalidated();
	}

	public int getCurrentSort() {
		return currentSortID;
	}

	public void setViewType(int viewType) {
		this.viewType = viewType;
		notifyDataSetInvalidated();
	}

	@Override
	public int getItemViewType(int position) {
		return viewType;
	}

}
