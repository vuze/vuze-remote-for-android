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
 * 
 */

package com.vuze.android.remote.rpc;

import java.io.*;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import org.apache.http.*;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpProtocolParams;

import android.annotation.TargetApi;
import android.net.http.AndroidHttpClient;
import android.os.Build;
import android.util.Log;

import com.aelitis.azureus.util.JSONUtils;
import com.vuze.android.remote.AndroidUtils;

/**
 * Connects to URL, decodes JSON results
 * 
 */
public class RestJsonClient
{
	private static final String TAG = "RPC";

	private static final boolean DEBUG_DETAILED = false;

	private static final boolean USE_STRINGBUFFER = true;

	public static Object connect(String url)
			throws RPCException {
		return connect("", url, null, null, null, false);
	}

	public static Map<?, ?> connect(String id, String url, Map<?, ?> jsonPost,
			Header[] headers, UsernamePasswordCredentials creds, boolean sendGzip)
			throws RPCException {
		long readTime = 0;
		long connSetupTime = 0;
		long connTime = 0;
		int bytesRead = 0;
		if (DEBUG_DETAILED) {
			Log.d(TAG, id + "] Execute " + url);
		}
		long now = System.currentTimeMillis();
		long then;

		Map<?, ?> json = Collections.EMPTY_MAP;

		try {

			URI uri = new URI(url);
			int port = uri.getPort();

			BasicHttpParams basicHttpParams = new BasicHttpParams();
			HttpProtocolParams.setUserAgent(basicHttpParams, "Vuze Android Remote");

			DefaultHttpClient httpclient;
			if ("https".equals(uri.getScheme())) {
				httpclient = MySSLSocketFactory.getNewHttpClient(port);
			} else {
				httpclient = new DefaultHttpClient(basicHttpParams);
			}

			//AndroidHttpClient.newInstance("Vuze Android Remote");
			httpclient.getCredentialsProvider().setCredentials(
					new AuthScope(null, -1), creds);

			// Prepare a request object
			HttpRequestBase httpRequest = jsonPost == null ? new HttpGet(uri)
					: new HttpPost(uri); // IllegalArgumentException

			if (jsonPost != null) {
				HttpPost post = (HttpPost) httpRequest;
				String postString = JSONUtils.encodeToJSON(jsonPost);
				if (AndroidUtils.DEBUG) {
					Log.d(TAG, id + "]  Post: " + postString);
				}

				AbstractHttpEntity entity = (sendGzip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO)
						? getCompressedEntity(postString) : new StringEntity(postString);
				post.setEntity(entity);

				post.setHeader("Accept", "application/json");
				post.setHeader("Content-type",
						"application/x-www-form-urlencoded; charset=UTF-8");
			}

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
				setupRequestFroyo(httpRequest);
			}

			if (headers != null) {
				for (Header header : headers) {
					httpRequest.setHeader(header);
				}
			}

			// Execute the request
			HttpResponse response;

			then = System.currentTimeMillis();
			if (AndroidUtils.DEBUG) {
				connSetupTime = (then - now);
				now = then;
			}

			response = httpclient.execute(httpRequest);

			then = System.currentTimeMillis();
			if (AndroidUtils.DEBUG) {
				connTime = (then - now);
				now = then;
			}

			HttpEntity entity = response.getEntity();

			// XXX STATUSCODE!

			if (entity != null) {

				long contentLength = entity.getContentLength();
				if (contentLength >= Integer.MAX_VALUE - 2) {
					throw new RPCException("JSON response too large");
				}

				// A Simple JSON Response Read
				InputStream instream = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO)
						? getUngzippedContent(entity) : entity.getContent();
				InputStreamReader isr = new InputStreamReader(instream, "utf8");

				StringBuilder sb;
				BufferedReader br;
				if (USE_STRINGBUFFER) {
					// Setting capacity saves StringBuffer from going through many
					// enlargeBuffers, and hopefully allows toString to not make a copy
					sb = new StringBuilder(contentLength > 512 ? (int) contentLength + 2
							: 512);
				} else {
					br = new BufferedReader(isr);
					br.mark(32767);
				}

				try {

					// 9775 files on Nexus 7
					// fastjson 1.1.39 (String)       :  924-1054ms
					// fastjson 1.1.39 (StringBuilder): 1227-1463ms
					// fastjson 1.1.39 (BR)           : 2233-2260ms
					// fastjson 1.1.39 (isr)          :      2312ms
					// GSON 2.2.4 (String)            : 1539-1760ms
					// GSON 2.2.4 (BufferedReader)    : 2646-3060ms

					if (USE_STRINGBUFFER) {
						char c[] = new char[8192];
						while (true) {
							int read = isr.read(c);
							if (read < 0) {
								break;
							}
							sb.append(c, 0, read);
						}

						if (AndroidUtils.DEBUG) {
							then = System.currentTimeMillis();
							if (DEBUG_DETAILED) {
								Log.d(TAG, id + "] " + sb.toString());
							}
							bytesRead = sb.length();
							readTime = (then - now);
							now = then;
						}

						json = JSONUtils.decodeJSON(sb.toString());
						//json = JSONUtilsGSON.decodeJSON(sb.toString());
					} else {

						//json = JSONUtils.decodeJSON(isr);
						json = JSONUtils.decodeJSON(br);
						//json = JSONUtilsGSON.decodeJSON(br);
					}

				} catch (Exception pe) {

					StatusLine statusLine = response.getStatusLine();
					if (statusLine != null && statusLine.getStatusCode() == 409) {
						throw new RPCException(response, "409");
					}

					try {
						String line;
						if (USE_STRINGBUFFER) {
							line = sb.subSequence(0, Math.min(128, sb.length())).toString();
						} else {
							br.reset();
							line = br.readLine().trim();
						}

						isr.close();

						if (AndroidUtils.DEBUG) {
							Log.d(TAG, id + "]line: " + line);
						}
						Header contentType = entity.getContentType();
						if (line.startsWith("<")
								|| line.contains("<html")
								|| (contentType != null && contentType.getValue().startsWith(
										"text/html"))) {
							// TODO: use android strings.xml
							throw new RPCException(
									response,
									"Could not retrieve remote client location information.  The most common cause is being on a guest wifi that requires login before using the internet.");
						}
					} catch (IOException io) {

					}

					Log.e(TAG, id, pe);
					if (statusLine != null) {
						String msg = statusLine.getStatusCode() + ": "
								+ statusLine.getReasonPhrase() + "\n" + pe.getMessage();
						throw new RPCException(msg, pe);
					}
					throw new RPCException(pe);
				} finally {
					closeOnNewThread(USE_STRINGBUFFER ? isr : br);
				}

				if (AndroidUtils.DEBUG) {
//					Log.d(TAG, id + "]JSON Result: " + json);
				}

			}
		} catch (RPCException e) {
			throw e;
		} catch (Throwable e) {
			Log.e(TAG, id, e);
			throw new RPCException(e);
		}

		if (AndroidUtils.DEBUG) {
			then = System.currentTimeMillis();
			Log.d(TAG, id + "] conn " + connSetupTime + "/" + connTime + "ms. Read "
					+ bytesRead + " in " + readTime + "ms, parsed in " + (then - now)
					+ "ms");
		}
		return json;
	}

	@TargetApi(Build.VERSION_CODES.FROYO)
	private static AbstractHttpEntity getCompressedEntity(String data)
			throws UnsupportedEncodingException {
		try {
			return AndroidHttpClient.getCompressedEntity(data.getBytes("UTF-8"), null);
		} catch (Throwable e) {
			return new StringEntity(data);
		}
	}

	// HttpEntity.getContent().close can take 30s!
	private static void closeOnNewThread(final Reader reader) {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					reader.close();
				} catch (Throwable t) {
				}
			}
		}, "closeInputStream");
		thread.setDaemon(true);
		thread.start();
	}

	@TargetApi(Build.VERSION_CODES.FROYO)
	private static InputStream getUngzippedContent(HttpEntity entity)
			throws IOException {
		return AndroidHttpClient.getUngzippedContent(entity);
	}

	@TargetApi(Build.VERSION_CODES.FROYO)
	private static void setupRequestFroyo(HttpRequestBase httpRequest) {
		AndroidHttpClient.modifyRequestToAcceptGzipResponse(httpRequest);
	}

}
