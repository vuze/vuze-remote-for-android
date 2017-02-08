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

package com.vuze.android.remote.session;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import com.vuze.android.remote.AndroidUtils;
import com.vuze.android.remote.R;
import com.vuze.android.remote.TransmissionVars;
import com.vuze.android.remote.rpc.ReplyMapReceivedListener;
import com.vuze.android.remote.rpc.SubscriptionListReceivedListener;
import com.vuze.android.remote.rpc.TransmissionRPC;
import com.vuze.util.MapUtils;
import com.vuze.util.Thunk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.text.TextUtils;
import android.util.Log;

/**
 * Subscription methods for a {@link Session}
 *
 * Created by TuxPaper on 12/13/16.
 */
public class Session_Subscription
{
	private static final String TAG = "SessionSubs";

	public interface SubscriptionsRemovedListener
	{
		void subscriptionsRemoved(List<String> subscriptionIDs);

		void subscriptionsRemovalError(
				Map<String, String> mapSubscriptionIDtoError);

		void subscriptionsRemovalException(Throwable t, String message);
	}

	@Thunk
	final Session session;

	@Thunk
	final List<SubscriptionListReceivedListener> receivedListeners = new CopyOnWriteArrayList<>();

	// <SubscriptionID, Map<Key, Value>>
	@Thunk
	Map<String, Map<?, ?>> mapSubscriptions;

	@Thunk
	long lastSubscriptionListReceivedOn;

	private boolean refreshingList;

	Session_Subscription(Session session) {
		this.session = session;
	}

	public void addListReceivedListener(SubscriptionListReceivedListener l,
			long triggerIfNewDataSinceMS) {
		session.ensureNotDestroyed();

		synchronized (receivedListeners) {
			if (!receivedListeners.contains(l)) {
				receivedListeners.add(l);
				if (mapSubscriptions != null
						&& lastSubscriptionListReceivedOn > triggerIfNewDataSinceMS) {
					if (AndroidUtils.DEBUG) {
						Log.d(TAG, "addSubscriptionListReceivedListener: triggering");
					}
					l.rpcSubscriptionListReceived(getList());
				}
			}
		}
	}

	public void createSubscription(final String rssURL, final String name) {
		session._executeRpc(new Session.RpcExecuter() {
			@Override
			public void executeRpc(TransmissionRPC rpc) {
				rpc.createSubscription(rssURL, name, new ReplyMapReceivedListener() {
					@Override
					public void rpcError(String id, Exception e) {

					}

					@Override
					public void rpcFailure(String id, String message) {

					}

					@Override
					public void rpcSuccess(String id, Map<?, ?> optionalMap) {
						refreshList();
					}

				});
			}
		});
	}

	void destroy() {
		receivedListeners.clear();
	}

	public Map<?, ?> getSubscription(String id) {
		session.ensureNotDestroyed();

		return MapUtils.getMapMap(mapSubscriptions, id, null);
	}

	public List<String> getList() {
		session.ensureNotDestroyed();

		synchronized (receivedListeners) {
			if (mapSubscriptions == null) {
				return Collections.emptyList();
			}

			return new ArrayList<>(mapSubscriptions.keySet());
		}
	}

	public int getListCount() {
		session.ensureNotDestroyed();

		synchronized (receivedListeners) {
			if (mapSubscriptions == null) {
				return 0;
			}

			return mapSubscriptions.size();
		}
	}

	public boolean isRefreshingList() {
		session.ensureNotDestroyed();

		return refreshingList;
	}

	@Thunk
	void setRefreshingList(boolean refreshingSubscriptionList) {
		session.ensureNotDestroyed();

		synchronized (session.mLock) {
			this.refreshingList = refreshingSubscriptionList;
		}
		for (SubscriptionListReceivedListener l : receivedListeners) {
			l.rpcSubscriptionListRefreshing(refreshingSubscriptionList);
		}
	}

