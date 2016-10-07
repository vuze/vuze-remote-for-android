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
import android.widget.ProgressBar;
import android.widget.TextView;

import com.squareup.picasso.Picasso;
import com.vuze.android.FlexibleRecyclerAdapter;
import com.vuze.android.FlexibleRecyclerSelectionListener;
import com.vuze.android.FlexibleRecyclerViewHolder;
import com.vuze.android.remote.*;
import com.vuze.util.DisplayFormatters;

import java.io.Serializable;

/**
 * Created by TuxPaper on 4/22/16.
 */
public class MetaSearchEnginesAdapter
	extends
	FlexibleRecyclerAdapter<MetaSearchEnginesAdapter.MetaSearchEnginesHolder, MetaSearchEnginesAdapter.MetaSearchEnginesInfo>
{
	public static class MetaSearchEnginesHolder
		extends FlexibleRecyclerViewHolder
	{
		final TextView tvName;

		final TextView tvCount;

		final ProgressBar pb;

		final ImageView iv;

		final ImageView ivChecked;

		public MetaSearchEnginesHolder(RecyclerSelectorInternal selector,
				View rowView) {
			super(selector, rowView);

			tvName = (TextView) rowView.findViewById(R.id.ms_engine_name);
			tvCount = (TextView) rowView.findViewById(R.id.ms_engine_count);
			pb = (ProgressBar) rowView.findViewById(R.id.ms_engine_pb);
			iv = (ImageView) rowView.findViewById(R.id.ms_engine_icon);
			ivChecked = (ImageView) rowView.findViewById(R.id.ms_engine_checked);
		}
	}

	public static class MetaSearchEnginesInfo
		implements Comparable<MetaSearchEnginesInfo>, Serializable
	{
		public final String uid;

		public String name;

		public boolean completed;

		public int count;

		public String iconURL;

		protected MetaSearchEnginesInfo(String uid) {
			this.uid = uid;
		}

		public MetaSearchEnginesInfo(String uid, String name, String iconURL,
				boolean completed) {
			this.name = name;
			this.iconURL = iconURL;
			this.completed = completed;
			this.uid = uid;
		}

		@Override
		public int compareTo(@NonNull MetaSearchEnginesInfo another) {
			return uid.compareTo(another.uid);
		}
	}

	private final Context context;

	public MetaSearchEnginesAdapter(Context context,
			FlexibleRecyclerSelectionListener<MetaSearchEnginesAdapter, MetaSearchEnginesAdapter.MetaSearchEnginesInfo> rs) {
		super(rs);
		this.context = context;
		Picasso.with(context).setLoggingEnabled(true);
		setHasStableIds(true);
	}

	@Override
	public long getItemId(int position) {
		MetaSearchEnginesInfo item = getItem(position);
		return item.uid.hashCode();
	}

	@Override
	public void onBindFlexibleViewHolder(MetaSearchEnginesHolder holder,
			int position) {
		MetaSearchEnginesInfo item = getItem(position);

		holder.tvName.setText(item.name);
		if (holder.tvCount != null) {
			holder.tvCount.setText(item.count == 0 ? "" : item.count == -1 ? "Error"
					: DisplayFormatters.formatNumber(item.count));
		}
		if (holder.pb != null) {
			holder.pb.setVisibility(item.completed ? View.GONE : View.VISIBLE);
		}
		if (holder.ivChecked != null) {
			holder.ivChecked.setVisibility(
					isItemChecked(position) ? View.VISIBLE : View.GONE);
		}
		if (holder.iv != null) {
			String url = "http://search.vuze.com/xsearch/imageproxy.php?url="
					+ item.iconURL;
			Picasso picassoInstance = VuzeRemoteApp.getPicassoInstance();
			picassoInstance.load(url).into(holder.iv);
		}
	}

	@Override
	public MetaSearchEnginesHolder onCreateFlexibleViewHolder(ViewGroup parent,
			int viewType) {

		LayoutInflater inflater = (LayoutInflater) context.getSystemService(
				Context.LAYOUT_INFLATER_SERVICE);

		View rowView = inflater.inflate(R.layout.row_ms_engine_sidelist, parent,
				false);

		MetaSearchEnginesHolder vh = new MetaSearchEnginesHolder(this, rowView);

		return vh;
	}

	public void refreshItem(String uid, boolean completed, int numAdded) {
		MetaSearchEnginesInfo info = new MetaSearchEnginesInfo(uid);
		if (info.completed == completed && numAdded == 0) {
			return;
		}
		final int position = getPositionForItem(info);
		if (position < 0) {
			return;
		}

		info = getItem(position);
		if (info == null) {
			return;
		}
		boolean changed = false;
		if (info.completed != completed) {
			info.completed = completed;
			changed = true;
		}
		int oldCount = info.count;
		if (numAdded < 0) {
			info.count = -1;
		} else {
			info.count += numAdded;
		}
		changed |= (info.count != oldCount);

		if (changed) {
			getRecyclerView().post(new Runnable() {
				@Override
				public void run() {
					notifyItemChanged(position);
				}
			});
		}
	}
}
