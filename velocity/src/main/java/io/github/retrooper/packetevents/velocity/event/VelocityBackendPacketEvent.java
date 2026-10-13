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

package io.github.retrooper.packetevents.velocity.event;

import com.github.retrooper.packetevents.event.CancellableEvent;
import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.velocitypowered.api.proxy.ServerConnection;

/**
 * A packet on a proxy-to-backend connection. Delivered exclusively through
 * {@link PacketListenerCommon#onPacketEventExternal(PacketEvent)} so existing frontend listeners
 * and PacketEvents' global player bookkeeping never process backend packets.
 *
 * <p>The wrapped receive event represents a serverbound packet, and the wrapped send event a
 * clientbound packet, regardless of the proxy's network direction. The User is connection-local;
 * its send/receive helpers must not be used. Mutate a wrapper and call markForReEncode(true).
 */
public final class VelocityBackendPacketEvent extends PacketEvent implements CancellableEvent {
    public static final int API_VERSION = 1;

    private final ServerConnection serverConnection;
    private final ProtocolPacketEvent packet;

    public VelocityBackendPacketEvent(final ServerConnection serverConnection,
                                      final ProtocolPacketEvent packet) {
        this.serverConnection = serverConnection;
        this.packet = packet;
    }

    public ServerConnection getServerConnection() {
        return this.serverConnection;
    }

    public ProtocolPacketEvent getPacket() {
        return this.packet;
    }

    @Override
    public boolean isCancelled() {
        return this.packet.isCancelled();
    }

    @Override
    public void setCancelled(final boolean cancelled) {
        this.packet.setCancelled(cancelled);
    }

    @Override
    public void call(final PacketListenerCommon listener) {
        this.callPacketEventExternal(listener);
    }
}