	public void refreshList() {
		session.ensureNotDestroyed();

		setRefreshingList(true);
		session._executeRpc(new Session.RpcExecuter() {
			@Override
			public void executeRpc(TransmissionRPC rpc) {
				rpc.getSubscriptionList(new ReplyMapReceivedListener() {
					@Override
					public void rpcError(String id, Exception e) {
						session.subscription.setRefreshingList(false);
						for (SubscriptionListReceivedListener l : receivedListeners) {
							l.rpcSubscriptionListError(id, e);
						}
					}

					@Override
					public void rpcFailure(String id, String message) {
						session.subscription.setRefreshingList(false);
						for (SubscriptionListReceivedListener l : receivedListeners) {
							l.rpcSubscriptionListFailure(id, message);
						}
					}

					@Override
					public void rpcSuccess(String id, Map<?, ?> optionalMap) {
						session.subscription.setRefreshingList(false);

						Map map = MapUtils.getMapMap(optionalMap,
								TransmissionVars.FIELD_SUBSCRIPTION_LIST, null);
						if (map == null) {
							map = Collections.emptyMap();
						}

						lastSubscriptionListReceivedOn = System.currentTimeMillis();

						synchronized (receivedListeners) {
							if (mapSubscriptions == null || mapSubscriptions.size() == 0) {
								// risky cast of the day, but it's cool
								mapSubscriptions = (Map<String, Map<?, ?>>) map;
							} else {
								// already have subscriptions.. merge old "results" into new
								Map<String, Map<?, ?>> newMap = new HashMap<>();
								for (Object o : map.keySet()) {
									Map v = (Map) map.get(o);
									newMap.put((String) o, v);

									Map<?, ?> mapOldSubscription = mapSubscriptions.get(o);
									if (mapOldSubscription != null) {
										Object entries = mapOldSubscription.get(
												TransmissionVars.FIELD_SUBSCRIPTION_RESULTS);
										if (entries != null) {
											v.put(TransmissionVars.FIELD_SUBSCRIPTION_RESULTS,
													entries);
										}
									}
								}

								mapSubscriptions = newMap;
							}
						}
						if (receivedListeners.size() > 0) {
							List<String> list = session.subscription.getList();
							for (SubscriptionListReceivedListener l : receivedListeners) {
								l.rpcSubscriptionListReceived(list);
							}
						}
					}
				});
			}
		});
	}

	public void refreshResults(final String subscriptionID) {
		session._executeRpc(new Session.RpcExecuter() {
			@Override
			public void executeRpc(TransmissionRPC rpc) {
				rpc.getSubscriptionResults(subscriptionID,
						new ReplyMapReceivedListener() {
							@Override
							public void rpcError(String id, Exception e) {
								if (receivedListeners.size() > 0) {
									for (SubscriptionListReceivedListener l : receivedListeners) {
										l.rpcSubscriptionListRefreshing(false);
									}
								}
							}

							@Override
							public void rpcFailure(String id, String message) {
								if (receivedListeners.size() > 0) {
									for (SubscriptionListReceivedListener l : receivedListeners) {
										l.rpcSubscriptionListRefreshing(false);
									}
								}
							}

							@Override
							public void rpcSuccess(String id, Map<?, ?> optionalMap) {

								Map mapNewSubscriptions = MapUtils.getMapMap(optionalMap,
										TransmissionVars.FIELD_SUBSCRIPTION_LIST, null);
								Map mapSubscription = MapUtils.getMapMap(mapNewSubscriptions,
										subscriptionID, null);
								List listResults = MapUtils.getMapList(mapSubscription,
										TransmissionVars.FIELD_SUBSCRIPTION_RESULTS, null);

								synchronized (receivedListeners) {
									Map map = mapSubscriptions.get(subscriptionID);
									if (map != null) {
										map.put(TransmissionVars.FIELD_SUBSCRIPTION_RESULTS,
												listResults);
									}
								}

								if (receivedListeners.size() > 0) {
									List<String> list = session.subscription.getList();
									for (SubscriptionListReceivedListener l : receivedListeners) {
										l.rpcSubscriptionListReceived(list);
									}
								}
							}
						});
			}
		});
	}

