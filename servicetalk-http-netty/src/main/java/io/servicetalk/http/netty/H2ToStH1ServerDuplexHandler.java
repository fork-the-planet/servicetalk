/*
 * Copyright © 2019-2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.buffer.api.Buffer;
import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.http.api.Http2Exception;
import io.servicetalk.http.api.HttpHeaders;
import io.servicetalk.http.api.HttpHeadersFactory;
import io.servicetalk.http.api.HttpRequestMethod;
import io.servicetalk.http.api.HttpResponseMetaData;
import io.servicetalk.transport.api.ConnectionObserver.StreamObserver;
import io.servicetalk.transport.netty.internal.CloseHandler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;

import javax.annotation.Nullable;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderValues.ZERO;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.servicetalk.http.api.HttpProtocolVersion.HTTP_2_0;
import static io.servicetalk.http.api.HttpRequestMetaDataFactory.newRequestMetaData;
import static io.servicetalk.http.api.HttpRequestMethod.Properties.NONE;
import static io.servicetalk.http.netty.H2ToStH1ClientDuplexHandler.isInterim;
import static io.servicetalk.http.netty.H2ToStH1Utils.h1HeadersToH2Headers;
import static io.servicetalk.http.netty.H2ToStH1Utils.h2HeadersSanitizeForH1;
import static io.servicetalk.http.netty.HeaderUtils.clientMaySendPayloadBodyFor;
import static io.servicetalk.http.netty.HeaderUtils.shouldAddZeroContentLength;

final class H2ToStH1ServerDuplexHandler extends AbstractH2DuplexHandler {
    private boolean readHeaders;
    private boolean responseSent;

    H2ToStH1ServerDuplexHandler(BufferAllocator allocator, HttpHeadersFactory headersFactory,
                                CloseHandler closeHandler, StreamObserver observer) {
        super(allocator, headersFactory, closeHandler, observer);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof HttpResponseMetaData) {
            HttpResponseMetaData metaData = (HttpResponseMetaData) msg;
            boolean realResponse = !isInterim(metaData.status());
            if (realResponse) {
                // Notify the CloseHandler only about "real" responses. We don't expose 1xx "interim responses" to the
                // user, and handle them internally.
                responseSent = true;
                closeHandler.protocolPayloadBeginOutbound(ctx);
            } else if (responseSent) {
                // Discard an "interim response" if it arrives after a "real response" is already sent.
                return;
            }
            Http2Headers h2Headers = h1HeadersToH2Headers(metaData.headers());
            h2Headers.status(metaData.status().codeAsCharSequence());
            writeMetaData(ctx, metaData, h2Headers, realResponse, promise);
        } else if (msg instanceof Buffer) {
            writeBuffer(ctx, (Buffer) msg, promise);
        } else if (msg instanceof HttpHeaders) {
            writeTrailers(ctx, msg, promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Http2Exception {
        if (msg instanceof Http2HeadersFrame) {
            final Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
            final int streamId = headersFrame.stream().id();
            final Http2Headers h2Headers = headersFrame.headers();
            final HttpRequestMethod httpMethod;
            final String path;
            if (!readHeaders) {
                closeHandler.protocolPayloadBeginInbound(ctx);
                CharSequence method = h2Headers.getAndRemove(METHOD.value());
                CharSequence pathSequence = h2Headers.getAndRemove(PATH.value());
                if (pathSequence == null) {
                    throw protocolError(ctx, streamId, false,
                            "Incoming request must have '" + PATH.value() + "' header");
                }
                if (method == null) {
                    throw protocolError(ctx, streamId, false,
                            "Incoming request must have '" + METHOD.value() + "' header");
                }
                path = pathSequence.toString();
                httpMethod = sequenceToHttpRequestMethod(method);
                readHeaders = true;
            } else {
                httpMethod = null;
                path = null;
            }

            if (headersFrame.isEndStream()) {
                if (httpMethod != null) {
                    fireFullRequest(ctx, h2Headers, httpMethod, path, streamId);
                } else {
                    ctx.fireChannelRead(h2TrailersToH1TrailersServer(h2Headers));
                }
                closeHandler.protocolPayloadEndInbound(ctx);
            } else if (httpMethod == null) {
                throw protocolError(ctx, streamId, false,
                        "Incoming request must have '" + METHOD.value() + "' header");
            } else {
                ctx.fireChannelRead(newRequestMetaData(HTTP_2_0, httpMethod, path,
                        h2HeadersToH1HeadersServer(ctx, h2Headers, httpMethod, false, streamId)));
            }
        } else if (msg instanceof Http2DataFrame) {
            readDataFrame(ctx, msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void fireFullRequest(final ChannelHandlerContext ctx, final Http2Headers h2Headers,
                                 final HttpRequestMethod httpMethod, final String path,
                                 final int streamId) throws Http2Exception {
        ctx.fireChannelRead(newRequestMetaData(HTTP_2_0, httpMethod, path,
                h2HeadersToH1HeadersServer(ctx, h2Headers, httpMethod, true, streamId)));
    }

    private HttpHeaders h2HeadersToH1HeadersServer(final ChannelHandlerContext ctx,
                                                   final Http2Headers h2Headers,
                                                   @Nullable final HttpRequestMethod httpMethod,
                                                   final boolean fullRequest,
                                                   final int streamId) throws Http2Exception {
        CharSequence value = h2Headers.getAndRemove(AUTHORITY.value());
        if (value != null) {
            h2Headers.set(HOST, value);
        }
        h2Headers.remove(Http2Headers.PseudoHeaderName.SCHEME.value());
        h2HeadersSanitizeForH1(h2Headers);
        if (httpMethod != null) {
            final boolean containsContentLength = h2Headers.contains(CONTENT_LENGTH);
            if (clientMaySendPayloadBodyFor(httpMethod)) {
                if (!containsContentLength && fullRequest && shouldAddZeroContentLength(httpMethod)) {
                    h2Headers.set(CONTENT_LENGTH, ZERO);
                }
            } else if (containsContentLength) {
                throw protocolError(ctx, streamId, fullRequest, "content-length (" + h2Headers.get(CONTENT_LENGTH) +
                        ") header is not expected for " + httpMethod.name() + " request");
            }
        }
        return new NettyH2HeadersToHttpHeaders(h2Headers, headersFactory.validateCookies(),
                headersFactory.validateValues());
    }

    private HttpHeaders h2TrailersToH1TrailersServer(Http2Headers h2Headers) {
        return new NettyH2HeadersToHttpHeaders(h2Headers, headersFactory.validateCookies(),
                headersFactory.validateValues());
    }

    private static HttpRequestMethod sequenceToHttpRequestMethod(CharSequence sequence) {
        String strMethod = sequence.toString();
        HttpRequestMethod reqMethod = HttpRequestMethod.of(strMethod);
        return reqMethod != null ? reqMethod : HttpRequestMethod.of(strMethod, NONE);
    }
}
