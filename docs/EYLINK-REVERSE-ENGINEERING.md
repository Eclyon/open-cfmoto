# Reverse-Engineering Findings — EyLink / Erya

This document describes the EyLink projection protocol reverse-engineered from the official Android application and validated against real motorcycle hardware.

The protocol documented here is separate from the Carbit EasyConn/PXC and Thinkerride stacks already investigated by OpenCfMoto. It uses its own QR pairing format, Wi-Fi Direct bootstrap, TCP control/video channels, framing, and H.264 transport.

Unless explicitly marked otherwise, protocol details below were validated either against the official EyLink Android implementation, through controlled network/protocol tests against the dashboard, or both. Hardware-specific observations are identified as such and should not be assumed to apply to every EyLink implementation.

## 0. Hardware and validation status

**Validated hardware: Kove 350 RR with EyLink dashboard.**

OpenCfMoto has been confirmed displaying Android Auto end-to-end on the physical dashboard without the proprietary EyLink application running.

Validated path:

`Android Auto -> OpenCfMoto AAP receiver -> VideoPipeline -> EyLink transport -> Kove TFT`

On the tested Kove 350 RR, the dashboard negotiates:

- video canvas: **784x352**
- frame rate: **30 fps**
- bitrate: **2000 kbps**
- codec: **H.264 / AVC, Annex-B**

These output parameters are negotiated with the dashboard at session start; OpenCfMoto does not assume that every EyLink dashboard uses 784x352.

The projection has also been observed continuing while the phone is locked and while the OpenCfMoto activity is not in the foreground.

Compatibility with other EyLink-equipped motorcycles or dashboards is plausible at the protocol level but is **not yet validated**. Differences in QR contents, Wi-Fi Direct behavior, ports, handshake fields, or video requirements may exist between EyLink implementations.

## 1. The QR code

The Kove dashboard presents an EyLink pairing QR as an HTTPS URL with query parameters. A sanitized example from the validated Kove 350 RR is:

`https://appstore.eryanet.com?productid=EYLINK&device=EY_<redacted>&ssid=EY_<redacted>&pwd=<redacted>&name=EY_<redacted>&mac=TYWKOVE<redacted>&IP=192.168.13.1&key=<redacted>`

The QR contains the information required to identify the EyLink device and bootstrap the connection. The `pwd` and `key` values above are intentionally redacted.

### 1.1 QR fields

| Field | Meaning / observed use |
|---|---|
| `productid` | Identifies the EyLink product family. Observed value: `EYLINK`. |
| `device` | EyLink device/BLE-facing name. On the tested unit it matches the `EY_...` identifier. |
| `ssid` | Network identifier supplied by the QR. On the tested unit it matches `name`. |
| `pwd` | Pairing/network password. Redacted in this document. |
| `name` | Wi-Fi Direct peer name used during P2P discovery. |
| `mac` | EyLink/Kove identifier. Despite the field name, this is **not** the actual Wi-Fi Direct device MAC address on the tested unit. |
| `IP` | Dashboard IP address supplied by the QR. Observed value: `192.168.13.1`. |
| `key` | EyLink pairing/activation key. Redacted in this document. |

### 1.2 The `mac` field is not the P2P device address

This distinction is important for implementing the Wi-Fi Direct bootstrap.

On the validated Kove 350 RR, the QR contains an opaque identifier in the `mac` field with the form:

`TYWKOVE...`

This value cannot be used as an Android `WifiP2pDevice.deviceAddress`.

The actual Wi-Fi Direct device address is obtained dynamically from Android's P2P peer discovery. The implementation therefore:

1. starts Wi-Fi Direct peer discovery;
2. searches for a peer whose `deviceName` exactly matches the QR `name`;
3. obtains that peer's real `deviceAddress`;
4. uses the discovered address to create the Wi-Fi Direct connection.

This behavior matches the connection path reconstructed from the official EyLink application.

### 1.3 Official application QR routing

Analysis of the official EyLink Android application shows that a QR containing a non-empty `name` follows the P2P route:

`QR -> scanToP2P(...) -> StartP2PBean(...) -> P2PService.searchP2P()`

The relevant QR values are passed approximately as:

```text
name   -> P2P peer name
key    -> EyLink pairing key
mac    -> EyLink opaque identifier
device -> BLE/device name
```

The initial QR-to-P2P path does **not** require a BLE bootstrap before Wi-Fi Direct discovery. This was important to establishing that OpenCfMoto could reproduce the network bootstrap without relying on the proprietary EyLink application.

On the tested Kove 350 RR, the QR parameters remained stable across dashboard power cycles. This is an observed hardware behavior and should not be treated as a guarantee for every EyLink implementation.

## 2. Wi-Fi Direct / P2P bootstrap

EyLink on the validated Kove 350 RR uses **Wi-Fi Direct (Android `WifiP2pManager`)**, rather than requiring the phone to join a conventional SoftAP for the projection session.

The proprietary EyLink application is **not required** to create this connection. OpenCfMoto has successfully reproduced the complete P2P bootstrap independently.

### 2.1 Official EyLink connection flow

Analysis of the official application shows the following sequence:

1. Parse the pairing QR and create the P2P connection parameters.
2. Start Wi-Fi Direct peer discovery with `WifiP2pManager.discoverPeers()`.
3. Receive the available peers through Android's P2P peer list.
4. Find the peer whose `WifiP2pDevice.deviceName` exactly matches the QR `name`.
5. Use the discovered `WifiP2pDevice.deviceAddress` to build the `WifiP2pConfig`.
6. Configure WPS using PBC (`wps.setup = 0`).
7. Call `WifiP2pManager.connect()`.
8. After group formation, request connection information with `requestConnectionInfo()`.
9. Obtain the dashboard/group-owner address from `WifiP2pInfo.groupOwnerAddress`.
10. Use that address for the EyLink TCP connections.

Conceptually:

