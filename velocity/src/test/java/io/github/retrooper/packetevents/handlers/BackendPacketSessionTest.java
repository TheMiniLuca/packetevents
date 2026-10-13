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
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientPluginResponse;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import io.github.retrooper.packetevents.velocity.event.VelocityBackendPacketEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendPacketSessionTest {
    private static final ClientVersion VERSION = ClientVersion.V_1_21_11;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<ProtocolPacketEvent> packets = new ArrayList<>();
    private PacketEventsAPI<?> api;
    private EventManager events;
    private ProtocolManager protocolManager;
    private int frontendCalls;

    @BeforeEach
    void setup() {
        this.api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        this.events = new EventManager();
        this.protocolManager = mock(ProtocolManager.class);
        when(this.api.getSettings()).thenReturn(new PacketEventsSettings());
        when(this.api.getEventManager()).thenReturn(this.events);
        when(this.api.getNettyManager()).thenReturn(new NettyManagerImpl());
        when(this.api.getInjector().isProxy()).thenReturn(true);
        when(this.api.getProtocolManager()).thenReturn(this.protocolManager);
        PacketEvents.setAPI(this.api);
        this.events.registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(final PacketReceiveEvent event) {
                frontendCalls++;
            }

            @Override
            public void onPacketEventExternal(final PacketEvent event) {
                if (event instanceof VelocityBackendPacketEvent) {
                    packets.add(((VelocityBackendPacketEvent) event).getPacket());
                }
            }
        });
    }

    @AfterEach
    void cleanup() {
        for (final EmbeddedChannel channel : this.channels) {
            channel.finishAndReleaseAll();
        }
        verifyNoInteractions(this.protocolManager);
        PacketEvents.setAPI(null);
    }

    @Test
    @DisplayName("백엔드 패킷은 프론트엔드 리스너와 사용자 맵에 전달되지 않는다")
    void isolatesBackendEventsAndUsers() {
        final EmbeddedChannel first = channel();
        final EmbeddedChannel second = channel();
        handshake(first);
        handshake(second);
        assertEquals(0, this.frontendCalls);
        assertEquals(2, this.packets.size());
        assertNotSame(this.packets.get(0).getUser(), this.packets.get(1).getUser());
        first.close();
        assertTrue(second.isActive());
    }

    @Test
    @DisplayName("백엔드 서버로 보내는 로그인 응답을 수정하여 다시 인코딩한다")
    void rewritesServerboundPacket() {
        final EmbeddedChannel channel = channel();
        handshake(channel);
        this.events.registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketEventExternal(final PacketEvent event) {
                if (!(event instanceof VelocityBackendPacketEvent)) { return; }
                final ProtocolPacketEvent packet = ((VelocityBackendPacketEvent) event).getPacket();
                if (packet.getPacketType() == PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE) {
                    new WrapperLoginClientPluginResponse((PacketReceiveEvent) packet).setData(new byte[] {9, 8, 7});
                    packet.markForReEncode(true);
                }
            }
        });
        final ByteBuf response = packet(PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE);
        varInt(response, 12);
        response.writeBoolean(true).writeBytes(new byte[] {1, 2});
        channel.writeOutbound(response);
        final ByteBuf result = channel.readOutbound();
        assertEquals(PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE.getId(VERSION), readVarInt(result));
        assertEquals(12, readVarInt(result));
        assertTrue(result.readBoolean());
        final byte[] data = new byte[result.readableBytes()];
        result.readBytes(data).release();
        assertArrayEquals(new byte[] {9, 8, 7}, data);
        assertEquals(0, response.refCnt());
    }

    @Test
    @DisplayName("로그인과 설정 전환에서 송수신 상태를 독립적으로 추적한다")
    void tracksSplitProtocolStates() {
        final EmbeddedChannel channel = channel();
        handshake(channel);
        channel.writeInbound(packet(PacketType.Login.Server.LOGIN_SUCCESS));
        ((ByteBuf) channel.readInbound()).release();
        final var user = this.packets.get(0).getUser();
        assertEquals(ConnectionState.CONFIGURATION, user.getEncoderState());
        assertEquals(ConnectionState.LOGIN, user.getDecoderState());
        send(channel, PacketType.Login.Client.LOGIN_SUCCESS_ACK);
        assertEquals(ConnectionState.CONFIGURATION, user.getDecoderState());
        channel.writeInbound(packet(PacketType.Configuration.Server.CONFIGURATION_END));
        ((ByteBuf) channel.readInbound()).release();
        assertEquals(ConnectionState.PLAY, user.getEncoderState());
        assertEquals(ConnectionState.CONFIGURATION, user.getDecoderState());
        send(channel, PacketType.Configuration.Client.CONFIGURATION_END_ACK);
        assertEquals(ConnectionState.PLAY, user.getDecoderState());
    }

    @Test
    @DisplayName("압축 코덱이 삽입되어도 패킷 가로채기는 압축 해제된 위치를 유지한다")
    void relocatesAfterCompression() {
        final EmbeddedChannel channel = channel();
        channel.pipeline().addBefore("minecraft-decoder", "compression-decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addBefore("minecraft-encoder", "compression-encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().fireUserEventTriggered(TestEvent.COMPRESSION_ENABLED);
        final List<String> names = channel.pipeline().names();
        assertTrue(names.indexOf("compression-decoder") < names.indexOf(BackendPacketDecoder.NAME));
        assertTrue(names.indexOf("compression-encoder") < names.indexOf(BackendPacketEncoder.NAME));
        handshake(channel);
    }

    @Test
    @DisplayName("취소된 패킷은 메모리를 해제하고 서버로 전송하지 않는다")
    void releasesCancelledPacket() {
        final EmbeddedChannel channel = channel();
        handshake(channel);
        this.events.registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketEventExternal(final PacketEvent event) {
                if (event instanceof VelocityBackendPacketEvent) {
                    ((VelocityBackendPacketEvent) event).setCancelled(true);
                }
            }
        });
        final ByteBuf response = packet(PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE);
        response.writeByte(0).writeBoolean(false);
        assertFalse(channel.writeOutbound(response));
        assertEquals(0, response.refCnt());
        assertNull(channel.readOutbound());
    }

    private EmbeddedChannel channel() {
        final Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getUsername()).thenReturn("Original");
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_11);
        final ServerConnection connection = mock(ServerConnection.class);
        when(connection.getPlayer()).thenReturn(player);
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("minecraft-decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("minecraft-encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("handler", new FakeConnectionHandler(connection));
        BackendPacketHandlers.inject(channel);
        this.channels.add(channel);
        return channel;
    }

    private static void handshake(final EmbeddedChannel channel) {
        final ByteBuf bytes = packet(PacketType.Handshaking.Client.HANDSHAKE);
        varInt(bytes, VERSION.getProtocolVersion());
        final byte[] host = "localhost".getBytes(StandardCharsets.UTF_8);
        varInt(bytes, host.length);
        bytes.writeBytes(host).writeShort(25565).writeByte(2);
        channel.writeOutbound(bytes);
        ((ByteBuf) channel.readOutbound()).release();
    }

    private static void send(final EmbeddedChannel channel, final PacketTypeCommon type) {
        channel.writeOutbound(packet(type));
        ((ByteBuf) channel.readOutbound()).release();
    }

    private static ByteBuf packet(final PacketTypeCommon type) {
        final ByteBuf bytes = Unpooled.buffer();
        varInt(bytes, type.getId(VERSION));
        return bytes;
    }

    private static void varInt(final ByteBuf bytes, final int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            bytes.writeByte((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        bytes.writeByte(remaining);
    }

    private static int readVarInt(final ByteBuf bytes) {
        int result = 0;
        int shift = 0;
        int current;
        do {
            current = bytes.readUnsignedByte();
            result |= (current & 0x7f) << shift;
            shift += 7;
        } while ((current & 0x80) != 0);
        return result;
    }

    public static final class FakeConnectionHandler extends ChannelInboundHandlerAdapter {
        private final ServerConnection connection;
        public FakeConnectionHandler(final ServerConnection connection) { this.connection = connection; }
        public ServerConnection getAssociation() { return this.connection; }
    }

    private enum TestEvent { COMPRESSION_ENABLED }
}
