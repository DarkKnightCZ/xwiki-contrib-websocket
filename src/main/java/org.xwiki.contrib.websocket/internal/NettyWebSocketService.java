/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.websocket.internal;

import java.util.Map;
import java.util.HashMap;
import java.io.StringWriter;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.inject.Named;
import javax.inject.Provider;
import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import org.xwiki.component.phase.Initializable;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.contrib.websocket.WebSocketHandler;
import org.xwiki.component.internal.multi.ComponentManagerManager;
import org.apache.commons.lang3.exception.ExceptionUtils;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.channel.ChannelFuture;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.Channel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;


@Component
@Named("netty")
@Singleton
public class NettyWebSocketService implements WebSocketService, Initializable
{
    private Map<String, DocumentReference> userByKey = new HashMap<String, DocumentReference>();
    private Map<DocumentReference, String> keyByUser = new HashMap<DocumentReference, String>();

    @Inject
    private ComponentManagerManager compMgrMgr;

    @Inject
    private WebSocketConfig conf;

    @Inject
    private Logger logger;

    @Override
    public String getKey(DocumentReference userRef)
    {
        String key = keyByUser.get(userRef);
        if (key != null) {
            return key;
        }
        key = RandomStringUtils.randomAlphanumeric(20);
        keyByUser.put(userRef, key);
        userByKey.put(key, userRef);
        return key;
    }