`QR name -> discoverPeers -> exact deviceName match -> discovered deviceAddress -> WPS PBC -> connect -> requestConnectionInfo -> groupOwnerAddress -> EyLink TCP`

If Android already reports the peer as connected, the official implementation can proceed to requesting the connection information rather than creating a new group from scratch.

### 2.2 Validated network topology

On the tested Kove 350 RR, a successful P2P session produced the following topology:

```text
Kove TFT / P2P Group Owner
        192.168.13.1
              |
        Wi-Fi Direct
              |
        Android phone
        192.168.13.x
```

The tested phone was observed at `192.168.13.20/24`, while the TFT/group owner was `192.168.13.1`.

The phone address should **not** be hardcoded. The relevant local address and group-owner address must be obtained from the active Android P2P/network state.

The QR also advertises `IP=192.168.13.1` on the validated unit, but OpenCfMoto uses the actual P2P connection information when available rather than relying exclusively on that fixed value.

### 2.3 Independent OpenCfMoto bootstrap

A key validation result was establishing the P2P group **without the official EyLink application participating**.

OpenCfMoto was observed successfully progressing through Android's Wi-Fi Direct group negotiation and network provisioning using the QR-derived peer name plus the actual peer address returned by discovery.

After group formation, OpenCfMoto can connect directly to the EyLink TCP services exposed by the TFT.

This establishes the following independent path:

`EyLink QR -> OpenCfMoto QR parser -> Android Wi-Fi Direct discovery -> Kove P2P group -> EyLink TCP`

The proprietary `com.eryanet.eylink` application is therefore **not a runtime dependency** of the OpenCfMoto EyLink backend.

### 2.4 Discovery, retry, and recovery behavior

The current OpenCfMoto implementation deliberately keeps an EyLink-specific Wi-Fi Direct discovery/recovery loop active while the motorcycle is unavailable.

The implementation uses an approximately **5-second discovery interval**. Initial connection still begins with Android's normal peer discovery; the additional loop retries discovery while waiting for the exact QR-derived peer name. During recovery after a previously formed P2P group is lost, the loop restarts immediately and continues in approximately 5-second intervals.

Discovery is grouped into four-attempt cycles for logging/state management. During recovery, completing a four-attempt cycle does **not** permanently stop discovery: the cycle resets and continues while the EyLink connection remains active and the motorcycle has not returned.

A P2P join that has actually been issued also has a bounded join deadline. If it remains unresolved beyond the current connection timeout, OpenCfMoto attempts to cancel/reset that join before returning to discovery. Separately, the normal initial P2P connection path retains its overall failure timeout; a real test where the TFT never appeared as a peer was observed exiting cleanly after approximately **40 seconds** rather than hanging indefinitely.

The approximately 5-second recovery interval is an **OpenCfMoto implementation policy, not a confirmed EyLink protocol requirement**. It is intentionally retained in the first EyLink implementation because it has produced responsive and reliable recovery on the physically tested Kove 350 RR.

A future power-efficiency pass could reasonably evaluate alternatives such as:

- increasing the recovery interval to approximately 30 seconds after the initial fast-recovery window;
- progressively backing off discovery while the motorcycle remains absent;
- entering a deeper parked/sleep state and re-arming discovery on a suitable Android/network event.

Those alternatives are intentionally left as future work. The current implementation favors the recovery behavior that has already been physically validated rather than changing timing immediately before upstream review.

## 3. TCP topology and port roles

Once the Wi-Fi Direct group is established, the phone acts as a TCP client and opens two independent connections to services exposed by the EyLink dashboard.

On the validated Kove 350 RR:

| Port | Role | Connection direction |
|---:|---|---|
| `11111` | Video/mirroring channel | phone -> TFT |
| `11113` | Control/message channel | phone -> TFT |

Both connections are active during a normal projection session.

Conceptually:

```text
Android phone
     |
     |---- TCP 11113 ----> EyLink control / messages
     |
     `---- TCP 11111 ----> EyLink mirror / H.264 video
                              |
                              v
                           Kove TFT
