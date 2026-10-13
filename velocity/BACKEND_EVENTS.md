# Velocity backend packet events

This fork adds proxy-to-backend interception without changing ordinary frontend packet listeners.
The initial consumer is Identity's backend login profile rewriting. The plugin ID remains
`packetevents`; the fork version is `2.14.1-SNAPSHOT`.

## API

Register a normal PacketEvents listener and opt in through `onPacketEventExternal`:

```java
@Override
public void onPacketEventExternal(final PacketEvent event) {
    if (!(event instanceof VelocityBackendPacketEvent)) {
        return;
    }
    final VelocityBackendPacketEvent backend = (VelocityBackendPacketEvent) event;
    final ProtocolPacketEvent packet = backend.getPacket();
    // backend.getServerConnection() identifies the exact connection, destination, and real Player.
    // PacketReceiveEvent = serverbound; PacketSendEvent = clientbound, even on the backend channel.
    // Read the appropriate wrapper, modify it, then packet.markForReEncode(true).
}
```

`VelocityBackendDisconnectEvent` is delivered through the same callback. Connection cleanup never
calls frontend user registration/disconnection hooks. Existing packet listeners and PacketEvents'
global UUID/channel maps therefore do not see backend packets. The API capability marker is
`VelocityBackendPacketEvent.API_VERSION = 1`.

Each backend channel owns a separate `User` and separate encoder/decoder protocol states. Login,
configuration, play transitions, and compression-handler relocation are handled locally. The real
Velocity `Player` profile is not changed. Backend pings without a `ServerConnection` association
pass through normally.

Callbacks run on the channel event loop and are synchronous. Complete database/network lookups
before the channel is opened, for example by awaiting `ServerPreConnectEvent`. Identity snapshots
the selected profile when the backend handshake starts. Consumers doing critical rewriting must
explicitly cancel/close a connection on failure; the existing EventManager logs listener failures.

The connection-local User exposes protocol state, not a globally registered player session.
Do not use its frontend-oriented send/receive helpers. This initial backend API does not populate
world/entity/registry state for wrappers that depend on it; its tested scope is login profile
rewriting, protocol transitions, and Identity's backend chat handling.

## Build

```powershell
.\gradlew.bat :velocity:test :velocity:shadowJar --configure-on-demand
```

Requires JDK 25, matching the upstream Velocity module. The shaded plugin is written to
`build/libs/packetevents-velocity-2.14.1-SNAPSHOT.jar`.

EmbeddedChannel tests cover backend/frontend isolation, per-channel state, packet rewriting,
compression relocation, and cancellation/reference-count cleanup. Identity's separate integration
runner exercises actual Paper and Velocity deployments with and without backend plugins.
