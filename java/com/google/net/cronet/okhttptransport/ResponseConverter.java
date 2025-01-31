/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.net.cronet.okhttptransport;

import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Okio;
import okio.Source;
import org.chromium.net.UrlResponseInfo;

/**
 * Converts Cronet's responses (or, more precisely, its chunks as they come from Cronet's {@link
 * org.chromium.net.UrlRequest.Callback}), to OkHttp's {@link Response}.
 */
final class ResponseConverter {
  private static final String CONTENT_LENGTH_HEADER_NAME = "Content-Length";
  private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";

  /**
   * Creates an OkHttp's Response from the OkHttp-Cronet bridging callback.
   *
   * <p>As long as the callback's {@code UrlResponseInfo} is available this method is non-blocking.
   * However, this method doesn't fetch the entire body response. As a result, subsequent calls to
   * the result's {@link Response#body()} methods might block further.
   */
  Response toResponse(Request request, OkHttpBridgeRequestCallback callback) throws IOException {
    Response.Builder responseBuilder = new Response.Builder();

    UrlResponseInfo urlResponseInfo = getFutureValue(callback.getUrlResponseInfo());

    responseBuilder
        .request(request)
        .code(urlResponseInfo.getHttpStatusCode())
        .message(urlResponseInfo.getHttpStatusText())
        .protocol(convertProtocol(urlResponseInfo.getNegotiatedProtocol()))
        .body(createResponseBody(request, callback));

    for (Map.Entry<String, String> header : urlResponseInfo.getAllHeadersAsList()) {
      // TODO(danstahr): Don't propagate content encodings handled by Cronet
      responseBuilder.addHeader(header.getKey(), header.getValue());
    }

    return responseBuilder.build();
  }

  ListenableFuture<Response> toResponseAsync(
      Request request, OkHttpBridgeRequestCallback callback) {
    return Futures.whenAllComplete(callback.getUrlResponseInfo(), callback.getBodySource())
        .call(() -> toResponse(request, callback), MoreExecutors.directExecutor());
  }

  /**
   * Creates an OkHttp's ResponseBody from the OkHttp-Cronet bridging callback.
   *
   * <p>As long as the callback's {@code UrlResponseInfo} is available this method is non-blocking.
   * However, this method doesn't fetch the entire body response. As a result, subsequent calls to
   * {@link ResponseBody} methods might block further to fetch parts of the body.
   */
  private static ResponseBody createResponseBody(
      Request request, OkHttpBridgeRequestCallback callback) throws IOException {
    UrlResponseInfo responseInfo = getFutureValue(callback.getUrlResponseInfo());
    Source bodySource = getFutureValue(callback.getBodySource());

    @Nullable String contentType = getLastHeaderValue(CONTENT_TYPE_HEADER_NAME, responseInfo);
    @Nullable
    String contentLengthString = getLastHeaderValue(CONTENT_LENGTH_HEADER_NAME, responseInfo);
    long contentLength;

    // Ignore content-length header for HEAD requests (consistency with OkHttp)
    if (request.method().equals("HEAD")) {
      contentLength = 0;
    } else {
      try {
        contentLength = contentLengthString != null ? Long.parseLong(contentLengthString) : -1;
      } catch (NumberFormatException e) {
        // TODO(danstahr): add logging
        contentLength = -1;
      }
    }

    // Check for absence of body in No Content / Reset Content responses (OkHttp consistency)
    int code = responseInfo.getHttpStatusCode();
    if ((code == 204 || code == 205) && contentLength > 0) {
      throw new ProtocolException(
          "HTTP " + code + " had non-zero Content-Length: " + contentLengthString);
    }

    return ResponseBody.create(
        contentType != null ? MediaType.parse(contentType) : null,
        contentLength,
        Okio.buffer(bodySource));
  }

  /** Converts Cronet's negotiated protocol string to OkHttp's {@link Protocol}. */
  private static Protocol convertProtocol(String negotiatedProtocol) {
    // See
    // https://www.iana.org/assignments/tls-extensiontype-values/tls-extensiontype-values.xhtml#alpn-protocol-ids
    if (negotiatedProtocol.contains("quic")) {
      return Protocol.QUIC;
    } else if (negotiatedProtocol.contains("h3")) {
      // TODO(danstahr): Should be h3 for newer OkHttp
      return Protocol.QUIC;
    } else if (negotiatedProtocol.contains("spdy")) {
      return Protocol.HTTP_2;
    } else if (negotiatedProtocol.contains("h2")) {
      return Protocol.HTTP_2;
    } else if (negotiatedProtocol.contains("http1.1")) {
      return Protocol.HTTP_1_1;
    }

    return Protocol.HTTP_1_0;
  }

  /** Returns the last header value for the given name, or null if the header isn't present. */
  @Nullable
  private static String getLastHeaderValue(String name, UrlResponseInfo responseInfo) {
    List<String> headers = responseInfo.getAllHeaders().get(name);
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    return Iterables.getLast(headers);
  }

  private static <T> T getFutureValue(Future<T> future) throws IOException {
    try {
      return Uninterruptibles.getUninterruptibly(future);
    } catch (ExecutionException e) {
      throw new IOException(e);
    }
  }
}
