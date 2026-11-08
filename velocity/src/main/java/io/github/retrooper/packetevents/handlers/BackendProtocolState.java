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

import com.github.retrooper.packetevents.protocol.ConnectionState;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Reads the decoder that will consume this exact backend frame, before it decodes the frame. */
final class BackendProtocolState {
    private static final ClassValue<Optional<Field>> STATES = new ClassValue<Optional<Field>>() {
        @Override
        protected Optional<Field> computeValue(final Class<?> type) {
            return stateField(type);
        }
    };

    private BackendProtocolState() { }

    static Optional<ConnectionState> clientbound(final Channel channel) throws IllegalAccessException {
        final Object decoder = channel.pipeline().get("minecraft-decoder");
        if (decoder == null) return Optional.empty();
        final Optional<Field> field = STATES.get(decoder.getClass());
        if (!field.isPresent()) return Optional.empty(); // Non-Velocity/custom codecs can use packet tracking.
        final Object state = field.get().get(decoder);
        if (!(state instanceof Enum<?>))
            throw new IllegalStateException("Velocity decoder has an unsupported protocol state: " + state);
        final String name = ((Enum<?>) state).name();
        switch (name) {
            case "HANDSHAKE":
            case "HANDSHAKING": return Optional.of(ConnectionState.HANDSHAKING);
            case "STATUS": return Optional.of(ConnectionState.STATUS);
            case "LOGIN": return Optional.of(ConnectionState.LOGIN);
            case "CONFIG":
            case "CONFIGURATION": return Optional.of(ConnectionState.CONFIGURATION);
            case "PLAY": return Optional.of(ConnectionState.PLAY);
            default: throw new IllegalStateException("Unknown Velocity decoder state: " + name);
        }
    }

    private static Optional<Field> stateField(final @Nullable Class<?> type) {
        if (type == null) return Optional.empty();
        try {
            final Field field = type.getDeclaredField("state");
            field.setAccessible(true);
            return Optional.of(field);
        } catch (final NoSuchFieldException inherited) {
            return stateField(type.getSuperclass());
        }
    }
}