```

The tested TFT accepts both connections at its Wi-Fi Direct group-owner address (`192.168.13.1` on the validated unit).

The two channels use different framing and have different lifecycle responsibilities.

### 3.1 Port 11113 — control channel

The control connection is used for EyLink JSON messages and session liveness.

For phone-to-TFT messages, the observed frame begins with a 12-byte header:

```text
offset 0  : AF BB CC 0F
offset 4  : 00 00 00 00
offset 8  : payload length (uint32 little-endian)
offset 12 : UTF-8 JSON payload
```

During an active session, OpenCfMoto sends the following heartbeat JSON:

`{"EYLINKheart":"Mapheart"}`

The heartbeat is sent every **500 ms**.

Analysis of the official implementation also shows a timeout monitor associated with this channel. A period without expected activity can be logged as a timeout condition, although the observed monitor itself should not be interpreted as proof that it directly closes the entire projection session.

TFT-to-phone traffic on this channel uses a different header format observed with an `EY`/`EH` signature and a big-endian payload length. OpenCfMoto does not currently require the full incoming control-message protocol for the validated Android Auto video path, so that direction remains only partially documented here.

### 3.2 Port 11111 — mirror/video channel

Port `11111` carries the mirroring session and encoded H.264 video.

The phone initiates the video session by:

1. opening the TCP connection;
2. sending `MIRROR_START`;
3. reading the TFT's `OK` response;
4. extracting the video capabilities negotiated by the TFT;
5. configuring the OpenCfMoto video pipeline to those dimensions and frame rate;
6. sending `WIDTH_HEIGHT`;
7. sending framed H.264 access units as `VIDEO_DATA`.

The TFT does **not** request each H.264 frame individually. Once mirroring has started, video is pushed continuously by the phone.

This is different from OpenCfMoto's EasyConn/PXC backend, where the dashboard pulls individual frames from the phone.

### 3.3 Secondary connection validation

During reverse engineering, port `11111` was confirmed to accept a new TCP connection after the original EyLink application had already established the underlying P2P network.

A controlled takeover test was performed by stopping the official EyLink application while leaving the P2P network alive, then connecting to the TFT directly from a custom test client.

The independent client successfully:

- connected to `11113`;
- sent the EyLink heartbeat;
- connected to `11111`;
- sent `MIRROR_START`;
- received the TFT's mirror capabilities;
- and cleanly sent `MIRROR_STOP`.

A later test sent independently encoded H.264 through the reconstructed `VIDEO_DATA` framing and displayed that video physically on the Kove TFT.

These tests established that, once the network exists, ownership of the EyLink TCP session does not depend on the proprietary application.

## 4. Mirror session negotiation

After opening TCP port `11111`, the phone starts a mirror session by sending an EyLink `MIRROR_START` frame.

### 4.1 Common EyLink frame header

Several phone-to-TFT mirror commands use the following 12-byte base structure:

```text
offset 0  : AA BB CC
offset 3  : function
offset 4  : 00 00 00 00
offset 8  : payload length (uint32 little-endian)
offset 12 : payload
```

Observed function identifiers relevant to projection:

| Function | Value | Purpose |
|---|---:|---|
| `VIDEO` | `0x00` | H.264 video data |
| `AUDIO` | `0x01` | Audio-related function observed in the protocol; not used by the OpenCfMoto EyLink video backend |
| `SSID` | `0x02` | Network-related protocol function |
| `PASSWORD` | `0x03` | Network-related protocol function |
| `MIRROR_START` | `0x04` | Start mirror session |
| `MIRROR_STOP` | `0x05` | Stop mirror session |
| `WIDTH_HEIGHT` | `0x06` | Report video dimensions |
| `JPEG` | `0x07` | JPEG/mirroring-related function |
| `JSON/ACK` | `0x0F` | Observed generic JSON/acknowledgement framing |

Not every function above is required by OpenCfMoto's Android Auto path. They are included to document the protocol namespace observed during reverse engineering.

### 4.2 MIRROR_START

The exact `MIRROR_START` frame validated against the Kove TFT is:

```text
AA BB CC 04 00 00 00 00 04 00 00 00 80 04 00 0A
```

Decoded using the common header:

```text
AA BB CC          magic
04                MIRROR_START
00 00 00 00       reserved
04 00 00 00       payload length = 4
80 04 00 0A       payload
```

Interpreting the four-byte payload as two little-endian 16-bit values gives:

```text
80 04 -> 1152
00 0A -> 2560
```

The official implementation and the independently validated test client both use these values when initiating the mirror session.

**Important:** `1152x2560` is not the final TFT video resolution. It is part of the validated `MIRROR_START` request. The actual output dimensions are returned by the TFT in the following `OK` response.

OpenCfMoto currently reproduces this proven request exactly. Whether other EyLink implementations require different source/request dimensions is **not yet validated**, so these values should be treated as a current protocol assumption rather than a universal EyLink constant.

### 4.3 TFT `OK` response

After accepting `MIRROR_START`, the tested TFT replies with a 13-byte capability response.

Observed response:

```text
4F 4B 10 03 60 01 10 03 60 01 1E D0 07
```

The first two bytes are ASCII:

```text
4F 4B -> "OK"
```

The remaining fields decode as little-endian values:

```text
offset 0  : "OK"
offset 2  : portrait width   (uint16 LE)
offset 4  : portrait height  (uint16 LE)
offset 6  : landscape width  (uint16 LE)
offset 8  : landscape height (uint16 LE)
offset 10 : fps              (uint8)
offset 11 : bitrate          (uint16 LE, kbps)
```

For the validated Kove 350 RR:

```text
10 03 -> 784
60 01 -> 352
10 03 -> 784
60 01 -> 352
1E    -> 30 fps
D0 07 -> 2000 kbps
```

Therefore the TFT negotiates:

`784x352 @ 30 fps, 2000 kbps`

Both portrait and landscape fields report the same dimensions on this particular dashboard.

### 4.4 Dynamic output configuration

OpenCfMoto does **not** hardcode `784x352` as the EyLink output resolution.

After parsing the `OK` response, the EyLink backend uses the dimensions and frame rate supplied by the TFT to configure the shared video pipeline.

Conceptually:

```text
MIRROR_START
     |
     v
TFT "OK" capabilities
     |
     +--> width / height
     +--> fps
     `--> bitrate
             |
             v
OpenCfMoto VideoPipeline
             |
             v
H.264 output sized for the negotiated TFT canvas
```

This distinction is important for future compatibility. If another compatible EyLink dashboard reports a different canvas, for example `1280x480`, the backend can attempt to configure the video pipeline for that negotiated size rather than assuming the Kove's `784x352`.

Only the Kove 350 RR negotiation described above has been physically validated so far.

## 5. Video transport

After the mirror session has been accepted and the TFT capabilities have been parsed, the phone begins sending dimension information and H.264 video over TCP port `11111`.

EyLink uses a **push model** for H.264: the phone sends encoded access units continuously. The TFT does not request each frame individually.

### 5.1 WIDTH_HEIGHT

The phone reports the active video dimensions using function `0x06`.

Frame layout:

```text
offset 0  : AA BB CC
offset 3  : 06                       WIDTH_HEIGHT
offset 4  : 00 00 00 00
offset 8  : 04 00 00 00             payload length = 4
offset 12 : width  (uint16 LE)
offset 14 : height (uint16 LE)
```

For a negotiated `784x352` canvas, the packet therefore follows the form:

```text
AA BB CC 06 00 00 00 00 04 00 00 00 <width LE16> <height LE16>
```

OpenCfMoto sends `WIDTH_HEIGHT` when the video session starts and periodically while streaming.

The validated implementation repeats it approximately every **2 seconds**.

