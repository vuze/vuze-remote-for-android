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

package com.vuze.android.remote;

import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.provider.Browser;
import android.support.v4.app.*;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.widget.AppCompatDrawableManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import com.rengwuxian.materialedittext.MaterialEditText;
import com.vuze.android.remote.activity.DrawerActivity;
import com.vuze.android.remote.fragment.ActionModeBeingReplacedListener;
import com.vuze.android.MenuDialogHelper;
import com.vuze.android.remote.rpc.RPCException;

import org.apache.http.conn.HttpHostConnectException;

@SuppressWarnings("WeakerAccess")
public class AndroidUtilsUI
{
	public static final boolean ALWAYS_DARK = false;

	private static final String TAG = "AndroidUtilsUI";

	static boolean hasAlertDialogOpen = false;

	private static AlertDialog currentSingleDialog;

	public static ArrayList<View> findByClass(ViewGroup root, Class type,
			ArrayList<View> list) {
		final int childCount = root.getChildCount();

		for (int i = 0; i < childCount; ++i) {
			final View child = root.getChildAt(i);
			if (type.isInstance(child)) {
				list.add(child);
			}

			if (child instanceof ViewGroup) {
				findByClass((ViewGroup) child, type, list);
			}
		}
		return list;
	}

	public static boolean handleCommonKeyDownEvents(Activity a, int keyCode,
			KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return false;
		}
		switch (keyCode) {
			case KeyEvent.KEYCODE_MEDIA_NEXT:
			case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: {
				ViewGroup vg = (ViewGroup) a.findViewById(android.R.id.content);
				ArrayList list = AndroidUtilsUI.findByClass(vg, ViewPager.class,
						new ArrayList<View>(0));

				if (list.size() > 0) {
					ViewPager viewPager = (ViewPager) list.get(0);
					viewPager.arrowScroll(View.FOCUS_RIGHT);
				}
				break;
			}

			case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
			case KeyEvent.KEYCODE_MEDIA_REWIND: {
				ViewGroup vg = (ViewGroup) a.findViewById(android.R.id.content);
				ArrayList list = AndroidUtilsUI.findByClass(vg, ViewPager.class,
						new ArrayList<View>(0));

				if (list.size() > 0) {
					ViewPager viewPager = (ViewPager) list.get(0);
					viewPager.arrowScroll(View.FOCUS_LEFT);
				}
				break;
			}

			case KeyEvent.KEYCODE_DPAD_LEFT: {
				if (a instanceof DrawerActivity) {
					DrawerActivity da = (DrawerActivity) a;
					DrawerLayout drawerLayout = da.getDrawerLayout();
					View viewFocus = a.getCurrentFocus();
					boolean canOpenDrawer = viewFocus != null
							&& "leftmost".equals(viewFocus.getTag());
					if (canOpenDrawer) {
						drawerLayout.openDrawer(Gravity.LEFT);
						drawerLayout.requestFocus();
						return true;
					}
				}
				break;
			}

		}