	public void removeSubscription(@NonNull Activity activity,
			@NonNull final String[] subscriptionIDs,
			@Nullable final SubscriptionsRemovedListener l) {
		Resources resources = activity.getResources();
		String message;
		@StringRes
		int titleID;
		if (subscriptionIDs.length == 1) {
			Map<?, ?> subscription = session.subscription.getSubscription(
					subscriptionIDs[0]);
			String name = MapUtils.getMapString(subscription, "name", null);

			message = resources.getString(R.string.subscription_remove_text,
					TextUtils.htmlEncode(name));
			titleID = R.string.subscription_remove_title;
		} else {
			message = resources.getString(R.string.subscriptions_remove_text,
					"" + subscriptionIDs.length);
			titleID = R.string.subscriptions_remove_title;
		}

		DialogInterface.OnClickListener onDeleteClicked = new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				session.transmissionRPC.removeSubscriptions(subscriptionIDs,
						new ReplyMapReceivedListener() {
							@Override
							public void rpcError(String id, Exception e) {
								if (l != null) {
									l.subscriptionsRemovalException(e, null);
								}
							}

							@Override
							public void rpcFailure(String id, String message) {
								if (l != null) {
									l.subscriptionsRemovalException(null, message);
								}
							}

							@Override
							public void rpcSuccess(String id, Map<?, ?> optionalMap) {
								refreshList();
								if (l == null) {
									return;
								}
								List<String> successes = new ArrayList<>();
								Map<String, String> failures = new HashMap<>();
								for (Object key : optionalMap.keySet()) {
									String subscriptionID = (String) key;
									String result = (String) optionalMap.get(key);
									// "Removed" or "Error:"
									if ("removed".equalsIgnoreCase(result)) {
										successes.add(subscriptionID);
									} else if (result.startsWith("Error:")) {
										failures.put(subscriptionID, result);
									}
								}
								if (successes.size() > 0) {
									l.subscriptionsRemoved(successes);
								}
								if (failures.size() > 0) {
									l.subscriptionsRemovalError(failures);
								}
							}
						});
			}
		};
		AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(
				titleID).setMessage(AndroidUtils.fromHTML(message)).setPositiveButton(
						R.string.dialog_delete_button_remove,
						onDeleteClicked).setNegativeButton(android.R.string.cancel, null);
		builder.show();
	}

	public void removeListReceivedListener(SubscriptionListReceivedListener l) {
		synchronized (receivedListeners) {
			receivedListeners.remove(l);
		}
	}

	public void setResultRead(final String subscriptionID,
			final List<String> resultIDs, final boolean read) {
		session._executeRpc(new Session.RpcExecuter() {
			@Override
			public void executeRpc(TransmissionRPC rpc) {
				Map<String, Object> map = new HashMap<>(2);
				Map<String, Object> mapIDs = new HashMap<>(2);
				Map<String, Object> mapFields = new HashMap<>(2);
				Map<String, Object> mapResults = new HashMap<>(2);

				for (String id : resultIDs) {
					HashMap<String, Object> mapResultFields = new HashMap<>(2);
					mapResultFields.put(TransmissionVars.FIELD_SUBSCRIPTION_RESULT_ISREAD,
							read);
					mapResults.put(id, mapResultFields);
				}

				mapFields.put(TransmissionVars.FIELD_SUBSCRIPTION_RESULTS, mapResults);
				mapIDs.put(subscriptionID, mapFields);
				map.put("ids", mapIDs);

				rpc.simpleRpcCall(TransmissionVars.METHOD_SUBSCRIPTION_SET, map,
						new ReplyMapReceivedListener() {
							@Override
							public void rpcError(String id, Exception e) {

							}

							@Override
							public void rpcFailure(String id, String message) {

							}

							@Override
							public void rpcSuccess(String id, Map<?, ?> optionalMap) {
								if (optionalMap == null) {
									return;
								}

								refreshResults(subscriptionID);
								/* Instead of refreshSubscriptionResult, we could use this:
								for (Object o: optionalMap.keySet()) {
									String subID = (String) o;
									Object v = optionalMap.get(o);
									if (v instanceof Map) {
										Map map = (Map) v;
										if (map.size() > 1) {
											Map<?, ?> mapSubscription = mapSubscriptions.get(subID);
											if (mapSubscription != null) {
												mapSubscription.clear();
												mapSubscription.putAll(map);
											}
										}
									}
									// TODO: trigger
								}
								*/

								// newResultsCount probably changed
								refreshList();
							}
						});
			}
		});
	}

	public void setField(final String subscriptionID,
			final Map<String, Object> itemsToSet) {
		session._executeRpc(new Session.RpcExecuter() {
			@Override
			public void executeRpc(TransmissionRPC rpc) {
				Map<String, Object> map = new HashMap<>(2);
				Map<String, Object> mapIDs = new HashMap<>(2);

				mapIDs.put(subscriptionID, itemsToSet);
				map.put("ids", mapIDs);
				rpc.simpleRpcCall(TransmissionVars.METHOD_SUBSCRIPTION_SET, map,
						new ReplyMapReceivedListener() {
							private void refresh() {
								refreshResults(subscriptionID);
								refreshList();
							}

							@Override
							public void rpcError(String id, Exception e) {

							}

							@Override
							public void rpcFailure(String id, String message) {

							}

							@Override
							public void rpcSuccess(String id, Map<?, ?> optionalMap) {
								refresh();
							}
						});
			}
		});
	}
}