    public void initialize()
    {
        try {
            boolean ssl = this.conf.sslEnabled();
            final SslContext sslCtx;
            if (ssl) {
                SelfSignedCertificate ssc = new SelfSignedCertificate();
                sslCtx = SslContext.newServerContext(ssc.certificate(), ssc.privateKey());
            } else {
                sslCtx = null;
            }

            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new WebSocketServerInitializer(sslCtx, this));

                Channel ch = b.bind(this.conf.getBindTo(), this.conf.getPort()).sync().channel();

                System.err.println("Open your web browser and navigate to " +
                        (ssl? "https" : "http") + "://127.0.0.1:" + this.conf.getPort() + '/');

                ch.closeFuture().sync();
            } finally {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class WebSocketServerInitializer extends ChannelInitializer<SocketChannel>
    {
        private final SslContext sslCtx;
        private final NettyWebSocketService nwss;

        public WebSocketServerInitializer(SslContext sslCtx, NettyWebSocketService nwss) {
            this.sslCtx = sslCtx;
            this.nwss = nwss;
        }

        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc()));
            }
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new HttpObjectAggregator(65536));
            pipeline.addLast(new WebSocketServerHandler(this.nwss));
        }
    }

    private static final class WebSocketServerHandler extends SimpleChannelInboundHandler<Object>
    {
        private final NettyWebSocketService nwss;
        private WebSocketServerHandshaker handshaker;
        private NettyWebSocket nws;

        public WebSocketServerHandler(NettyWebSocketService nwss)
        {
            this.nwss = nwss;
        }

        public void channelRead0(ChannelHandlerContext ctx, Object msg) {
            messageReceived(ctx, msg);
        }

        public void messageReceived(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest) {
                handleHttpRequest(ctx, (FullHttpRequest) msg);
            } else if (msg instanceof WebSocketFrame) {
                handleWebSocketFrame(ctx, (WebSocketFrame) msg);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            // Handle a bad request.
            if (!req.getDecoderResult().isSuccess()) {
                sendHttpResponse(ctx, req,
                                 new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                             HttpResponseStatus.BAD_REQUEST));
                return;
            }

            // Form: /wiki/handler?k=12345
            String uri = req.getUri();
            final String key = uri.substring(uri.indexOf("?k=")+3);
            uri = uri.substring(0, uri.indexOf("?k="));
            final String handlerName = uri.substring(uri.lastIndexOf('/') + 1);
            uri = uri.substring(0, uri.lastIndexOf('/'));
            final String wiki = uri.substring(uri.lastIndexOf('/') + 1);
            uri = uri.substring(0, uri.lastIndexOf('/'));
            final DocumentReference user = this.nwss.userByKey.get(key);

            if (req.getMethod() != HttpMethod.GET) {
                this.nwss.logger.debug("request method not GET");
            } else if (!"".equals(uri)) {
                this.nwss.logger.debug("leftover content after parsing URI");
            } else if (user == null) {
                this.nwss.logger.debug("request from unknown user");
            } else {
                // success

                ComponentManager cm =
                    this.nwss.compMgrMgr.getComponentManager("wiki:" + wiki, false);
                if (cm == null) {
                    ByteBuf content = Unpooled.copiedBuffer(
                       "ERROR: no wiki found named [" + wiki + "]", StandardCharsets.UTF_8);
                    sendHttpResponse(ctx, req,
                                     new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                                 HttpResponseStatus.NOT_FOUND,
                                                                 content));
                    return;
                }

                WebSocketHandler handler = null;
                try {
                    handler = cm.getInstance(WebSocketHandler.class, handlerName);
                } catch (Exception wat) { }
                if (handler == null) {
                    ByteBuf content = Unpooled.copiedBuffer(
                        "ERROR: no registered component for path [" + handlerName + "]",
                        StandardCharsets.UTF_8);
                    sendHttpResponse(ctx, req,
                                     new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                                 HttpResponseStatus.NOT_FOUND,
                                                                 content));
                    return;
                }

                String loc = getWebSocketLocation(req, this.nwss.conf.sslEnabled());
                WebSocketServerHandshakerFactory wsFactory =
                    new WebSocketServerHandshakerFactory(loc, null, false);
                handshaker = wsFactory.newHandshaker(req);
                if (handshaker == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                } else {
                    handshaker.handshake(ctx.channel(), req);
                }

                final NettyWebSocket nws = new NettyWebSocket(user, handlerName, ctx, key, wiki);

                try {
                    handler.onWebSocketConnect(nws);
                } catch (Exception e) {
                    this.nwss.logger.warn("Exception in {}.onWebSocketConnect()... [{}]",
                                          handler.getClass().getName(),
                                          ExceptionUtils.getStackTrace(e));
                }

                ctx.channel().closeFuture().addListener(new GenericFutureListener<ChannelFuture>() {
                    public void operationComplete(ChannelFuture f) {
                        nws.disconnect();
                    }
                });

                this.nws = nws;

                return;
            }
            // failure
            sendHttpResponse(ctx, req,
                             new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                         HttpResponseStatus.FORBIDDEN));
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame)
        {
            // Check for closing frame
            if (frame instanceof CloseWebSocketFrame) {
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
                return;
            }
            if (frame instanceof PingWebSocketFrame) {
                ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
                return;
            }
            if (!(frame instanceof TextWebSocketFrame)) {
                throw new UnsupportedOperationException(
                    String.format("%s frame types not supported", frame.getClass().getName()));
            }

            final String msg = ((TextWebSocketFrame) frame).text();
            this.nws.message(msg);
        }

        private static void sendHttpResponse(ChannelHandlerContext ctx,
                                             FullHttpRequest req,
                                             FullHttpResponse res)
        {
            // Generate an error page if response getStatus code is not OK (200).
            if (res.getStatus().code() != 200) {
                ByteBuf buf = Unpooled.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8);
                res.content().writeBytes(buf);
                buf.release();
                HttpHeaders.setContentLength(res, res.content().readableBytes());
            }

            // Send the response and close the connection if necessary.
            ChannelFuture f = ctx.channel().writeAndFlush(res);
            if (!HttpHeaders.isKeepAlive(req) || res.getStatus().code() != 200) {
                f.addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            this.nwss.logger.warn("Netty exceptionCaught() [{}]",
                                  ExceptionUtils.getStackTrace(cause));
            ctx.close();
        }

        private static String getWebSocketLocation(FullHttpRequest req, boolean ssl) {
            String location = req.headers().get(HttpHeaders.Names.HOST) + "/";
            if (ssl) {
                return "wss://" + location;
            } else {
                return "ws://" + location;
            }
        }
    }
}
