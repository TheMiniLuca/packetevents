/*
 * This file is part of packetevents - https://github.com/retrooper/packetevents
 * Copyright (C) 2026 retrooper and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.retrooper.packetevents.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.retrooper.packetevents.protocol.PacketSide;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackendPacketCompressionTest {
    private enum Event { COMPRESSION_ENABLED, OTHER }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("ViaVersion 방식 재배치와 함께 압축 이벤트를 전달해도 순환하거나 중복 전달하지 않는다")
    void compressionDoesNotLoopWithViaRelocation(final boolean viaFirst) throws Exception {
        final var calls = new ArrayList<String>();
        final var eventCount = new AtomicInteger();
        final var viaVisits = new AtomicInteger();
        final var session = mock(BackendPacketSession.class);
        when(session.process(any(ByteBuf.class), any(PacketSide.class))).thenAnswer(call -> {
            calls.add("packet-" + call.<PacketSide>getArgument(1).name());
            return true;
        });
        final var channel = new EmbeddedChannel();
        try {
            final var pipeline = channel.pipeline();
            pipeline.addLast("minecraft-decoder", new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                    calls.add("minecraft-decode"); ctx.fireChannelRead(msg);
                }
            });
            pipeline.addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter() {
                @Override public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
                    calls.add("minecraft-encode"); ctx.write(msg,promise);
                }
            });
            pipeline.addLast("event-observer", new ChannelInboundHandlerAdapter() {
                @Override public void userEventTriggered(final ChannelHandlerContext ctx, final Object event) {
                    eventCount.incrementAndGet(); ctx.fireUserEventTriggered(event);
                }
            });
            pipeline.addBefore("minecraft-decoder",BackendPacketDecoder.NAME,new BackendPacketDecoder(session));
            pipeline.addBefore("minecraft-encoder",BackendPacketEncoder.NAME,new BackendPacketEncoder(session));
            final String decoderAnchor = viaFirst ? BackendPacketDecoder.NAME : "minecraft-decoder";
            final String encoderAnchor = viaFirst ? BackendPacketEncoder.NAME : "minecraft-encoder";
            pipeline.addBefore(decoderAnchor,"via-decoder",new RelocatingViaDecoder(calls,viaVisits));
            pipeline.addBefore(encoderAnchor,"via-encoder",new RelocatingViaEncoder(calls));
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) { pipeline.remove("compression-decoder"); pipeline.remove("compression-encoder"); }
                pipeline.addBefore("minecraft-decoder","compression-decoder",new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
                        calls.add("inflate"); ctx.fireChannelRead(msg);
                    }
                });
                pipeline.addBefore("minecraft-encoder","compression-encoder",new ChannelOutboundHandlerAdapter() {
                    @Override public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
                        calls.add("deflate"); ctx.write(msg,promise);
                    }
                });
                assertDoesNotThrow(() -> { pipeline.fireUserEventTriggered(Event.COMPRESSION_ENABLED); channel.checkException(); });
                assertEquals(attempt + 1,eventCount.get());
                final var names = pipeline.names();
                assertTrue(names.indexOf("compression-decoder") < names.indexOf("via-decoder"));
                assertTrue(names.indexOf("via-decoder") < names.indexOf(BackendPacketDecoder.NAME));
                assertTrue(names.indexOf("compression-encoder") < names.indexOf("via-encoder"));
                assertTrue(names.indexOf("via-encoder") < names.indexOf(BackendPacketEncoder.NAME));
                calls.clear();
                final var inbound = Unpooled.buffer().writeByte(1);
                assertTrue(channel.writeInbound(inbound));
                ((ByteBuf)channel.readInbound()).release();
                assertEquals(0,inbound.refCnt());
                assertEquals(List.of("inflate","via-in","packet-SERVER","minecraft-decode"),calls);
                calls.clear();
                final var outbound = Unpooled.buffer().writeByte(2);
                assertTrue(channel.writeOutbound(outbound));
                ((ByteBuf)channel.readOutbound()).release();
                assertEquals(0,outbound.refCnt());
                assertEquals(List.of("minecraft-encode","packet-CLIENT","via-out","deflate"),calls);
            }
            final var before = List.copyOf(pipeline.names());
            pipeline.fireUserEventTriggered(Event.OTHER); channel.checkException();
            assertEquals(4,eventCount.get()); assertEquals(before,pipeline.names());
        } finally { channel.finishAndReleaseAll(); }
        verify(session).disconnect();
    }

    /** Mirrors ViaVersion VelocityDecodeHandler's compression relocation and forwarding order. */
    @ChannelHandler.Sharable
    private static final class RelocatingViaDecoder extends ChannelInboundHandlerAdapter {
        private final List<String> calls;
        private final AtomicInteger visits;
        private RelocatingViaDecoder(final List<String> calls, final AtomicInteger visits) { this.calls=calls; this.visits=visits; }
        @Override public void userEventTriggered(final ChannelHandlerContext ctx, final Object event) throws Exception {
            if (event == Event.COMPRESSION_ENABLED) {
                if (visits.incrementAndGet() > 20) throw new IllegalStateException("Compression event entered a relocation cycle");
                final var pipeline=ctx.pipeline();
                final var encoder=pipeline.remove("via-encoder"); pipeline.addBefore("minecraft-encoder","via-encoder",encoder);
                final var decoder=pipeline.remove("via-decoder"); pipeline.addBefore("minecraft-decoder","via-decoder",decoder);
            }
            super.userEventTriggered(ctx,event);
        }
        @Override public void channelRead(final ChannelHandlerContext ctx, final Object msg) { calls.add("via-in"); ctx.fireChannelRead(msg); }
    }
    @ChannelHandler.Sharable
    private static final class RelocatingViaEncoder extends ChannelOutboundHandlerAdapter {
        private final List<String> calls;
        private RelocatingViaEncoder(final List<String> calls) { this.calls=calls; }
        @Override public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) { calls.add("via-out"); ctx.write(msg,promise); }
    }
}
