
// http://stackoverflow.com/a/19506010

package com.vuze.android.util;

import com.vuze.util.Thunk;

import android.content.Context;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;

/**
 * Detects left and right swipes across a view.
 */
public class OnSwipeTouchListener
	implements OnTouchListener
{

	private final GestureDetector gestureDetector;

	private View view;

	public OnSwipeTouchListener(Context context) {
		gestureDetector = new GestureDetector(context, new GestureListener());
	}

	public void onSwipeLeft() {
	}

	public void onSwipeRight() {
	}

	public boolean onTouch(View v, MotionEvent event) {
		view = v;
		return gestureDetector.onTouchEvent(event);
	}

	@Thunk
	final class GestureListener
		extends SimpleOnGestureListener
	{

		private static final int SWIPE_DISTANCE_THRESHOLD = 100;

		private static final int SWIPE_VELOCITY_THRESHOLD = 100;

		@Override
		public boolean onDown(MotionEvent e) {
			return true;
		}

		@Override
		public boolean onSingleTapUp(MotionEvent e) {
			onClick();
			return super.onSingleTapUp(e);
		}

		@Override
		public boolean onDoubleTap(MotionEvent e) {
			onDoubleClick();
			return super.onDoubleTap(e);
		}

		@Override
		public void onLongPress(MotionEvent e) {
			onLongClick();
			super.onLongPress(e);
		}

		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
				float velocityY) {
			if (e1 == null || e2 == null) {
				return false;
			}
			float distanceX = e2.getX() - e1.getX();
			float distanceY = e2.getY() - e1.getY();
			if (Math.abs(distanceX) > Math.abs(distanceY)
					&& Math.abs(distanceX) > SWIPE_DISTANCE_THRESHOLD
					&& Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
				if (distanceX > 0)
					onSwipeRight();
				else
					onSwipeLeft();
				return true;
			}
			return false;
		}
	}

	@SuppressWarnings({"EmptyMethod", "WeakerAccess"})
	public void onDoubleClick() {
	}

	@SuppressWarnings({"EmptyMethod", "WeakerAccess"})
	public void onLongClick() {

	}

	@SuppressWarnings({"EmptyMethod", "WeakerAccess"})
	public void onClick() {
	}
}