Analysis of the official EyLink implementation also showed dimension reporting associated with the first accepted H.264 data and periodic refreshes. The exact requirement for this periodic repetition on every EyLink implementation is not yet known; reproducing it has been validated to work on the Kove 350 RR.

### 5.2 VIDEO_DATA framing

H.264 access units use function `0x00`, but the bytes normally reserved in the common header carry additional video metadata.

The reconstructed frame is:

```text
offset 0  : AA BB CC
offset 3  : 00                       VIDEO
offset 4  : frame index   (uint16 LE)
offset 6  : checksum16    (uint16 LE)
offset 8  : payload length (uint32 LE)
offset 12 : timestamp     (uint32 LE)
offset 16 : H.264 Annex-B access unit
```

For video packets:

`payload length = 4 + H264_access_unit_length`

The checksum is calculated over the **H.264 bytes only**. The four-byte timestamp is not included in the checksum.

### 5.3 Frame index

The frame index is an unsigned 16-bit counter.

It increments once for each transmitted video packet and wraps modulo `65536`:

`0 -> 1 -> 2 -> ... -> 65535 -> 0`

OpenCfMoto reproduces this behavior directly in the EyLink transport.

### 5.4 Timestamp

Each video packet contains a 32-bit little-endian timestamp immediately before the H.264 bytes.

The timestamp represents elapsed time relative to the start of the mirror session.

Conceptually:

`timestamp = current_monotonic_time - mirror_start_time`

This avoids using wall-clock time and provides the TFT with a session-relative video timestamp.

### 5.5 checksum16

EyLink video packets contain a 16-bit checksum derived from the H.264 access unit.

The reconstructed algorithm:

1. read the H.264 data as little-endian 32-bit words;
2. add each word to an accumulator;
3. if 1-3 trailing bytes remain, interpret them as the low bytes of a final little-endian word and add it;
4. fold the accumulator's upper bits back into the lower 16 bits;
5. use the resulting 16-bit value in the VIDEO header.

Conceptual pseudocode:

```text
sum = 0

for each complete 4-byte chunk:
    sum += uint32_le(chunk)

if trailing bytes exist:
    sum += partial_uint32_le(trailing_bytes)

while sum has bits above 16:
    sum = (sum & 0xFFFF) + (sum >> 16)

checksum = sum & 0xFFFF
```

This checksum implementation has been validated by transmitting independently encoded H.264 video through the reconstructed EyLink transport and displaying it successfully on the physical Kove TFT.

### 5.6 H.264 payload

The video payload is **H.264 / AVC in Annex-B form**.

OpenCfMoto's shared `VideoPipeline` produces complete encoded access units. Codec configuration data (SPS/PPS) is retained by the pipeline and prepended to a keyframe when required so that the downstream decoder can initialize correctly.

The EyLink backend does not add another proprietary wrapper around the H.264 access unit beyond the `VIDEO_DATA` frame described above.

The resulting path is:

```text
Android Auto H.264
       |
       v
OpenCfMoto AA decoder/compositor
       |
       v
VideoPipeline encoder
       |
       | complete Annex-B access unit
       v
EyLink VIDEO_DATA framing
       |
       | TCP :11111
       v
Kove TFT H.264 decoder
```

### 5.7 No per-frame acknowledgement

No lock-step acknowledgement is required for normal H.264 transmission on the validated TFT.

After the mirror session starts, OpenCfMoto can continuously push `VIDEO_DATA` packets at the negotiated frame rate.

This is important when comparing EyLink with the EasyConn/PXC backend: EyLink video transmission should not wait for a TFT request or acknowledgement before sending every H.264 frame.

JPEG-related protocol paths appear to use acknowledgement/flow-control behavior, but they are not part of the Android Auto H.264 path documented here.

## 6. Session lifecycle and clean shutdown

A working EyLink projection session uses both TCP channels together. The control channel maintains session liveness while the mirror/video channel negotiates and carries the H.264 stream.

### 6.1 Startup sequence

The validated OpenCfMoto startup sequence is:

```text
Wi-Fi Direct group established
        |
        v
Determine TFT group-owner address
        |
        v
Connect TCP :11113
        |
        v
Start Mapheart heartbeat
        |
        v
Connect TCP :11111
        |
        v
Send MIRROR_START
        |
        v
Receive and parse "OK" capabilities
        |
        v
Configure VideoPipeline
        |
        v
Start video source / request keyframe
        |
        v
Send WIDTH_HEIGHT
        |
        v
Send VIDEO_DATA continuously
```

The control and video sockets are therefore independent TCP connections but belong to the same logical EyLink projection session.

### 6.2 Control heartbeat

While the session is active, the phone sends the following UTF-8 JSON message on port `11113`:

`{"EYLINKheart":"Mapheart"}`

It is wrapped in the phone-to-TFT control frame documented in section 3 and transmitted approximately every **500 ms**.

OpenCfMoto starts this heartbeat before beginning normal video transmission and keeps it active for the lifetime of the EyLink session.

### 6.3 Entering streaming state

Receiving the TFT's `OK` response confirms that the mirror request was accepted, but OpenCfMoto does not treat that alone as proof that video is actually flowing.

The EyLink backend transitions OpenCfMoto to its `STREAMING` connection state after the **first H.264 video frame has been successfully transmitted**.

This distinction prevents the UI from remaining indefinitely in a connection/pairing state after projection has already started on the physical dashboard.

### 6.4 MIRROR_STOP

A mirror session is explicitly terminated using function `0x05`.

The complete validated frame is:

```text
AA BB CC 05 00 00 00 00 00 00 00 00
```

Decoded:

```text
AA BB CC          magic
05                MIRROR_STOP
00 00 00 00       reserved
00 00 00 00       payload length = 0
```

### 6.5 Clean shutdown order

A clean EyLink shutdown should:

