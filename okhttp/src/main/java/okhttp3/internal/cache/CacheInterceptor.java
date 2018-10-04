/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.cache;

import java.io.IOException;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http.RealResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.discard;

/**
 * HTTP 协议中的缓存机制，其主要功能如下：
 * 从缓存中读取缓存，并创建缓存策略对象
 * 根据创建的缓存策略对象，从缓存、网络获取响应并生成最终的响应对象
 * 更新缓存内容，并返回响应对象
 */
public final class CacheInterceptor implements Interceptor {
  final InternalCache cache;

  public CacheInterceptor(InternalCache cache) {
    this.cache = cache;
  }

  /**
   * 去拦截请求处理缓存相关逻辑，其中使用缓存策略器CacheStrategy类来取出与缓存相关的数据，
   * 若缓存中有相应数据则取出，若缓存中数据不存在或过期则重新向服务器发出请求。当前本地有指定缓存时，
   * 重新请求的资源数据会被同步到当前本地中，涉及到的同步类是Cache、DiskLruCache类
   */
  @Override
  public Response intercept(Chain chain) throws IOException {
    Response cacheCandidate = cache != null
        ? cache.get(chain.request())////通过request得到候选缓存
        : null;

    long now = System.currentTimeMillis();

    /**
     * 创建缓存策略对象，并从中得到请求和响应
     * now 当前请求资源时间，用于校验缓存是否过期
     * chain.request() ： 一个Request对象，当前请求头的参数
     * cacheCandidate ： 一个Response对象，如果当前的缓存目录（这里的缓存目录是cache，类型为InternalCache）不等于null，则从缓存目录中取出对应请求的Response数据。
     */
    CacheStrategy strategy = new CacheStrategy.Factory(now, chain.request(), cacheCandidate).get();
    Request networkRequest = strategy.networkRequest;
    Response cacheResponse = strategy.cacheResponse;

    if (cache != null) {
      cache.trackResponse(strategy);
    }

    if (cacheCandidate != null && cacheResponse == null) {
      closeQuietly(cacheCandidate.body()); // The cache candidate wasn't applicable. Close it.
    }

    // 如果没有网络，且缓存为null，创建504的错误码 并返回
    if (networkRequest == null && cacheResponse == null) {
      return new Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(504)
          .message("Unsatisfiable Request (only-if-cached)")
          .body(Util.EMPTY_RESPONSE)
          .sentRequestAtMillis(-1L)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build();
    }

    //没有网络请求 且 有缓存，跳过网络，返回缓存
    if (networkRequest == null) {
      return cacheResponse.newBuilder()
          .cacheResponse(stripBody(cacheResponse))
          .build();
    }

    Response networkResponse = null;
    try {
      //前面的条件都不满足,则发送给  ConnectInterceptor 进行网络处理
      networkResponse = chain.proceed(networkRequest);
    } finally {
      //如果在Connectinterceptor中发送了错误导致了 响应 为空，则释放掉缓存
      if (networkResponse == null && cacheCandidate != null) {
        closeQuietly(cacheCandidate.body());
      }
    }

    /**
     * 304 表示资源没有更改过。
     * HttpEngine的invalidate()方法用于判断是采用缓存还是网络的Response
     * 如果netWork.code = 304，则直接使用缓存数据。
     * 如果netWork.code != 304, 会继续去判断缓存和网络的Last-Modified。
     * 缓存的最后修改时间更大，就采用缓存。
     * 网络的最后修改时间更大，就采用网络数据。
     */
    // 如果得到的缓存响应不是空的，并且网络响应的状态码为 304，则根据缓存响应结果生成最终的响应并返回
    if (cacheResponse != null) {
      if (networkResponse.code() == HTTP_NOT_MODIFIED) {
        Response response = cacheResponse.newBuilder()
            .headers(combine(cacheResponse.headers(), networkResponse.headers()))
            .sentRequestAtMillis(networkResponse.sentRequestAtMillis())
            .receivedResponseAtMillis(networkResponse.receivedResponseAtMillis())
            .cacheResponse(stripBody(cacheResponse))
            .networkResponse(stripBody(networkResponse))
            .build();
        networkResponse.body().close();

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        //更新缓存中的response
        cache.trackConditionalCacheHit();
        cache.update(cacheResponse, response);
        return response;
      } else {
        closeQuietly(cacheResponse.body());
      }
    }

    // 根据网络请求响应生成最终的响应
    Response response = networkResponse.newBuilder()
        .cacheResponse(stripBody(cacheResponse))
        .networkResponse(stripBody(networkResponse))
        .build();

    // 如果缓存对象不为空，则将响应加入到缓存中
    if (cache != null) {
      if (HttpHeaders.hasBody(response) && CacheStrategy.isCacheable(response, networkRequest)) {
        // Offer this request to the cache.
        CacheRequest cacheRequest = cache.put(response);
        return cacheWritingResponse(cacheRequest, response);
      }

      if (HttpMethod.invalidatesCache(networkRequest.method())) {
        try {
          cache.remove(networkRequest);
        } catch (IOException ignored) {
          // The cache cannot be written.
        }
      }
    }

    return response;
  }

