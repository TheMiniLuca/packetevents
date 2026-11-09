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

When ViaVersion is present on Velocity, backend interception stays on the **server wire side** of
`via-decoder` / `via-encoder`, after decompression and before compression. Incoming packets are
observed before translation to the player's version; outgoing packets are observed after translation
to the backend version. This boundary applies both at initial injection and after every
`COMPRESSION_ENABLED` relocation. Without ViaVersion, the Minecraft codecs remain the anchors.
Compression events are forwarded before moving the PacketEvents contexts so ViaVersion can finish
its synchronous relocation without repeating or cycling the event.

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

Requires JDK 25 for the build and targets Velocity 4.2.0 with its Adventure 5.2.0 API.
Library and serializer bytecode targets Java 21, Adventure 5's minimum. This fork no longer
targets Adventure 4 hosts. Building the bundled serializers against Adventure 5 is required:
`ClickEvent.Action` constants have typed field descriptors that differ from Adventure 4.
Regression tests cover legacy 1.21.4 click payloads and modern integer page payloads.
Backend tests use Minecraft 26.3 (protocol 777). The shaded plugin is written to
`build/libs/packetevents-velocity-2.14.1-SNAPSHOT.jar`.

EmbeddedChannel tests cover backend/frontend isolation, per-channel state, packet rewriting,
compression relocation, and cancellation/reference-count cleanup. Identity's separate integration
runner exercises actual Paper and Velocity deployments with and without backend plugins.
The cross-version boundary tests pass 1.21.11 signed chat and commands through an explicit packet-ID
translation fixture and decode the result with the real 26.3 PacketEvents wrappers. They include the
283-byte chat samples that previously produced signature overreads and `2464 > 64` argument lengths,
plus clientbound KeepAlive and repeated compression events. The translation fixture is not a complete
ViaVersion runtime or a claim of end-to-end live compatibility.
