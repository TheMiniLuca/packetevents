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
import static org.mockito.Mockito.*;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import io.github.retrooper.packetevents.velocity.event.VelocityBackendPacketEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackendPacketViaBoundaryTest {
    private static final ClientVersion CLIENT = ClientVersion.V_1_21_11;
    private static final ClientVersion BACKEND = ClientVersion.V_26_3;
    private final List<String> observed = new ArrayList<>();
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private enum Event { COMPRESSION_ENABLED }

    @BeforeEach
    void setup() {
        final var api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        final var events = new EventManager();
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        when(api.getEventManager()).thenReturn(events);
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        when(api.getInjector().isProxy()).thenReturn(true);
        PacketEvents.setAPI(api);
        events.registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketEventExternal(final PacketEvent event) {
                if (!(event instanceof VelocityBackendPacketEvent backend)) return;
                final var packet = backend.getPacket();
                if (packet.getConnectionState() != ConnectionState.PLAY) return;
                assertEquals(BACKEND.toServerVersion(), packet.getServerVersion());
                if (packet.getPacketType() == PacketType.Play.Client.CHAT_COMMAND) {
                    final var command = new WrapperPlayClientChatCommand((PacketReceiveEvent) packet);
                    assertEquals(1, command.getSignedArguments().size());
                    observed.add(command.getCommand());
                } else if (packet.getPacketType() == PacketType.Play.Client.CHAT_MESSAGE) {
                    observed.add(new WrapperPlayClientChatMessage((PacketReceiveEvent) packet).getMessage());
                } else if (packet.getPacketType() == PacketType.Play.Server.KEEP_ALIVE) {
                    assertEquals(42, new WrapperPlayServerKeepAlive((PacketSendEvent) packet).getId());
                    observed.add("keepalive");
                } else {
                    fail("백엔드 원본 버전과 다른 종류로 해석됨: " + packet.getPacketType());
                }
            }
        });
    }

    @AfterEach
    void cleanup() {
        for (final var channel : channels) channel.finishAndReleaseAll();
        PacketEvents.setAPI(null);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 2464})
    @DisplayName("압축 전환을 반복해도 1.21.11 일반 채팅을 26.3 서명 명령어로 오인하지 않는다")
    void signedChatKeepsNativeBackendFormat(final int signaturePrefix) {
        final var channel = channel(PacketType.Play.Client.CHAT_MESSAGE);
        for (int attempt = 0; attempt < 3; attempt++) {
            compress(channel);
            final ByteBuf input = signedPacket(PacketType.Play.Client.CHAT_MESSAGE, signaturePrefix);
            assertEquals(283, input.readableBytes());
            assertTrue(channel.writeOutbound(input));
            final ByteBuf output = channel.readOutbound();
            assertEquals(PacketType.Play.Client.CHAT_MESSAGE.getId(BACKEND), output.readUnsignedByte());
            output.release();
            assertEquals(0, input.refCnt());
        }
        assertEquals(List.of("abc", "abc", "abc"), observed);
    }

    @Test
    @DisplayName("압축 이후 실제 서명 명령어의 인자와 서명도 정상적으로 해석한다")
    void signedCommandKeepsNativeBackendFormat() {
        final var channel = channel(PacketType.Play.Client.CHAT_COMMAND);
        compress(channel);
        final ByteBuf input = signedPacket(PacketType.Play.Client.CHAT_COMMAND, 7);
        assertTrue(channel.writeOutbound(input));
        final ByteBuf output = channel.readOutbound();
        assertEquals(PacketType.Play.Client.CHAT_COMMAND.getId(BACKEND), output.readUnsignedByte());
        output.release();
        assertEquals(0, input.refCnt());
        assertEquals(List.of("say abc"), observed);
    }

    @Test
    @DisplayName("압축 이후 서버에서 받은 패킷도 ViaVersion이 클라이언트 버전으로 바꾸기 전에 읽는다")
    void clientboundPacketIsObservedBeforeTranslation() {
        assertNotEquals(PacketType.Play.Server.KEEP_ALIVE.getId(CLIENT),
                PacketType.Play.Server.KEEP_ALIVE.getId(BACKEND));
        final var channel = channel(PacketType.Play.Client.CHAT_MESSAGE);
        compress(channel);
        final ByteBuf input = Unpooled.buffer()
                .writeByte(PacketType.Play.Server.KEEP_ALIVE.getId(BACKEND)).writeLong(42);
        assertTrue(channel.writeInbound(input));
        final ByteBuf output = channel.readInbound();
        assertEquals(PacketType.Play.Server.KEEP_ALIVE.getId(CLIENT), output.readUnsignedByte());
        output.release();
        assertEquals(0, input.refCnt());
        assertEquals(List.of("keepalive"), observed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("ViaVersion이 먼저 설치돼도 나중에 설치돼도 초기 가로채기는 서버 원본 쪽에 둔다")
    void initialInjectionIsOnServerSide(final boolean viaFirst) {
        final var channel = new EmbeddedChannel();
        channels.add(channel);
        channel.pipeline().addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
        if (viaFirst) via(channel, PacketType.Play.Client.CHAT_MESSAGE);
        BackendPacketHandlers.inject(channel);
        if (!viaFirst) via(channel, PacketType.Play.Client.CHAT_MESSAGE);
        final var names = channel.pipeline().names();
        assertTrue(names.indexOf(BackendPacketDecoder.NAME) < names.indexOf("via-decoder"));
        assertTrue(names.indexOf(BackendPacketEncoder.NAME) < names.indexOf("via-encoder"));
    }

    private EmbeddedChannel channel(final PacketType.Play.Client outbound) {
        final var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getUsername()).thenReturn("Original");
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_11);
        final var connection = mock(ServerConnection.class);
        when(connection.getPlayer()).thenReturn(player);
        when(connection.getServerInfo()).thenReturn(new ServerInfo("backend", new InetSocketAddress("127.0.0.1", 25566)));
        final var channel = new EmbeddedChannel();
        channels.add(channel);
        channel.pipeline().addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("handler", new BackendPacketSessionTest.FakeConnectionHandler(connection));
        BackendPacketHandlers.inject(channel);
        final ByteBuf handshake = Unpooled.buffer().writeByte(0);
        varInt(handshake, BACKEND.getProtocolVersion());
        string(handshake, "localhost");
        handshake.writeShort(25565).writeByte(2);
        assertTrue(channel.writeOutbound(handshake));
        ((ByteBuf) channel.readOutbound()).release();
        // Enter PLAY through the actual backend session's normal configuration acknowledgements.
        channel.writeInbound(Unpooled.buffer().writeByte(PacketType.Login.Server.LOGIN_SUCCESS.getId(BACKEND)));
        ((ByteBuf) channel.readInbound()).release();
        channel.writeOutbound(Unpooled.buffer().writeByte(PacketType.Login.Client.LOGIN_SUCCESS_ACK.getId(BACKEND)));
        ((ByteBuf) channel.readOutbound()).release();
        channel.writeInbound(Unpooled.buffer().writeByte(PacketType.Configuration.Server.CONFIGURATION_END.getId(BACKEND)));
        ((ByteBuf) channel.readInbound()).release();
        channel.writeOutbound(Unpooled.buffer().writeByte(PacketType.Configuration.Client.CONFIGURATION_END_ACK.getId(BACKEND)));
        ((ByteBuf) channel.readOutbound()).release();
        via(channel, outbound);
        return channel;
    }

    private static void compress(final EmbeddedChannel channel) {
        if (channel.pipeline().get("compression-decoder") != null) {
            channel.pipeline().remove("compression-decoder");
            channel.pipeline().remove("compression-encoder");
        }
        channel.pipeline().addBefore("minecraft-decoder", "compression-decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addBefore("minecraft-encoder", "compression-encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().fireUserEventTriggered(Event.COMPRESSION_ENABLED);
        channel.checkException();
    }

    private static void via(final EmbeddedChannel channel, final PacketType.Play.Client outbound) {
        channel.pipeline().addBefore("minecraft-encoder", "via-encoder", new IdTranslator(outbound));
        channel.pipeline().addBefore("minecraft-decoder", "via-decoder", new RelocatingTranslator());
    }

    private static ByteBuf signedPacket(final PacketType.Play.Client type, final int prefix) {
        final var bytes = Unpooled.buffer().writeByte(type.getId(CLIENT));
        string(bytes, type == PacketType.Play.Client.CHAT_MESSAGE ? "abc" : "say abc");
        bytes.writeLong(1_794_148_000_000L).writeLong(123);
        if (type == PacketType.Play.Client.CHAT_MESSAGE) bytes.writeBoolean(true);
        else { varInt(bytes, 1); string(bytes, "message"); }
        final byte[] signature = new byte[256];
        if (prefix == 7) signature[0] = 7;
        else { signature[0] = (byte) 0xa0; signature[1] = 0x13; }
        bytes.writeBytes(signature);
        varInt(bytes, 0);
        return bytes.writeZero(3).writeByte(0);
    }

    private static void string(final ByteBuf bytes, final String value) {
        final byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        varInt(bytes, encoded.length);
        bytes.writeBytes(encoded);
    }

    private static void varInt(final ByteBuf bytes, final int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            bytes.writeByte((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        bytes.writeByte(remaining);
    }

    @ChannelHandler.Sharable
    private static final class IdTranslator extends ChannelOutboundHandlerAdapter {
        private final PacketType.Play.Client type;
        private IdTranslator(final PacketType.Play.Client type) { this.type = type; }
        @Override
        public void write(final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
            final var bytes = (ByteBuf) message;
            assertEquals(type.getId(CLIENT), bytes.getUnsignedByte(bytes.readerIndex()));
            bytes.setByte(bytes.readerIndex(), type.getId(BACKEND));
            ctx.write(message, promise);
        }
    }

    @ChannelHandler.Sharable
    private static final class RelocatingTranslator extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object message) {
            final var bytes = (ByteBuf) message;
            assertEquals(PacketType.Play.Server.KEEP_ALIVE.getId(BACKEND), bytes.getUnsignedByte(bytes.readerIndex()));
            bytes.setByte(bytes.readerIndex(), PacketType.Play.Server.KEEP_ALIVE.getId(CLIENT));
            ctx.fireChannelRead(message);
        }
        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object event) throws Exception {
            if (event == Event.COMPRESSION_ENABLED) {
                final var pipeline = ctx.pipeline();
                final var encoder = pipeline.remove("via-encoder");
                pipeline.addBefore("minecraft-encoder", "via-encoder", encoder);
                pipeline.remove(this);
                pipeline.addBefore("minecraft-decoder", "via-decoder", this);
            }
            super.userEventTriggered(ctx, event);
        }
    }
}
