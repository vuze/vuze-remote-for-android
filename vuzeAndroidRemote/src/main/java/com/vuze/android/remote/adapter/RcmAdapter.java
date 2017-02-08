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

package com.vuze.android.remote.adapter;

import java.util.*;

import com.vuze.android.FlexibleRecyclerAdapter;
import com.vuze.android.FlexibleRecyclerSelectionListener;
import com.vuze.android.FlexibleRecyclerViewHolder;
import com.vuze.android.remote.*;
import com.vuze.android.remote.spanbubbles.SpanTags;
import com.vuze.util.*;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

public class RcmAdapter
	extends FlexibleRecyclerAdapter<RcmAdapter.ViewHolder, String>
	implements Filterable, AdapterFilterTalkbalk<String>
{
	private static final String TAG = "RCMAdapter";

	private static final boolean DEBUG = AndroidUtils.DEBUG;

	public interface RcmSelectionListener
		extends FlexibleRecyclerSelectionListener<RcmAdapter, String>
	{
		Map getSearchResultMap(String hash);

		List<String> getSearchResultList();

		void downloadResult(String id);
	}

	static class ViewHolder
		extends FlexibleRecyclerViewHolder
	{
		TextView tvName;

		TextView tvInfo;

		TextView tvTags;

		TextView tvSize;

		ProgressBar pbRank;

		ImageButton ibDownload;

		public ViewHolder(RecyclerSelectorInternal selector, View rowView) {
			super(selector, rowView);
		}
	}

	private final ComparatorMapFields sorter;

	private final View.OnClickListener onDownloadClickedListener;

	private final int inflateID;

	@Thunk
	Context context;

	@Thunk
	final RcmSelectionListener rs;

	private final Object mLock = new Object();

	private RcmAdapterFilter filter;

	public RcmAdapter(Context context, RcmSelectionListener rs) {
		super(rs);
		this.context = context;
		this.rs = rs;

		inflateID = AndroidUtils.usesNavigationControl()
				? R.layout.row_rcm_list_dpad : R.layout.row_rcm_list;

		onDownloadClickedListener = new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				RecyclerView.ViewHolder viewHolder = getRecyclerView().findContainingViewHolder(
						v);

				if (viewHolder == null) {
					return;
				}
				int position = viewHolder.getAdapterPosition();
				String id = getItem(position);

				RcmAdapter.this.rs.downloadResult(id);
			}
		};

		sorter = new ComparatorMapFields() {

			public Throwable lastError;

			@Override
			public int reportError(Comparable<?> oLHS, Comparable<?> oRHS,
					Throwable t) {
				if (lastError != null) {
					if (t.getCause().equals(lastError.getCause())
							&& t.getMessage().equals(lastError.getMessage())) {
						return 0;
					}
				}
				lastError = t;
				Log.e(TAG, "MetaSort", t);
				VuzeEasyTracker.getInstance(RcmAdapter.this.context).logError(t);
				return 0;
			}

			@Override
			public Map<?, ?> mapGetter(Object o) {
				return RcmAdapter.this.rs.getSearchResultMap((String) o);
			}
		};

	}

	@Override
	public ViewHolder onCreateFlexibleViewHolder(ViewGroup parent, int viewType) {
		LayoutInflater inflater = (LayoutInflater) context.getSystemService(
				Context.LAYOUT_INFLATER_SERVICE);
		View rowView = inflater.inflate(inflateID, parent, false);
		ViewHolder viewHolder = new ViewHolder(this, rowView);
		viewHolder.tvName = (TextView) rowView.findViewById(R.id.rcmrow_title);
		viewHolder.tvInfo = (TextView) rowView.findViewById(R.id.rcmrow_info);
		viewHolder.tvTags = (TextView) rowView.findViewById(R.id.rcmrow_tags);
		viewHolder.tvSize = (TextView) rowView.findViewById(R.id.rcmrow_size);
		viewHolder.pbRank = (ProgressBar) rowView.findViewById(R.id.rcmrow_rank);
		if (viewHolder.pbRank != null) {
			viewHolder.pbRank.setMax(100);
		}
		viewHolder.ibDownload = (ImageButton) rowView.findViewById(
				R.id.rcmrow_dl_button);
		if (viewHolder.ibDownload != null) {
			viewHolder.ibDownload.setOnClickListener(onDownloadClickedListener);
		}

		return viewHolder;
	}

	@Override
	public void onBindFlexibleViewHolder(ViewHolder holder, int position) {
		Map<?, ?> mapRCM = rs.getSearchResultMap(getItem(position));

		if (holder.tvName != null) {
			String s = MapUtils.getMapString(mapRCM, TransmissionVars.FIELD_RCM_NAME,
					"");
			holder.tvName.setText(AndroidUtils.lineBreaker(s));
		}

		if (holder.tvSize != null) {
			long size = MapUtils.getMapLong(mapRCM, "size", 0);
			String s = size <= 0 ? ""
					: DisplayFormatters.formatByteCountToKiBEtc(size, true);
			holder.tvSize.setText(s);
		}

		if (holder.tvInfo != null) {
			long rank = MapUtils.getMapLong(mapRCM, TransmissionVars.FIELD_RCM_RANK,
					0);
			long numSeeds = MapUtils.getMapLong(mapRCM,
					TransmissionVars.FIELD_RCM_SEEDS, -1);
			long numPeers = MapUtils.getMapLong(mapRCM,
					TransmissionVars.FIELD_RCM_PEERS, -1);
			StringBuffer sb = new StringBuffer();

			//sb.append("Discovery Strength: " + rank);

			if (holder.pbRank != null) {
				holder.pbRank.setProgress((int) rank);
			}

			long pubDate = MapUtils.getMapLong(mapRCM,
					TransmissionVars.FIELD_RCM_PUBLISHDATE, 0);
			if (pubDate > 0) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(context.getString(R.string.published_x_ago,
						DateUtils.getRelativeDateTimeString(context, pubDate,
								DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS * 2,
								0).toString()));
			}

			long lastSeenSecs = MapUtils.getMapLong(mapRCM,
					TransmissionVars.FIELD_RCM_LAST_SEEN_SECS, 0);
			if (lastSeenSecs > 0) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(context.getString(R.string.last_seen_x,
						DateUtils.getRelativeDateTimeString(context, lastSeenSecs * 1000,
								DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS * 2,
								0).toString()));
			}

			if (numSeeds >= 0 || numPeers >= 0) {
				if (sb.length() > 0) {
					sb.append('\n');
				}

				if (numSeeds >= 0) {
					sb.append(context.getString(R.string.x_seeds,
							DisplayFormatters.formatNumber(numSeeds)));
				}
				if (numPeers >= 0) {
					if (numSeeds >= 0) {
						sb.append(" \u2022 ");
					}
					sb.append(context.getString(R.string.x_peers,
							DisplayFormatters.formatNumber(numPeers)));
				}
			}

			holder.tvInfo.setText(sb);
		}

		if (holder.tvTags != null) {
			List<?> listTags = MapUtils.getMapList(mapRCM,
					TransmissionVars.FIELD_RCM_TAGS, Collections.EMPTY_LIST);
			if (listTags.size() == 0) {
				holder.tvTags.setVisibility(View.GONE);
			} else {

				SpanTags spanTag = new SpanTags(context, null, holder.tvTags, null);
				spanTag.setLinkTags(false);
				spanTag.setShowIcon(false);
				spanTag.addTagNames((List<String>) listTags);
				spanTag.updateTags();

				holder.tvTags.setVisibility(View.VISIBLE);
			}
		}
	}

	@Override
	public @NonNull RcmAdapterFilter getFilter() {
		if (filter == null) {
			// xxx java.lang.RuntimeException: Can't create handler inside thread
			// that has not called Looper.prepare()
			filter = new RcmAdapterFilter(this, rs, mLock);
		}
		return filter;
	}

	public void setSort(String[] fieldIDs, Boolean[] sortOrderAsc) {
		synchronized (mLock) {
			Boolean[] order;
			if (sortOrderAsc == null) {
				order = new Boolean[fieldIDs.length];
				Arrays.fill(order, Boolean.FALSE);
			} else if (sortOrderAsc.length != fieldIDs.length) {
				order = new Boolean[fieldIDs.length];
				Arrays.fill(order, Boolean.FALSE);
				System.arraycopy(sortOrderAsc, 0, order, 0, sortOrderAsc.length);
			} else {
				order = sortOrderAsc;
			}
			sorter.setSortFields(fieldIDs, order);
		}
		doSort();
	}

	public void setSort(Comparator<? super Map<?, ?>> comparator) {
		synchronized (mLock) {
			sorter.setComparator(comparator);
		}
		doSort();
	}

	private void doSort() {
		if (!sorter.isValid()) {
			if (DEBUG) {
				Log.d(TAG, "doSort skipped: no comparator and no sort");
			}
			return;
		}
		if (DEBUG) {
			Log.d(TAG, "sort: " + sorter.toDebugString());
		}

		sortItems(sorter);
	}

	@Override
	public List<String> doSort(List<String> items, boolean createNewList) {
		return doSort(items, sorter, createNewList);
	}

	@Override
	public void lettersUpdated(HashMap<String, Integer> mapLetterCount) {
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (filter != null) {
			filter.saveToBundle(outState);
		}
	}

	@Override
	public void onRestoreInstanceState(Bundle savedInstanceState,
			RecyclerView rv) {
		super.onRestoreInstanceState(savedInstanceState, rv);
		if (filter != null) {
			filter.restoreFromBundle(savedInstanceState);
		}
	}

}