  private static Response stripBody(Response response) {
    return response != null && response.body() != null
        ? response.newBuilder().body(null).build()
        : response;
  }

  /**
   * Returns a new source that writes bytes to {@code cacheRequest} as they are read by the source
   * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
   * may never exhaust the source stream and therefore not complete the cached response.
   */
  private Response cacheWritingResponse(final CacheRequest cacheRequest, Response response)
      throws IOException {
    // Some apps return a null body; for compatibility we treat that like a null cache request.
    if (cacheRequest == null) return response;
    Sink cacheBodyUnbuffered = cacheRequest.body();
    if (cacheBodyUnbuffered == null) return response;

    final BufferedSource source = response.body().source();
    final BufferedSink cacheBody = Okio.buffer(cacheBodyUnbuffered);

    Source cacheWritingSource = new Source() {
      boolean cacheRequestClosed;

      @Override public long read(Buffer sink, long byteCount) throws IOException {
        long bytesRead;
        try {
          bytesRead = source.read(sink, byteCount);
        } catch (IOException e) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true;
            cacheRequest.abort(); // Failed to write a complete cache response.
          }
          throw e;
        }

        if (bytesRead == -1) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true;
            cacheBody.close(); // The cache response is complete!
          }
          return -1;
        }

        sink.copyTo(cacheBody.buffer(), sink.size() - bytesRead, bytesRead);
        cacheBody.emitCompleteSegments();
        return bytesRead;
      }

      @Override public Timeout timeout() {
        return source.timeout();
      }

      @Override public void close() throws IOException {
        if (!cacheRequestClosed
            && !discard(this, HttpCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
          cacheRequestClosed = true;
          cacheRequest.abort();
        }
        source.close();
      }
    };

    String contentType = response.header("Content-Type");
    long contentLength = response.body().contentLength();
    return response.newBuilder()
        .body(new RealResponseBody(contentType, contentLength, Okio.buffer(cacheWritingSource)))
        .build();
  }

  /** Combines cached headers with a network headers as defined by RFC 7234, 4.3.4. */
  private static Headers combine(Headers cachedHeaders, Headers networkHeaders) {
    Headers.Builder result = new Headers.Builder();

    for (int i = 0, size = cachedHeaders.size(); i < size; i++) {
      String fieldName = cachedHeaders.name(i);
      String value = cachedHeaders.value(i);
      if ("Warning".equalsIgnoreCase(fieldName) && value.startsWith("1")) {
        continue; // Drop 100-level freshness warnings.
      }
      if (isContentSpecificHeader(fieldName) || !isEndToEnd(fieldName)
              || networkHeaders.get(fieldName) == null) {
        Internal.instance.addLenient(result, fieldName, value);
      }
    }

    for (int i = 0, size = networkHeaders.size(); i < size; i++) {
      String fieldName = networkHeaders.name(i);
      if (!isContentSpecificHeader(fieldName) && isEndToEnd(fieldName)) {
        Internal.instance.addLenient(result, fieldName, networkHeaders.value(i));
      }
    }

    return result.build();
  }

  /**
   * Returns true if {@code fieldName} is an end-to-end HTTP header, as defined by RFC 2616,
   * 13.5.1.
   */
  static boolean isEndToEnd(String fieldName) {
    return !"Connection".equalsIgnoreCase(fieldName)
        && !"Keep-Alive".equalsIgnoreCase(fieldName)
        && !"Proxy-Authenticate".equalsIgnoreCase(fieldName)
        && !"Proxy-Authorization".equalsIgnoreCase(fieldName)
        && !"TE".equalsIgnoreCase(fieldName)
        && !"Trailers".equalsIgnoreCase(fieldName)
        && !"Transfer-Encoding".equalsIgnoreCase(fieldName)
        && !"Upgrade".equalsIgnoreCase(fieldName);
  }

  /**
   * Returns true if {@code fieldName} is content specific and therefore should always be used
   * from cached headers.
   */
  static boolean isContentSpecificHeader(String fieldName) {
    return "Content-Length".equalsIgnoreCase(fieldName)
        || "Content-Encoding".equalsIgnoreCase(fieldName)
        || "Content-Type".equalsIgnoreCase(fieldName);
  }
}