1. stop producing/sending new video frames;
2. send `MIRROR_STOP` on port `11111`;
3. flush the video socket;
4. stop the periodic `WIDTH_HEIGHT` activity;
5. close the video socket;
6. stop the `Mapheart` heartbeat;
7. close the control socket.

The shared Android Auto `VideoPipeline` should not necessarily be destroyed merely because the EyLink transport is being disconnected. Transport lifecycle and Android Auto session lifecycle are separate concerns inside OpenCfMoto.

This separation is particularly important for reconnect handling.

### 6.6 Reconnection, parking, and saved pairing

The validated Kove/OpenCfMoto setup can reuse previously saved QR-derived connection parameters; rescanning the QR is not inherently required for every motorcycle startup.

This has now been exercised across motorcycle power cycles and P2P loss/recovery. A saved Kove profile was reused to rediscover the exact EyLink P2P peer, reform the Wi-Fi Direct group, restore the TFT endpoint, and restart EyLink projection without rescanning the QR.

OpenCfMoto also keeps Android Auto and motorcycle transport lifecycle separate during longer outages. In the physically tested recovery path:

1. the motorcycle/TFT disappeared and the active P2P group was lost;
2. EyLink P2P recovery continued while Android Auto remained available for a short outage;
3. after approximately **180 seconds** without the motorcycle, OpenCfMoto parked/tore down the heavy Android Auto receiver path to reduce unnecessary work;
4. P2P discovery/recovery remained armed;
5. after the motorcycle returned and the P2P group was formed again, the saved endpoint was handed back into the Android Auto resume path;
6. Android Auto and EyLink projection were successfully established again without a new QR scan.

The approximately 180-second parking threshold is an **OpenCfMoto lifecycle policy**, not part of the EyLink protocol.

Android Auto's developer Head Unit Server remains a separate prerequisite/state dependency of the AAP side. During testing there were cases where manually restarting that server coincided with a later successful attempt, but the collected logs also show successful Android Auto AAP handshakes and live video during attempts where the motorcycle itself was still absent from Wi-Fi Direct discovery. The evidence therefore does **not** support treating a fresh Head Unit Server restart as required for every OpenCfMoto launch.

The two transports should be diagnosed independently:

```text
Motorcycle side:
saved EyLink parameters -> P2P -> EyLink TCP -> TFT

Android Auto side:
Head Unit Server / AAP -> AaReceiver -> VideoPipeline
```

A failure on one side does not by itself establish a failure on the other, and an Android Auto startup issue does not imply that the EyLink pairing must be rebuilt from a newly scanned QR.

## 7. Additional observed EyLink features

The EyLink protocol contains functionality beyond the H.264 projection path currently required by OpenCfMoto.

The features in this section are documented for completeness. Unless explicitly stated otherwise, they are **not required by the validated Kove 350 RR Android Auto implementation** and should not be interpreted as fully supported OpenCfMoto features.

### 7.1 BLE service

Analysis of the official EyLink application identified the following BLE service:

`0000aaa0-0000-1000-8000-00805f9b34fb`

Observed characteristics include:

| Characteristic | Observed purpose |
|---|---|
| `aaa1` | IP-related data |
| `aaa2` | SSID-related data |
| `aaa3` | password-related data |
| `aaa4` | connection/status-related data |
| `aaa5` | MAC / activation-key-related data |
| `aaa8` | OTA/update-related functionality |

The official application contains BLE-assisted connection/session functionality. In particular, receiving valid IP-related BLE data can initialize parts of its recorder/session infrastructure.

However, source analysis of the QR pairing path shows that BLE is **not a prerequisite for the initial Wi-Fi Direct bootstrap** used by the validated Kove.

OpenCfMoto has established P2P, connected to the EyLink TCP services, and displayed Android Auto on the TFT without reproducing this BLE path.

BLE is therefore not currently part of the OpenCfMoto EyLink projection backend.

### 7.2 `TP` messages

TFT-to-phone traffic observed in the EyLink mirror protocol includes messages beginning with the ASCII signature:

`TP`

These appear to represent touch/input-related events.

The validated Kove 350 RR TFT is **not a touchscreen**, and these messages are not required for its Android Auto projection path.

OpenCfMoto therefore does not currently implement EyLink `TP` input handling.

The existence of this protocol path may be relevant to other EyLink dashboards with touch-capable displays, but its complete event format and compatibility should be independently validated before implementing it.

### 7.3 JPEG transport

EyLink defines function `0x07` for JPEG-related mirroring data.

Reverse engineering indicates that the JPEG path uses flow-control/acknowledgement behavior that differs from the continuous H.264 push path.

OpenCfMoto does not use JPEG transport for the validated Android Auto implementation.

Android Auto projection uses the H.264 `VIDEO_DATA` path documented in section 5.

### 7.4 Audio

EyLink exposes an audio-related protocol function (`0x01`), but OpenCfMoto does not currently route Android Auto audio through the EyLink TFT.

On the validated motorcycle, Android Auto visual projection is sent to the TFT while phone audio remains on the phone's normal Bluetooth audio route.

This has been validated in real use with navigation alerts reaching a Bluetooth helmet intercom while Android Auto navigation remained displayed on the motorcycle TFT.

For the current Kove use case this separation is intentional:

```text
Android Auto video -> OpenCfMoto -> EyLink -> Kove TFT

Android Auto audio -> Android / Bluetooth -> helmet intercom
```

EyLink audio transport is therefore outside the scope of the current backend.

### 7.5 Additional incoming messages

Port `11111` is not exclusively a one-way H.264 socket.

In addition to the initial `OK` response, reverse engineering identified other TFT-to-phone message families, including:

- `TP` input messages;
- short JSON/acknowledgement traffic;
- protocol/version-related messages.

Likewise, port `11113` supports TFT-to-phone control traffic in addition to the phone's `Mapheart` heartbeat.

The current OpenCfMoto EyLink backend implements the subset required for the validated projection path rather than attempting to reproduce every feature of the proprietary application.

