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

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.PacketSide;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.EventCreationUtil;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import io.github.retrooper.packetevents.velocity.event.VelocityBackendDisconnectEvent;
import io.github.retrooper.packetevents.velocity.event.VelocityBackendPacketEvent;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.jetbrains.annotations.Nullable;

/** Owned by one backend channel/event loop. Never registered in ProtocolManager's player maps. */
final class BackendPacketSession {
    private final Channel channel;
    private @Nullable ServerConnection connection;
    private @Nullable User user;

    BackendPacketSession(final Channel channel) {
        this.channel = channel;
    }

    boolean process(final ByteBuf buffer, final PacketSide side) throws ReflectiveOperationException {
        if (!buffer.isReadable() || PacketEvents.getAPI().isTerminated()) {
            return true;
        }
        if (this.connection == null) {
            final Object handler = this.channel.pipeline().get("handler");
            if (handler == null) {
                return true; // Server-list pings also use Velocity's backend initializer.
            }
            final Object association = handler.getClass().getMethod("getAssociation").invoke(handler);
            if (!(association instanceof ServerConnection)) {
                return true;
            }
            this.connection = (ServerConnection) association;
            final Player player = this.connection.getPlayer();
            this.user = new User(this.channel, ConnectionState.HANDSHAKING,
                    ClientVersion.getById(player.getProtocolVersion().getProtocol()),
                    new UserProfile(player.getUniqueId(), player.getUsername()));
        }

        final int start = buffer.readerIndex();
        final ProtocolPacketEvent packet = side == PacketSide.CLIENT
                ? EventCreationUtil.createReceiveEvent(this.channel, this.user,
                        this.connection.getPlayer(), buffer, false)
                : EventCreationUtil.createSendEvent(this.channel, this.user,
                        this.connection.getPlayer(), buffer, false);
        final int payloadStart = buffer.readerIndex();
        PacketEvents.getAPI().getEventManager().callEvent(
                new VelocityBackendPacketEvent(this.connection, packet),
                () -> buffer.readerIndex(payloadStart));
        if (packet.isCancelled()) {
            return false;
        }
        this.advanceState(packet);
        final PacketWrapper<?> wrapper = packet.getLastUsedWrapper();
        if (wrapper != null && packet.needsReEncode()) {
            buffer.clear();
            wrapper.writeVarInt(packet.getPacketId());
            wrapper.write();
            buffer.readerIndex(0);
        } else {
            buffer.readerIndex(start);
        }
        if (packet.hasPostTasks()) {
            for (final Runnable task : packet.getPostTasks()) {
                task.run();
            }
        }
        return true;
    }

    private void advanceState(final ProtocolPacketEvent packet) {
        final User backendUser = packet.getUser();
        if (packet.getPacketType() == PacketType.Handshaking.Client.HANDSHAKE) {
            final WrapperHandshakingClientHandshake handshake =
                    new WrapperHandshakingClientHandshake((PacketReceiveEvent) packet);
            backendUser.setClientVersion(handshake.getClientVersion());
            backendUser.setConnectionState(handshake.getNextConnectionState());
        } else if (packet.getPacketType() == PacketType.Login.Server.LOGIN_SUCCESS) {
            if (backendUser.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_20_2)) {
                backendUser.setEncoderState(ConnectionState.CONFIGURATION);
            } else {
                backendUser.setConnectionState(ConnectionState.PLAY);
            }
        } else if (packet.getPacketType() == PacketType.Login.Client.LOGIN_SUCCESS_ACK
                || packet.getPacketType() == PacketType.Play.Client.CONFIGURATION_ACK) {
            backendUser.setDecoderState(ConnectionState.CONFIGURATION);
        } else if (packet.getPacketType() == PacketType.Play.Server.CONFIGURATION_START) {
            backendUser.setEncoderState(ConnectionState.CONFIGURATION);
        } else if (packet.getPacketType() == PacketType.Configuration.Server.CONFIGURATION_END) {
            backendUser.setEncoderState(ConnectionState.PLAY);
        } else if (packet.getPacketType() == PacketType.Configuration.Client.CONFIGURATION_END_ACK) {
            backendUser.setDecoderState(ConnectionState.PLAY);
        }
    }

    void disconnect() {
        if (this.connection != null) {
            PacketEvents.getAPI().getEventManager().callEvent(
                    new VelocityBackendDisconnectEvent(this.connection));
            this.connection = null;
            this.user = null;
        }
    }
}
