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

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

public final class BackendPacketHandlers {
    private BackendPacketHandlers() {
    }

    public static void inject(final Channel channel) {
        final BackendPacketSession session = new BackendPacketSession(channel);
        channel.pipeline().addBefore(anchor(channel.pipeline(), "via-decoder", "minecraft-decoder"), BackendPacketDecoder.NAME,
                new BackendPacketDecoder(session));
        channel.pipeline().addBefore(anchor(channel.pipeline(), "via-encoder", "minecraft-encoder"), BackendPacketEncoder.NAME,
                new BackendPacketEncoder(session));
    }

    static void relocate(final ChannelPipeline pipeline, final BackendPacketDecoder decoder) {
        // The backend User tracks the server wire version from its handshake. Read inbound bytes
        // before ViaVersion translates them and outbound bytes after translation, still inflated.
        final BackendPacketEncoder encoder = (BackendPacketEncoder) pipeline.remove(BackendPacketEncoder.NAME);
        pipeline.addBefore(anchor(pipeline, "via-encoder", "minecraft-encoder"), BackendPacketEncoder.NAME, encoder);
        pipeline.remove(decoder);
        pipeline.addBefore(anchor(pipeline, "via-decoder", "minecraft-decoder"), BackendPacketDecoder.NAME, decoder);
    }

    private static String anchor(final ChannelPipeline pipeline, final String via, final String minecraft) {
        return pipeline.context(via) != null ? via : minecraft;
    }
}