This is intentional: protocol features should be added when their wire behavior and hardware requirement are sufficiently understood, rather than guessed from unused code paths.

## 8. OpenCfMoto implementation

EyLink support is integrated as a motorcycle-side transport backend while reusing OpenCfMoto's existing Android Auto receiver and shared H.264 video pipeline.

The implementation does **not** embed, launch, communicate with, or otherwise depend on the proprietary `com.eryanet.eylink` Android application at runtime.

The high-level architecture is:

```text
Android Auto
     |
     v
AaReceiver / AapTransport
     |
     v
AaCompositor
     |
     v
VideoPipeline
     |
     v
AaVideoBridge
     |
     v
EylinkLink
     |
     +---- TCP :11113 control / Mapheart
     |
     `---- TCP :11111 mirror / H.264
                    |
                    v
                 EyLink TFT
```

EyLink therefore replaces only the motorcycle-facing projection transport. The Android Auto/AAP implementation remains shared with the other OpenCfMoto backends.

### 8.1 `QrData.kt`

`QrData` recognizes EyLink pairing URLs before falling through to the existing QR formats.

An EyLink QR is identified using its `productid=EYLINK` and required EyLink fields.

The parser retains the information needed for the P2P bootstrap, including:

- P2P peer name;
- device/BLE name;
- EyLink key;
- SSID/password information;
- advertised IP;
- opaque `mac` identifier.

EyLink is represented explicitly so that later connection code can select the correct backend rather than accidentally treating the QR as EasyConn/PXC.

### 8.2 `BikeWifiP2p.kt`

`BikeWifiP2p` performs the Android Wi-Fi Direct bootstrap described in section 2.

For EyLink, it:

1. discovers P2P peers;
2. matches the exact QR-derived peer name;
3. ignores the QR `mac` as a Wi-Fi Direct device address;
4. uses the real `deviceAddress` returned by Android discovery;
5. initiates the P2P connection;
6. obtains the local bind address and TFT/group-owner address after network provisioning.

The existing non-EyLink connection paths remain separate.

The current branch also contains EyLink-specific discovery retry behavior added during development. As noted earlier, this is an implementation aid rather than a confirmed requirement of the EyLink protocol and should be reviewed before upstream merge.

### 8.3 `BikeLink.kt`

`BikeLink` selects the motorcycle-side projection backend.

The current implementation distinguishes at least:

```text
EASYCONN
EYLINK
```

For non-EyLink QR data, the existing EasyConn path remains selected.

For EyLink, `BikeLink` creates/uses `EylinkLink` and starts it with the network information obtained from the P2P connection.

This keeps EyLink protocol handling out of the existing EasyConn/PXC implementation.

### 8.4 `EylinkProtocol.kt`

`EylinkProtocol` contains the low-level EyLink wire-format implementation.

Its responsibilities include:

- EyLink port/function constants;
- `MIRROR_START` construction;
- `MIRROR_STOP` construction;
- `WIDTH_HEIGHT` construction;
- `VIDEO_DATA` framing;
- little-endian field encoding;
- H.264 `checksum16`;
- parsing the TFT `OK` capability response.

Keeping these operations in a dedicated protocol component separates byte-level framing from socket/session lifecycle code.

### 8.5 `EylinkLink.kt`

`EylinkLink` owns the live EyLink transport session.

Its responsibilities include:

- opening the `11113` control socket;
- maintaining the `Mapheart` heartbeat;
- opening the `11111` mirror/video socket;
- sending `MIRROR_START`;
- reading and parsing the TFT's `OK` response;
- configuring the shared video pipeline from the negotiated dimensions;
- sending `WIDTH_HEIGHT`;
- polling complete H.264 access units from the shared `VideoPipeline`;
- wrapping them as EyLink `VIDEO_DATA`;
- maintaining frame index and session-relative timestamps;
- transitioning OpenCfMoto to `STREAMING` after the first transmitted frame;
- sending `MIRROR_STOP` and closing the EyLink transport cleanly.

The EyLink link does not create a second Android Auto implementation. It consumes the same encoded video pipeline used by OpenCfMoto's existing architecture.

### 8.6 Dynamic video configuration

After receiving the TFT capability response, the backend configures the shared pipeline using the negotiated canvas:

```text
TFT OK
  |
  +--> width
  +--> height
  `--> fps
        |
        v
VideoPipeline.configureBikeCanvas(...)
VideoPipeline.setFrameCap(...)
        |
        v
AaCompositor output
        |
        v
EyLink H.264
```

This is why the tested `784x352 @ 30 fps` configuration is not hardcoded as the only supported EyLink display geometry.

The TFT-provided bitrate is parsed and retained as part of the negotiated capability information. Whether every EyLink device requires the encoder to follow that bitrate exactly should be validated on additional hardware.

### 8.7 Android Auto video handoff

Before sending TFT video, the EyLink backend attaches to the shared pipeline through OpenCfMoto's existing Android Auto video bridge.

The pipeline is instructed that motorcycle-side data transmission is starting, which clears stale queued output and requests a fresh keyframe.

This is important for decoder startup: the first usable EyLink stream should begin from current codec configuration/keyframe data rather than old queued video.

The resulting end-to-end path has been physically validated:

```text
Android Auto AAP H.264
        |
        v
OpenCfMoto decoder
        |
        v
AaCompositor
        |
        v
OpenCfMoto H.264 encoder
        |
        v
EyLink VIDEO_DATA
        |
        v
Kove 350 RR TFT
```

### 8.8 Backend isolation and regression considerations

The EyLink implementation was intentionally added as a separate backend rather than replacing the existing EasyConn/PXC transport.

`BikeLink` now owns backend-aware transport selection and shutdown. Shared callers such as `MainActivity` and `AndroidAutoService` use `BikeLink.stopBackend()` instead of reaching directly into the EasyConn prober. This indirection is required so the same lifecycle paths can stop either EasyConn or EyLink without teaching those callers transport-specific details.

