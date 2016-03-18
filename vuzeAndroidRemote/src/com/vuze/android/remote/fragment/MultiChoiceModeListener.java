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

package com.vuze.android.remote.fragment;

import android.support.v7.view.ActionMode;
import android.support.v7.view.ActionMode.Callback;
import android.view.Menu;
import android.view.MenuItem;

public interface MultiChoiceModeListener extends Callback
{

	public abstract void onItemCheckedStateChanged(ActionMode actionMode,
			int position, long id, boolean checked);

	public abstract boolean onCreateActionMode(ActionMode actionMode, Menu menu);

	public abstract boolean onPrepareActionMode(ActionMode actionMode, Menu menu);

	public abstract boolean onActionItemClicked(ActionMode actionMode,
			MenuItem item);

	public abstract void onDestroyActionMode(ActionMode actionMode);
}