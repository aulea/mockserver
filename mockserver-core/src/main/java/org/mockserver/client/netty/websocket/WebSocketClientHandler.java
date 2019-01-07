package org.mockserver.client.netty.websocket;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.*;
import org.mockserver.client.netty.codec.mappers.FullHttpResponseToMockServerResponse;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.ContentTypeMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static io.netty.handler.codec.http.HttpHeaderValues.WEBSOCKET;
import static org.mockserver.client.netty.websocket.WebSocketClient.REGISTRATION_FUTURE;

public class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

    private final WebSocketClient webSocketClient;
    private final WebSocketClientHandshaker handshaker;
    private final MockServerLogger mockServerLogger = new MockServerLogger(this.getClass());

    WebSocketClientHandler(InetSocketAddress serverAddress, String contextPath, WebSocketClient webSocketClient) throws URISyntaxException {
        this.handshaker = WebSocketClientHandshakerFactory.newHandshaker(
            new URI("ws://" + serverAddress.getHostName() + ":" + serverAddress.getPort() + cleanContextPath(contextPath) + "/_mockserver_callback_websocket"),
            WebSocketVersion.V13,
            null,
            false,
            new DefaultHttpHeaders(),
            Integer.MAX_VALUE
        );
        this.webSocketClient = webSocketClient;
    }

    private String cleanContextPath(String contextPath) {
        if (!Strings.isNullOrEmpty(contextPath)) {
            return (!contextPath.startsWith("/") ? "/" : "") + contextPath;
        } else {
            return "";
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        mockServerLogger.trace("web socket client disconnected");
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) {
        Channel ch = ctx.channel();
        if (msg instanceof FullHttpResponse) {
            FullHttpResponse httpResponse = (FullHttpResponse) msg;
            final SettableFuture<String> registrationFuture = ch.attr(REGISTRATION_FUTURE).get();
            if (httpResponse.headers().contains(UPGRADE, WEBSOCKET, true) && !handshaker.isHandshakeComplete()) {
                handshaker.finishHandshake(ch, httpResponse);
                final String clientRegistrationId = httpResponse.headers().get("X-CLIENT-REGISTRATION-ID");
                registrationFuture.set(clientRegistrationId);
                mockServerLogger.trace("web socket client " + clientRegistrationId + " connected!");
            } else if (httpResponse.status().equals(HttpResponseStatus.NOT_IMPLEMENTED)) {
                String message = readRequestBody(httpResponse);
                registrationFuture.setException(new WebSocketException(message));
                mockServerLogger.warn(message);
            } else {
                registrationFuture.setException(new WebSocketException("Unsupported web socket message " + new FullHttpResponseToMockServerResponse().mapMockServerResponseToFullHttpResponse(httpResponse)));
            }
        } else if (msg instanceof WebSocketFrame) {
            WebSocketFrame frame = (WebSocketFrame) msg;
            if (frame instanceof TextWebSocketFrame) {
                webSocketClient.receivedTextWebSocketFrame((TextWebSocketFrame) frame);
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.write(new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof CloseWebSocketFrame) {
                mockServerLogger.trace("web socket client received request to close");
                ch.close();
            }
        }
    }

    private String readRequestBody(FullHttpResponse fullHttpResponse) {
        if (fullHttpResponse.content().readableBytes() > 0) {
            byte[] bodyBytes = new byte[fullHttpResponse.content().readableBytes()];
            fullHttpResponse.content().readBytes(bodyBytes);
            Charset requestCharset = ContentTypeMapper.getCharsetFromContentTypeHeader(fullHttpResponse.headers().get(CONTENT_TYPE));
            return new String(bodyBytes, requestCharset);
        }
        return "";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        mockServerLogger.error("web socket client caught exception", cause);
        final SettableFuture<String> registrationFuture = ctx.channel().attr(REGISTRATION_FUTURE).get();
        if (!registrationFuture.isDone()) {
            registrationFuture.setException(cause);
        }
        ctx.close();
    }
}