		return false;
	}

	public static void onCreate(Context context) {
		// AppThemeDark is LeanBack, and LeanBack is API 17
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
			boolean isTV = AndroidUtils.isTV();
			if (ALWAYS_DARK || isTV) {
				context.setTheme(R.style.AppThemeDark);

//				Window window = ((AppCompatActivity) context).getWindow();
//				window.clearFlags(WindowManager.LayoutParams
// .FLAG_LAYOUT_IN_OVERSCAN);
//				if (!isTV
//						&& Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
//					Window window = ((AppCompatActivity) context).getWindow();
//					window.clearFlags(WindowManager.LayoutParams
//							.FLAG_LAYOUT_IN_OVERSCAN);
//				}
			}
		}
	}

	public static int getStyleColor(Context context, int r_attr_theme_color) {
		TypedValue typedValue = new TypedValue();
		if (context == null) {
			return 0;
		}
		Resources.Theme theme = context.getTheme();
		if (!theme.resolveAttribute(r_attr_theme_color, typedValue, true)) {
			Log.e(TAG, "Could not get resolveAttribute " + r_attr_theme_color
					+ " for " + AndroidUtils.getCompressedStackTrace());
			return 0;
		}

		if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
				&& typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
			return typedValue.data;
		}

		try {
			TypedArray arr = context.obtainStyledAttributes(typedValue.data,
					new int[] {
							r_attr_theme_color
					});
			int c = arr.getColor(0, -1);
//			Log.d(TAG,
//					"Color for " + r_attr_theme_color + ", type " + typedValue.type +
//							";" + typedValue.coerceToString());// + " from " + arr);
			arr.recycle();
			if (c == -1) {
				if (AndroidUtils.DEBUG) {
					Log.e(TAG,
							"Could not get obtainStyledAttributes " + r_attr_theme_color
									+ "; " + typedValue + " for "
									+ AndroidUtils.getCompressedStackTrace());
				}

				// Sometimes TypedArray.getColor fails, but using TypedValue.resourceId
				// seems to work fine.  Could be related to Leanback?
				// TypedArray.getColor:
				// - failed | API 22 | DarkTheme  | Android TV
				// - failed | API 22 | DarkTheme  | Nexus 7
				// - Ok     | API 22 | LightTheme | Android TV
				// - Ok     | API 22 | LightTheme | Nexus 7
				// - Ok     | API 19 | LightTheme | GT 3
				// - Ok     | API 19 | DarkTheme  | GT 3
				// - Ok     | API 18 | LightTheme | Smartphone
				// - Ok     | API 18 | DarkTheme  | Smartphone
				// - Ok     | API 17 | DarkTheme  | FireTV
				return ContextCompat.getColor(context, typedValue.resourceId);
			} else {
				return c;
			}
		} catch (Resources.NotFoundException ignore) {
		}

		return typedValue.data;
	}

	public static void setViewChecked(View child, boolean activate) {
		if (child == null) {
			return;
		}
		if (child instanceof Checkable) {
			((Checkable) child).setChecked(activate);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			child.setActivated(activate);
		}
	}

	public static boolean handleBrokenListViewScrolling(Activity a, int
			keyCode) {
		// Hack for FireTV 1st Gen (and possibly others):
		// sometimes scrolling up/down stops being processed by ListView,
		// even though there's more list to show.  Handle this manually
		// Dirty implemenation -- doesn't take into consideration disabled rows
		// or page-up/down/top/bottom key modifiers
		if (keyCode == KeyEvent.KEYCODE_DPAD_UP
				|| keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
			ListView lv;

			View currentFocus = a.getCurrentFocus();
			if (currentFocus instanceof ListView) {
				lv = (ListView) currentFocus;
				if (lv.getChoiceMode() == ListView.CHOICE_MODE_SINGLE) {
					int position = lv.getSelectedItemPosition();
					if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
						position--;
					} else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
						position++;
					}

					if (position > 0 && position < lv.getCount()) {
						lv.setSelection(position);
						return true;
					}
				}
			}
		}

		return false;
	}

	public static void setManyMenuItemsEnabled(boolean enabled, Menu menu,
			int[] ids) {
		for (int id : ids) {
			MenuItem menuItem = menu.findItem(id);
			if (menuItem != null) {
				menuItem.setEnabled(enabled);
			}
		}
	}

	public static void setManyMenuItemsVisible(boolean visible, Menu menu,
			int[] ids) {
		for (int id : ids) {
			MenuItem menuItem = menu.findItem(id);
			if (menuItem != null) {
				menuItem.setVisible(visible);
			}
		}
	}

	public static int dpToPx(int dp) {
		return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
	}

	public static int pxToDp(int px) {
		return (int) (px / Resources.getSystem().getDisplayMetrics().density);
	}

	public static float pxToInchX(int px) {
		return (px / Resources.getSystem().getDisplayMetrics().xdpi);
	}

	public static float pxToInchY(int px) {
		return (px / Resources.getSystem().getDisplayMetrics().ydpi);
	}

	public static int spToPx(int sp) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
				Resources.getSystem().getDisplayMetrics());
	}

	public static MaterialEditText createFancyTextView(Context context) {
		MaterialEditText textView = new MaterialEditText(context);
		int styleColor = getStyleColor(context, android.R.attr.textColorPrimary);
		textView.setBaseColor(styleColor);
		textView.setMetTextColor(styleColor);
		textView.setFloatingLabel(MaterialEditText.FLOATING_LABEL_HIGHLIGHT);
		textView.setPrimaryColor(getStyleColor(context, R.attr.met_primary_color));
		return textView;
	}

	public interface OnTextBoxDialogClick
	{
		void onClick(DialogInterface dialog, int which, EditText editText);
	}

	public static AlertDialog createTextBoxDialog(Context context,
			int newtag_title, int newtag_hint,
			final OnTextBoxDialogClick onClickListener) {
		return createTextBoxDialog(context, newtag_title, newtag_hint, null,
				EditorInfo.IME_ACTION_DONE, onClickListener);
	}

	public static AlertDialog createTextBoxDialog(Context context,
			int newtag_title, int newtag_hint, String presetText,
			final int imeOptions, final OnTextBoxDialogClick onClickListener) {
		final AlertDialog.Builder builder = new AlertDialog.Builder(context);

		final AlertDialog[] dialog = {
				null
		};

		FrameLayout container = new FrameLayout(context);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		params.gravity = Gravity.CENTER_VERTICAL;
		container.setMinimumHeight(AndroidUtilsUI.dpToPx(100));
		int padding = AndroidUtilsUI.dpToPx(20);
		params.leftMargin = padding;
		params.rightMargin = padding;

		final MaterialEditText textView = AndroidUtilsUI.createFancyTextView(
				context);
		textView.setHint(newtag_hint);
		textView.setFloatingLabelText(
				context.getResources().getString(newtag_hint));
		textView.setSingleLine();
		textView.setImeOptions(imeOptions);
		textView.setOnEditorActionListener(new TextView.OnEditorActionListener()
		{
			@Override
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (dialog[0] == null) {
					return false;
				}
				if (actionId == imeOptions || (actionId == 0
						&& event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
					// Won't work
					//dialog[0].dismiss();
					//dialog[0].cancel();


					// From http://stackoverflow.com/a/38390615
					dialog[0].getButton(DialogInterface.BUTTON_POSITIVE).performClick();
					return true;
				}
				return false;
			}
		});
		textView.setLayoutParams(params);
		if (presetText != null) {
			textView.setText(presetText);
		}

		container.addView(textView);

		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1) {
			builder.setInverseBackgroundForced(true);
		}

		builder.setView(container);
		builder.setTitle(newtag_title);
		builder.setPositiveButton(android.R.string.ok,
				new DialogInterface.OnClickListener()
				{
					@Override
					public void onClick(DialogInterface dialog, int which) {
						onClickListener.onClick(dialog, which, textView);
					}
				});
		builder.setNegativeButton(android.R.string.cancel,
				new DialogInterface.OnClickListener()
				{
					@Override
					public void onClick(DialogInterface dialog, int which) {
					}
				});

		dialog[0] = builder.create();
		return dialog[0];
	}

	public static Fragment getFocusedFragment(FragmentActivity activity) {
		View currentFocus = activity.getCurrentFocus();
		if (currentFocus == null) {
			return null;
		}
		ViewParent currentFocusParent = currentFocus.getParent();
		if (currentFocusParent == null) {
			return null;
		}
		List<Fragment> fragments = activity.getSupportFragmentManager()
				.getFragments();
		if (fragments == null) {
			return null;
		}
		for (Fragment f : fragments) {
			if (f == null) {
				continue;
			}
			ViewParent v = currentFocusParent;
			View fragmentView = f.getView();
			while (v != null) {
				if (v == fragmentView) {
					return f;
				}
				v = v.getParent();
			}
		}

		return null;
	}

	public static boolean isUIThread() {
		return Looper.getMainLooper().getThread() == Thread.currentThread();
	}

	public static boolean sendOnKeyToFragments(FragmentActivity activity,
			int keyCode, KeyEvent event) {
		Fragment focusedFragment = AndroidUtilsUI.getFocusedFragment(activity);
		if (focusedFragment instanceof View.OnKeyListener) {
			if (((View.OnKeyListener) focusedFragment).onKey(null, keyCode, event)) {
				return true;
			}
		}
		return false;
	}

	public static boolean popupContextMenu(Context context,
			ActionModeBeingReplacedListener l, String title) {
		final android.support.v7.view.ActionMode.Callback actionModeCallback = l
				.getActionModeCallback();
		if (actionModeCallback == null) {
			return false;
		}

		MenuBuilder menuBuilder = new MenuBuilder(context);

		if (title != null) {
			try {
				Method mSetHeaderTitle = menuBuilder.getClass().getDeclaredMethod(
						"setHeaderTitleInt", CharSequence.class);
				if (mSetHeaderTitle != null) {
					mSetHeaderTitle.setAccessible(true);
					mSetHeaderTitle.invoke(menuBuilder, title);
				}
			} catch (Throwable ignore) {
			}
		}

		if (!actionModeCallback.onCreateActionMode(null, menuBuilder)) {
			return false;
		}

		actionModeCallback.onPrepareActionMode(null, menuBuilder);

		menuBuilder.setCallback(new MenuBuilder.Callback()
		{
			@Override
			public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
				return actionModeCallback.onActionItemClicked(null, item);
			}

			@Override
			public void onMenuModeChange(MenuBuilder menu) {

			}
		});

		MenuDialogHelper menuDialogHelper = new MenuDialogHelper(menuBuilder);
		menuDialogHelper.show(null);

		return true;
	}

	public static boolean popupContextMenu(final Activity activity,
			String title) {
		MenuBuilder menuBuilder = new MenuBuilder(activity);

		if (title != null) {
			try {
				Method mSetHeaderTitle = menuBuilder.getClass().getDeclaredMethod(
						"setHeaderTitleInt", CharSequence.class);
				if (mSetHeaderTitle != null) {
					mSetHeaderTitle.setAccessible(true);
					mSetHeaderTitle.invoke(menuBuilder, title);
				}
			} catch (Throwable ignore) {
			}
		}

		if (!activity.onCreateOptionsMenu(menuBuilder)) {
			return false;
		}

		activity.onPrepareOptionsMenu(menuBuilder);

		menuBuilder.setCallback(new MenuBuilder.Callback()
		{
			@Override
			public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
				return activity.onOptionsItemSelected(item);
			}

			@Override
			public void onMenuModeChange(MenuBuilder menu) {

			}
		});

		MenuDialogHelper menuDialogHelper = new MenuDialogHelper(menuBuilder);
		menuDialogHelper.show(null);

		return true;
	}

	public static void requestPermissions(Activity activity, String[]
			permissions,
			Runnable runnableOnGrant, Runnable runnableOnDeny) {

		if (!(activity instanceof AppCompatActivityM)) {
			Log.e(TAG,
					"requestPermissions: activity " + activity
							+ " is not AppCompatActivityM for "
							+ AndroidUtils.getCompressedStackTrace());
			// fallback and just run it and hope we have perms
			if (runnableOnGrant != null) {
				runnableOnGrant.run();
			}
			return;
		}

		AppCompatActivityM a = (AppCompatActivityM) activity;
		a.requestPermissions(permissions, runnableOnGrant, runnableOnDeny);
	}

	public static void linkify(TextView tv) {
		tv.setMovementMethod(LinkMovementMethod.getInstance());
		CharSequence t = tv.getText();
		if (!(t instanceof SpannableString)) {
			return;
		}
		SpannableString text = (SpannableString) t;

		int len = text.length();

		int next;
		for (int i = 0; i < text.length(); i = next) {
			next = text.nextSpanTransition(i, len, URLSpan.class);
			URLSpan[] old = text.getSpans(i, next, URLSpan.class);
			for (int j = old.length - 1; j >= 0; j--) {
				text.removeSpan(old[j]);

				UrlSpan2 span2 = new UrlSpan2(old[j].getURL());
				text.setSpan(span2, i, next, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
		}

	}

	public static final class UrlSpan2
			extends URLSpan
	{
		public UrlSpan2(String url) {
			super(url);
		}

		@Override
		public void onClick(View widget) {
			Uri uri = Uri.parse(getURL());
			Context context = widget.getContext();
			Intent intent = new Intent(Intent.ACTION_VIEW, uri);
			intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName());
			try {
				PackageManager pm = context.getPackageManager();
				ResolveInfo info = pm.resolveActivity(intent, 0);

				boolean badResolve = info == null;
				if (info != null) {
					ComponentInfo componentInfo = AndroidUtils.getComponentInfo(info);
					badResolve = componentInfo == null
							|| componentInfo.name.contains("frameworkpackagestubs");
				}

				if (badResolve) {
					// toast
					Toast.makeText(context, "Can't open " + uri,
							Toast.LENGTH_LONG).show();
				} else {
					context.startActivity(intent);
				}
			} catch (ActivityNotFoundException e) {
				Log.w("URLSpan",
						"Actvity was not found for intent, " + intent.toString());
			}
		}
	}

	public static boolean isChildOf(View child, ViewGroup vg) {
		if (child == null || vg == null) {
			return false;
		}
		ViewParent parent = child.getParent();
		while (parent != null) {
			if (parent == vg) {
				return true;
			}
			parent = parent.getParent();
		}
		return false;
	}

	public static int getScreenWidthPx(Context context) {
		Resources resources = context.getResources();
		DisplayMetrics dm = resources.getDisplayMetrics();
		int orientation = resources.getConfiguration().orientation;

		return (orientation == Configuration.ORIENTATION_LANDSCAPE
				? Math.max(dm.widthPixels, dm.heightPixels)
				: Math.min(dm.widthPixels, dm.heightPixels));
	}

	public static int getScreenWidthDp(Context context) {
		Resources resources = context.getResources();
		DisplayMetrics dm = resources.getDisplayMetrics();
		int orientation = resources.getConfiguration().orientation;

		return pxToDp(orientation == Configuration.ORIENTATION_LANDSCAPE
				? Math.max(dm.widthPixels, dm.heightPixels)
				: Math.min(dm.widthPixels, dm.heightPixels));
	}

	public static int getScreenHeightDp(Context context) {
		Resources resources = context.getResources();
		DisplayMetrics dm = resources.getDisplayMetrics();
		int orientation = resources.getConfiguration().orientation;

		return pxToDp(orientation == Configuration.ORIENTATION_PORTRAIT
				? Math.max(dm.widthPixels, dm.heightPixels)
				: Math.min(dm.widthPixels, dm.heightPixels));
	}

	public static Drawable getDrawableWithBounds(Context context, int resID) {
		Drawable drawableCompat = AppCompatDrawableManager.get().getDrawable(
				context, resID);
		if (drawableCompat != null) {
			if (drawableCompat.getBounds().isEmpty()) {
				drawableCompat.setBounds(0, 0, drawableCompat.getIntrinsicWidth(),
						drawableCompat.getIntrinsicHeight());
			}
		}
		return drawableCompat;
	}

	public static boolean childOrParentHasTag(View child, String tag) {
		if (child == null || tag == null) {
			return false;
		}
		if (tag.equals(child.getTag())) {
			return true;
		}
		ViewParent parent = child.getParent();
		while (parent != null) {

			if ((parent instanceof View) && tag.equals(((View) parent).getTag())) {
				return true;
			}
			parent = parent.getParent();
		}
		return false;

	}

	@SuppressWarnings("unused")
	public static boolean isViewContains(View view, int rx, int ry) {
		Rect rect = new Rect();
		view.getGlobalVisibleRect(rect);

		return rect.contains(rx, ry);
	}

	public static ViewGroup getContentView(Activity activity) {
		return (ViewGroup) ((ViewGroup) activity.findViewById(
				android.R.id.content)).getChildAt(0);
	}

	public static void walkTree(View rootView, String indent) {

		if (rootView instanceof FrameLayout) {
			FrameLayout f = (FrameLayout) rootView;

			int childCount = f.getChildCount();
			if (childCount > 0) {
				for (int i = 0; i < childCount; i++) {
					View childAt = f.getChildAt(i);
					Log.d(TAG, indent + "walkTree: child " + i + ": " + childAt + ";"
							+ Integer.toHexString(childAt.getId()));
					walkTree(childAt, indent + "\t");

				}
			} else {
				Log.d(TAG, indent + "walkTree: rootView=" + rootView);
			}
		}
	}

	/**
	 * Creates an AlertDialog.Builder that has the proper theme for Gingerbread
	 */
	public static AndroidUtils.AlertDialogBuilder createAlertDialogBuilder(
			Activity activity, int resource) {
		AlertDialog.Builder builder = new AlertDialog.Builder(activity);

		// Not sure if we need this anymore, but once upon a time, pre-honeycomb
		// (2.x) had dialog color issues
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1) {
			builder.setInverseBackgroundForced(true);
		}

		View view = View.inflate(activity, resource, null);
		builder.setView(view);

		return new AndroidUtils.AlertDialogBuilder(view, builder);
	}

	public static void openSingleAlertDialog(Activity ownerActivity,
			AlertDialog.Builder builder) {
		openSingleAlertDialog(ownerActivity, builder, null);
	}

	@SuppressWarnings("ConstantConditions")
	public static void openSingleAlertDialog(Activity ownerActivity,
			AlertDialog.Builder builder,
			final DialogInterface.OnDismissListener dismissListener) {
		// We should always be on the UI Thread, so no need to synchronize
		if (hasAlertDialogOpen) {
			if (currentSingleDialog == null
					|| currentSingleDialog.getOwnerActivity() == null
					|| !currentSingleDialog.getOwnerActivity().isFinishing()) {
				if (AndroidUtils.DEBUG) {
					Log.e(TAG, "Already have Alert Dialog Open " + currentSingleDialog);
				}
				return;
			}
		}

		if (AndroidUtils.DEBUG && hasAlertDialogOpen) {
			Log.e(TAG, "hasAlertDialogOpen flag ON, but dialog isn't showing");
		}

		hasAlertDialogOpen = true;

		try {
			currentSingleDialog = builder.show();
			currentSingleDialog.setOwnerActivity(ownerActivity);
			if (AndroidUtils.DEBUG) {
				Log.d(TAG,
						"Alert Dialog Open " + AndroidUtils.getCompressedStackTrace());
			}

			// Note: There's a builder.setOnDismissListener(), but it's API 17
			currentSingleDialog.setOnDismissListener(
					new DialogInterface.OnDismissListener()
					{
						@Override
						public void onDismiss(DialogInterface dialog) {
							hasAlertDialogOpen = false;
							if (dismissListener != null) {
								dismissListener.onDismiss(dialog);
							}
						}
					});
		} catch (WindowManager.BadTokenException bte) {
			// android.view.WindowManager$BadTokenException: Unable to add window --
			// token android.os.BinderProxy@42043ff8 is not valid; is your activity
			// running?
			// ignore.  We checked activity.isFinishing() earlier.. not much we
			// can do
			Log.e(TAG, "AlertDialog", bte);
		}
	}

	public static void showConnectionError(Activity activity, Throwable t,
			boolean allowContinue) {
		if (AndroidUtils.DEBUG) {
			Log.d(TAG, "showConnectionError "
					+ AndroidUtils.getCompressedStackTrace(t, 0, 9));
		}

		Throwable t2 = (t instanceof RPCException) ? t.getCause() : t;

		if ((t2 instanceof HttpHostConnectException)
				|| (t2 instanceof UnknownHostException)) {
			String message = t.getMessage();
			if (AndroidUtils.DEBUG) {
				Log.d(TAG, "showConnectionError Yup " + message);
			}
			if (message != null && message.contains("pair.vuze.com")) {
				showConnectionError(activity, R.string.connerror_pairing,
						allowContinue);
				return;
			}
		}
		String message = "";
		while (t != null) {
			String newMessage = t.getMessage();
			if (newMessage != null && message.contains(newMessage)) {
				t = t.getCause();
				continue;
			}
			message += newMessage + "\n";
			Throwable tReplace = t;
			while (tReplace != null) {
				Class<?> cla = tReplace.getClass();
				String name = cla.getName();
				message = message.replaceAll(name + ": ", cla.getSimpleName() + ": ");
				tReplace = tReplace.getCause();
			}
			t = t.getCause();
		}
		showConnectionError(activity, message, allowContinue);
	}

	public static void showConnectionError(Activity activity, int errMsgID,
			boolean allowContinue) {
		String errMsg = activity.getResources().getString(errMsgID);
		showConnectionError(activity, errMsg, allowContinue);
	}

	public static void showConnectionError(final Activity activity,
			final String errMsg, final boolean allowContinue) {
		if (AndroidUtils.DEBUG) {
			Log.d(TAG, "showConnectionError.string "
					+ AndroidUtils.getCompressedStackTrace());
		}
		if (activity == null) {
			Log.e(null, "No activity for error message " + errMsg);
			return;
		}
		activity.runOnUiThread(new Runnable()
		{
			public void run() {
				if (activity.isFinishing()) {
					if (AndroidUtils.DEBUG) {
						System.out.println("can't display -- finishing");
					}
					return;
				}
				AlertDialog.Builder builder = new AlertDialog.Builder(
						activity).setTitle(R.string.error_connecting).setMessage(
						errMsg).setCancelable(true).setNegativeButton(
						R.string.action_logout,
						new DialogInterface.OnClickListener()
						{
							public void onClick(DialogInterface dialog, int which) {
								if (activity.isTaskRoot()) {
									RemoteUtils.openRemoteList(activity);
								}
								activity.finish();
							}
						});
				if (allowContinue) {
					builder.setPositiveButton(R.string.button_continue,
							new DialogInterface.OnClickListener()
							{
								public void onClick(DialogInterface dialog, int which) {
								}
							});
				}
				openSingleAlertDialog(activity, builder);
			}
		});

	}

	public static void showDialog(Activity activity, int titleID,
			CharSequence msg) {
		String title = activity.getResources().getString(titleID);
		showDialog(activity, title, msg);
	}

	public static void showDialog(final Activity activity,
			final CharSequence title, final CharSequence msg) {
		activity.runOnUiThread(new Runnable()
		{
			public void run() {
				if (activity.isFinishing()) {
					if (AndroidUtils.DEBUG) {
						System.out.println("can't display -- finishing");
					}
					return;
				}
				AlertDialog.Builder builder = new AlertDialog.Builder(
						activity).setMessage(msg).setCancelable(true).setNegativeButton(
						android.R.string.ok, new DialogInterface.OnClickListener()
						{
							public void onClick(DialogInterface dialog, int which) {
							}
						});
				if (title != null) {
					builder.setTitle(title);
				}
				AlertDialog alertDialog = builder.show();
				View vMessage = alertDialog.findViewById(android.R.id.message);
				if (vMessage instanceof TextView) {
					linkify((TextView) vMessage);
				}
			}
		});

	}

	public static void showFeatureRequiresVuze(final Activity activity,
			final String feature) {
		activity.runOnUiThread(new Runnable()
		{
			public void run() {
				if (activity.isFinishing()) {
					if (AndroidUtils.DEBUG) {
						System.out.println("can't display -- finishing");
					}
					return;
				}
				String msg = activity.getResources().getString(R.string.vuze_required,
						feature);
				AlertDialog.Builder builder = new AlertDialog.Builder(
						activity).setMessage(msg).setCancelable(true).setPositiveButton(
						android.R.string.ok, new DialogInterface.OnClickListener()
						{
							public void onClick(DialogInterface dialog, int which) {
							}
						});
				builder.show();
			}
		});

	}

	public static void linkify(View view, int widgetId) {
		TextView textview = (TextView) view.findViewById(widgetId);
		if (textview != null) {
			textview.setMovementMethod(LinkMovementMethod.getInstance());
		} else {
			Log.d(TAG, "NO " + widgetId);
		}
	}

	/**
	 * Same as {@link Activity#runOnUiThread(Runnable)}, except ensures
	 * activity still exists while in UI Thread, before executing runnable
	 */
	public static void runOnUIThread(final Fragment fragment,
			final Runnable runnable) {
		Activity activity = fragment.getActivity();
		if (activity == null) {
			return;
		}
		activity.runOnUiThread(new Runnable()
		{
			@Override
			public void run() {
				Activity activity = fragment.getActivity();
				if (activity == null) {
					return;
				}
				if (runnable instanceof AndroidUtils.RunnableWithActivity) {
					((AndroidUtils.RunnableWithActivity) runnable).activity = activity;
				}
				runnable.run();
			}
		});
	}

	public static boolean showDialog(DialogFragment dlg, FragmentManager fm,
			String tag) {
		try {
			dlg.show(fm, tag);

//			Dialog dialog = dlg.getDialog();
//			Window window = dialog.getWindow();
//			WindowManager.LayoutParams attributes = window
//					.getAttributes();
//			Log.d(TAG, "showDialog: " + attributes);
//			DisplayMetrics metrics = getResources().getDisplayMetrics();
//			dlg.getDialog().getWindow().setLayout(ViewGroup.LayoutParams
// .MATCH_PARENT,
//					ViewGroup.LayoutParams.WRAP_CONTENT);

//			WindowManager.LayoutParams params = dlg.getDialog().getWindow()
// .getAttributes();
//
//			params.height = WindowManager.LayoutParams.MATCH_PARENT;
//			dlg.getDialog().getWindow().setAttributes(
//					(android.view.WindowManager.LayoutParams) params);

			return true;
		} catch (IllegalStateException e) {
			// Activity is no longer active (ie. most likely paused)
			return false;
		}
	}
}
