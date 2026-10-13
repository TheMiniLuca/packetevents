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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandler;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable // Allows relocation when compression codecs are inserted on this channel.
public final class BackendPacketDecoder extends ChannelInboundHandlerAdapter {
    public static final String NAME = "packetevents-backend-decoder";
    private final BackendPacketSession session;

    public BackendPacketDecoder(final BackendPacketSession session) {
        this.session = session;
    }

    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message) throws Exception {
        if (!(message instanceof ByteBuf)) {
            context.fireChannelRead(message);
            return;
        }
        boolean forwarded = false;
        try {
            final ByteBuf copy = ((ByteBuf) message).copy();
            try {
                if (this.session.process(copy, PacketSide.SERVER)) {
                    forwarded = true;
                    context.fireChannelRead(copy);
                }
            } finally {
                if (!forwarded) {
                    copy.release();
                }
            }
        } finally {
            ReferenceCountUtil.release(message);
        }
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext context, final Object event) throws Exception {
        // Velocity inserts compression codecs dynamically. Keep interception on their decoded side.
        if (event instanceof Enum && ((Enum<?>) event).name().equals("COMPRESSION_ENABLED")) {
            final BackendPacketEncoder encoder = (BackendPacketEncoder)
                    context.pipeline().remove(BackendPacketEncoder.NAME);
            context.pipeline().addBefore("minecraft-encoder", BackendPacketEncoder.NAME, encoder);
            context.pipeline().remove(this);
            context.pipeline().addBefore("minecraft-decoder", NAME, this);
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext context) throws Exception {
        this.session.disconnect();
        super.channelInactive(context);
    }
}