The final feature diff against `origin/main` does **not** include an `AaCompositor` change. Experimental compositor work performed during development was intentionally kept out of the EyLink feature diff so that shared rendering behavior is not changed as part of this transport addition.

Two small EasyConn-side state differences remain worth regression-testing on existing hardware:

1. `BikeLink.stopBackend()` resets the shared `proberStarted` gate when stopping the active transport, in addition to stopping the EasyConn prober when EasyConn is selected.
2. If EasyConn reaches the start gate with no available prober, the code now clears the gate and reports an explicit error instead of silently returning.

Neither difference is required by the EyLink wire protocol. They are consequences of making `BikeLink` backend-aware and should be checked against existing EasyConn/PXC devices during upstream review rather than being presented as EyLink protocol behavior.

Likewise, lifecycle paths such as Wi-Fi reacquisition, watchdog handling, and transport shutdown should be checked for assumptions that predate multiple motorcycle-side backends.

The goal of upstream cleanup is to preserve existing EasyConn behavior while making EyLink a peer backend rather than introducing EyLink-specific behavior into unrelated transports.

## 9. Validation status and known limitations

This section separates physically validated behavior from findings based on official-app analysis and from implementation details that still require broader testing.

### 9.1 Physically validated on Kove 350 RR

The following behaviors have been confirmed against the real Kove 350 RR TFT:

| Item | Status |
|---|---|
| Parse the EyLink pairing QR | **Validated** |
| Discover the TFT through Android Wi-Fi Direct | **Validated** |
| Match the TFT using the QR-derived P2P peer name | **Validated** |
| Form the P2P group without the proprietary EyLink application | **Validated** |
| Obtain/connect to the TFT group-owner address | **Validated** |
| Connect to TCP `11113` | **Validated** |
| Send `Mapheart` control heartbeat | **Validated** |
| Connect to TCP `11111` | **Validated** |
| Send `MIRROR_START` | **Validated** |
| Receive and parse the 13-byte `OK` response | **Validated** |
| Negotiate `784x352 @ 30 fps / 2000 kbps` on this TFT | **Validated** |
| Send `WIDTH_HEIGHT` | **Validated** |
| Send independently encoded H.264 using reconstructed `VIDEO_DATA` framing | **Validated** |
| Reconstructed H.264 `checksum16` | **Validated** |
| Display custom H.264 video on the physical TFT | **Validated** |
| Display Android Auto through OpenCfMoto on the physical TFT | **Validated** |
| Run without `com.eryanet.eylink` as a runtime dependency | **Validated** |
| Continue projection with the phone locked | **Observed working** |
| Continue projection with the OpenCfMoto activity outside the foreground | **Observed working** |
| Navigation audio remaining on the phone/Bluetooth intercom path | **Observed working** |
| Reuse saved pairing information without rescanning the QR | **Validated on tested setup** |
| Recover after P2P group loss and reform the group from saved pairing | **Validated on tested setup** |
| Park the Android Auto path after ~180 s of motorcycle absence and later reconnect without QR | **Validated on tested setup** |
| Exit an initial no-peer/no-group attempt after ~40 s instead of hanging indefinitely | **Observed working** |
| Clean `MIRROR_STOP` command | **Validated** |

The Android Auto projection has also been tested during real motorcycle use rather than only while stationary.

No EyLink transport disconnection was observed during the initial road test. This is useful validation but should not be interpreted as a general long-term reliability guarantee.

### 9.2 Confirmed through official EyLink application analysis

The following findings were reconstructed from the official Android application's implementation and used to guide the independent client:

- QR routing into the Wi-Fi Direct/P2P path;
- exact P2P peer-name matching;
- use of Android's discovered `WifiP2pDevice.deviceAddress`;
- WPS PBC configuration;
- `requestConnectionInfo()` / group-owner address handling;
- EyLink BLE service and characteristic roles;
- H.264/AVC encoder usage;
- 30 fps mirror configuration;
- Annex-B video handling;
- `MIRROR_START`, `MIRROR_STOP`, `WIDTH_HEIGHT`, and video framing behavior;
- periodic control heartbeat behavior;
- periodic dimension reporting;
- additional `TP`, JPEG, JSON/ack, and control-message paths.

Where official-app analysis and physical protocol testing overlap, the physical Kove test is the stronger validation for the behavior documented as working.

### 9.3 Current implementation assumptions

The following should **not** yet be treated as universal EyLink requirements:

#### `MIRROR_START` source dimensions

The validated request contains:

`1152x2560`

These values reproduce the official/proven request and work on the Kove 350 RR, but it is not yet known whether every EyLink dashboard expects the same values.

#### TCP ports

The validated TFT exposes:

- `11111` — mirror/video
- `11113` — control

These ports are currently part of the EyLink backend assumptions. Other EyLink generations have not yet been tested.

#### QR and P2P behavior

The validated unit uses a stable `EY_...` peer name and Wi-Fi Direct bootstrap.

Other EyLink implementations may differ in QR fields, credential rotation, discovery behavior, BLE involvement, or network topology.

#### Periodic `WIDTH_HEIGHT`

Repeating `WIDTH_HEIGHT` approximately every two seconds matches the reconstructed behavior and works on the validated TFT.

Whether all EyLink implementations require this periodic refresh is unknown.

### 9.4 Features intentionally not implemented

The current OpenCfMoto EyLink backend does not attempt to reproduce every feature present in the proprietary application.

Not currently implemented:

- EyLink BLE session/bootstrap features not required for projection;
- EyLink audio transport;
- JPEG mirroring;
- `TP` input handling;
- complete TFT-to-phone control-message handling;
- OTA/update-related EyLink functionality.

These omissions do not prevent the validated Kove 350 RR Android Auto video path from operating.

The Kove 350 RR TFT used for validation is non-touch, so `TP` handling is not required for this hardware.

