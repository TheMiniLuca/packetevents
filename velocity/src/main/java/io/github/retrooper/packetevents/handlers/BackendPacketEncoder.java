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

import com.github.retrooper.packetevents.protocol.PacketSide;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandler;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable // Allows relocation on its owning backend channel.
public final class BackendPacketEncoder extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "packetevents-backend-encoder";
    private final BackendPacketSession session;

    public BackendPacketEncoder(final BackendPacketSession session) {
        this.session = session;
    }

    @Override
    public void write(final ChannelHandlerContext context, final Object message,
                      final ChannelPromise promise) throws Exception {
        if (!(message instanceof ByteBuf)) {
            context.write(message, promise);
            return;
        }
        boolean forwarded = false;
        final ByteBuf copy = ((ByteBuf) message).copy();
        try {
            if (this.session.process(copy, PacketSide.CLIENT)) {
                forwarded = true;
                context.write(copy, promise);
            } else {
                promise.trySuccess();
            }
        } catch (final Exception failure) {
            promise.tryFailure(failure);
            context.fireExceptionCaught(failure);
            context.close();
        } finally {
            ReferenceCountUtil.release(message);
            if (!forwarded) {
                copy.release();
            }
        }
    }
}
