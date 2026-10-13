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

package io.github.retrooper.packetevents.injector;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.handlers.BackendPacketHandlers;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import java.lang.reflect.Method;

public final class VelocityBackendChannelInitializer extends ChannelInitializer<Channel> {
    private final ChannelInitializer<Channel> wrappedInitializer;
    private final Method initChannel;

    public VelocityBackendChannelInitializer(final ChannelInitializer<Channel> wrappedInitializer)
            throws NoSuchMethodException {
        this.wrappedInitializer = wrappedInitializer;
        this.initChannel = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
        this.initChannel.setAccessible(true);
    }

    @Override
    protected void initChannel(final Channel channel) throws Exception {
        this.initChannel.invoke(this.wrappedInitializer, channel);
        if (!PacketEvents.getAPI().isTerminated()) {
            BackendPacketHandlers.inject(channel);
        }
    }

    public ChannelInitializer<Channel> getWrappedInitializer() {
        return this.wrappedInitializer;
    }
}