### 9.5 Areas requiring upstream review

Before merging the implementation upstream, the following areas deserve explicit review:

1. The deliberate approximately 5-second EyLink P2P discovery/recovery interval and whether maintainers prefer a slower or adaptive backoff policy.
2. Lifecycle handling when the motorcycle Wi-Fi/P2P network disappears and later returns, including the approximately 180-second Android Auto parking threshold.
3. Backend-aware shutdown paths through `BikeLink.stopBackend()`.
4. EasyConn regression coverage for the `proberStarted` reset and explicit missing-prober error described in section 8.8.
5. Existing watchdog/reconnection code that may still assume EasyConn/PXC.
6. Removal of development-only logging or temporary diagnostics.
7. Source/comment encoding cleanup where required.
8. Verification that no machine-local Android Studio/JDK configuration is included in the change set.

The current feature diff does not contain an `AaCompositor` change, so compositor regression testing is not a merge requirement introduced by this EyLink patch itself.

### 9.6 Android Auto Head Unit Server observation

Android Auto's developer **Head Unit Server** is part of the AAP test/runtime environment used by OpenCfMoto and is conceptually separate from EyLink.

A fresh Head Unit Server restart was initially suspected to explain one reconnect success. Later log review provided a stronger discriminator: OpenCfMoto successfully dialled the Android Auto head-unit server, completed the AAP version/SSL handshake, opened the video channel, and reached live Android Auto video during attempts where the Kove TFT still failed to appear in Wi-Fi Direct peer discovery.

The available evidence therefore does **not** establish that the Head Unit Server must be restarted for every OpenCfMoto launch. It should be running and healthy when the Android Auto side is expected to connect, but P2P discovery failures must be investigated independently.

```text
EyLink:
QR / saved pairing -> Wi-Fi Direct -> TCP transport -> TFT

Android Auto:
Head Unit Server -> AAP session -> OpenCfMoto video source
```

The Head Unit Server is not an EyLink handshake requirement.

### 9.7 Compatibility statement

At the time of writing, the appropriate compatibility claim is:

> **EyLink projection is confirmed working on the tested Kove 350 RR. The protocol implementation is designed around negotiated TFT capabilities and may support additional EyLink dashboards, but other EyLink hardware has not yet been validated.**

Future hardware reports should record at minimum:

- motorcycle make/model/year;
- EyLink QR shape with secrets redacted;
- P2P peer name behavior;
- group-owner address;
- control/video ports;
- `MIRROR_START` response;
- negotiated dimensions, fps, and bitrate;
- whether H.264 projection succeeds.

This will make it possible to distinguish common EyLink protocol behavior from Kove- or dashboard-specific behavior as additional hardware is tested.

## 10. Research methodology and attribution

This work was produced through an iterative reverse-engineering process combining source analysis, protocol experiments, implementation, and validation against physical motorcycle hardware.

### 10.1 Methodology

The investigation used several independent sources of evidence:

1. Static analysis of the official EyLink Android application to identify QR parsing, Wi-Fi Direct bootstrap, BLE behavior, socket roles, packet construction, encoder configuration, and session lifecycle.
2. Android network and system diagnostics to observe Wi-Fi Direct group formation, addressing, and active TCP connections.
3. Controlled TCP experiments against the physical TFT to reproduce individual EyLink commands independently of the proprietary application.
4. Independent H.264 encoding and EyLink packet construction to validate the reconstructed video framing and checksum.
5. Integration of the reconstructed transport into OpenCfMoto's existing Android Auto and video pipeline.
6. Repeated physical validation on the Kove 350 RR, including stationary testing and real motorcycle use.

A protocol detail is described as validated when it was reproduced successfully against the physical TFT or directly confirmed by sufficiently clear behavior in the official implementation. Findings that remain assumptions or have only been observed on one hardware implementation are explicitly identified as such throughout this document.

### 10.2 Development process

The physical testing environment, motorcycle hardware, pairing data, logs, screenshots/photos, command execution, build/install testing, and real-world validation were provided and performed by the Kove 350 RR owner/contributor.

The technical reverse-engineering analysis, protocol reconstruction, implementation design, code generation and debugging guidance were developed with substantial assistance from **ChatGPT by OpenAI**, working iteratively from the supplied application analysis, decompiled source, logs, network observations, and physical test results.

In practical terms, the process followed a repeated loop:

`observe -> analyze -> form hypothesis -> implement -> test on hardware -> confirm/correct`

The AI-assisted analysis could propose and implement protocol behavior, but claims of physical compatibility in this document are based on results actually observed by the contributor on the motorcycle hardware.

### 10.3 Attribution boundaries

The contributor should not be interpreted as personally claiming authorship of reverse-engineering analysis or implementation work that was produced through AI assistance.

Conversely, ChatGPT/OpenAI did not independently access or operate the motorcycle hardware. Physical results, including successful video display, Android Auto projection, connection behavior, and road-test observations, were reported and validated through the contributor's test environment.

This distinction is preserved so that the provenance of both the technical implementation and the hardware validation remains clear.

### 10.4 Reproducibility

The purpose of documenting the protocol at this level is to make the result independently reproducible rather than dependent on the original reverse-engineering session.

A future implementation should be able to reproduce the validated path using the information in this document:

```text
Parse EyLink QR
        |
        v
Discover and connect Wi-Fi Direct peer
        |
        v
Obtain TFT group-owner address
        |
        +--> TCP 11113 -> control heartbeat
        |
        `--> TCP 11111 -> MIRROR_START
                              |
                              v
                         parse TFT OK
                              |
                              v
                     configure H.264 output
                              |
                              v
                  WIDTH_HEIGHT + VIDEO_DATA
                              |
                              v
                           TFT video
```

Additional EyLink hardware reports and independent implementations are encouraged. Results from other dashboards should distinguish between behavior that matches this documented protocol and behavior that requires device-specific changes.
