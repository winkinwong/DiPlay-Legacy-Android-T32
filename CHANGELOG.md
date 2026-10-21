# DiPlay 0.2.46-t32.4 — 2026-10-21

- Floating dot launcher: a small overlay dot opens DiPlay from the head-unit desktop; it hides itself while CarPlay is on screen and on the lock screen (ported from upstream #119, scoped for this head unit).
- Wireless smoothness: echo AirPlay keepalive probes and advertise keepAliveLowPower so the iPhone may idle in low-power mode — the multi-second stalls that clustered in the first minutes after (re)connect are reduced; validated over three road tests on 2026-10-20/21.
- Wireless smoothness: receive stalls no longer tear the session down (keyframe request instead), the picture refreshes on every surface recreation, the first-TCP watchdog widens to 60 s (measured iPhone connect 20.7–30.7 s), the iAP2 tunnel reconnect delay drops to 3 s, and the mDNS rebind race that failed reconnects is fixed — reconnect median went from 29–38 s to ~9 s.
- Navigation ducking: duckAudio/unduckAudio from the iPhone now lower head-unit music during turn-by-turn prompts and restore it afterwards, decoupled from the audio-focus setting.
- Session survival: swiping DiPlay from recents no longer kills an active session on non-BYD head units; reverse-camera returns show the picture sooner via the unconditional keyframe refresh.
- Diagnostics: a link-health monitor samples NIC counters and /proc/net/wireless radio level every 10 s during wireless sessions, stall reports carry a link snapshot, and steering-wheel media keys plus lifecycle events reach the exported report.

**All versions in this repository are optimized for the 2020 Nissan X-Trail (T32 platform, Android 4.4.2); other models must test on their own.**

# DiPlay 0.2.46-t32.3 — 2026-10-16

- Wireless CarPlay now bridges the vendor AnwPhoneLink Bluetooth stack: head units with no framework BluetoothAdapter and no HSAE Bluetooth service (like this X-Trail, which previously failed wireless with "No Bluetooth adapter") get wireless CarPlay; the paired-phone list is queried through the AnwPhoneLink service and the iAP2 RFCOMM/SPP channel runs over it.
- Port upstream #138/#165/#157 wireless gate: gate on the hybrid adapter, wait up to 3 s for enable, and proceed with a warning when Bluetooth reports off but a manual iPhone address was entered.
- Validated on the target head unit on 2026-10-16: wireless connection works and is slightly smoother than USB.

**All versions in this repository are optimized for the 2020 Nissan X-Trail (T32 platform, Android 4.4.2); other models must test on their own.**

# DiPlay 0.2.46-t32.1 — 2026-10-14

- First X-Trail (chassis T32) head-unit optimization release: built on the author's v0.2.46 with the four fixes from issue #134.
- Stop Android 4.4 frames from falling back to BouncyCastle's Java ChaCha20-Poly1305; prefer the platform implementation and fall back to the bundled Conscrypt native one (9.1× faster on the same code path in an offline benchmark).
- Redact crash stacks line by line and keep a durable crash record that survives a restart, so an exported report finally carries the stack instead of an empty `FATAL`.
- Keep the wired iAP2 link while AirPlay media is still arriving instead of resetting after 90 s of control silence, which tore down a healthy session on the X-Trail.
- Report sub-1 MB/s decrypt throughput instead of 0 and add a `Frame cipher:` line; reposition the README as the X-Trail (T32) head-unit optimization build with an explicit trademark disclaimer.
- Signed with this repository's own release certificate (alias `t32-key0`), which cannot install over a build signed by the author's v0.2.21 key.
- Validated on the target head unit on 2026-10-14: audio healthy, USB self-recovered without replugging, video smooth via the native cipher; touch-swipe latency is the known item tracked for the next release.

See [v0.2.46-t32.1 release notes](docs/RELEASE-NOTES-0.2.46-t32.1.md).

# DiPlay 0.2.46 — 2026-10-13

- Bring in the latest AirPlay wired listener interface-scoped binding and kernel LISTEN diagnosis.
- Use genuine platform API levels for USB/VPN compatibility on vendor ROMs that report misleading SDK levels.
- Improve Bluetooth diagnosis for HSAE external adapters and clarify iPhone wireless pairing instructions.
- Retain the Android 4.4 TLS 1.2 update fix and verified signed release installation from v0.2.45.

See [v0.2.46 release notes](docs/RELEASE-NOTES-0.2.46.md).

# DiPlay 0.2.45 — 2026-10-12

- Fix old Android head-unit update checks that negotiate only obsolete SSLv3 protocols; use a bundled TLS 1.2 HTTPS stack for Android 4.4/5.0 and a narrowly scoped fallback for affected newer vendor ROMs.
- Preserve certificate and host validation, approved GitHub redirect targets and APK SHA-256/signature verification. Improve localized failure guidance.
- Add TLS downgrade regression tests and live GitHub update-check instrumentation on Android 4.4 and Android 11.
- Include the latest upstream NCM TCP diagnostic budget/SYN observability improvements.

See [v0.2.45 release notes](docs/RELEASE-NOTES-0.2.45.md).

# DiPlay 0.2.44 — 2026-10-12

- Rebuild software-update dialogs in the navy/cyan car-display style: release notes, version banner, progress stages, and post-download installation confirmation.
- Show live downloaded bytes and transfer speed; allow downloads to continue in the background without cancelling their worker.
- Keep SHA-256 and signing-certificate checks plus explicit Android installation consent; improve Chinese/English update copy and Android 4.4-compatible drawing.
- Add UI regression tests for the update prompt, download-stage transitions, background button, and installation confirmation.

See [v0.2.44 release notes](docs/RELEASE-NOTES-0.2.44.md) for details and device-validation limits.

# DiPlay 0.2.14 — 2026-10-07

- Group Settings by driver goal with search, adaptive layouts, quick controls and reconnect notices (#369).
- Add an independent Interface size control, preserving CarPlay geometry and following real density/window changes (#378).
- Add a default automatic-connection choice, scheduled day/night mode and launcher return to active CarPlay (#373, #365, #376).
- Improve custom car-button image selection and clarify the main app's source-build instructions (#364, #377, #374).
- Repair the captured USBMUX diagnostic trailer, wake drained Bluetooth receive queues and retry explicitly rejected large USB reads once at 16 KiB; improve stream diagnostics (#362).
- Select compatible video output without window hardware acceleration; use Android video decryption when available and retain a fallback (#362, #332).
- Add default-off experimental Smooth video pacing with explicit latency/picture-adjustment limits (#367).
- Preserve usable hotspot interface addresses and authenticated Bluetooth control on the observed rendered-session handoff fallback (#394, #396).
- Add configurable steering-wheel Siri, bounded microphone-source fallback and optional audio-focus handling (#360, #371, #339).
- Repair BYD call-watcher/audio-status behavior and add opt-in, default-off experimental software call echo cancellation and voice filtering (#370).
- Preserve artwork proportions, correct system-bar-aware split/rotation geometry and recognize the specifically observed 1280×480 DiLink 3 cluster surface (#375, #389, #388).

See [0.2.14 release notes](docs/RELEASE-NOTES-0.2.14.md) for all 20 contribution links, credits and limitations. Android 9/API 28 remains required. See [validation](docs/VALIDATION.md) for measured checks; complete-release vehicle acceptance is not claimed.

# DiPlay 0.2.13 — 2026-10-06

- Enable the legacy Android 9 Wi-Fi Direct group path with generated credentials and serialized ownership/cleanup; requested frequency remains unverified on Android 9 (#282).
- Prefer current IPv4 hotspot endpoints, preserve scoped IPv6 fallback, and refine Auto channel priorities beside a 5 GHz station without guaranteeing a band (#283, #309).
- Avoid unused Android NSD and USB-service dependencies for wireless startup; retain only rendered sessions on the guarded handoff fallback (#313, #300, #258).
- Add explicit experimental hotspot join Check/Apply/Restore on eligible Android 13+, user-configured 5 GHz hotspots, with complete private recovery state and no automatic mutation (#251).
- Switch wireless to USB within the host activity, verify requested permissions independently, preserve other accessibility services and cancel stale permission work (#268).
- Preserve validated USBMUX payload replies with narrow four-byte trailer recovery (#298).
- Add optional live dock/split-screen areas, square-canvas screen rotation and the selected-decoder square check; add a default-off experimental side panel (#246, #277, #284, #245).
- Preserve DiLink 4 native casting mode, offer pre-connection calibration and apply live cluster picture adjustments (#260, #265).
- Retain a recent dashboard turn card only across wireless replacement within its stale window; add a Smaller map choice (125%) and the checked DiLink 3 full-then-half projection sequence with compensation (#304, #306, #296).
- Restore battery reads when only sys.car.protocol is populated and recover eligible unbound wheel services using already-authorized ADB (#285, #297).
- Add independent default-off experimental DiLink 3 call keys and dashboard calls, with initialized watcher readiness, unique ownership, pristine-safe cancellation and retryable dirty cleanup; target-car acceptance remains requested (#243).
- Correct the observed 24 kHz Siri microphone RTP clock while retaining 48 kHz for telephony/unobserved formats; add bounded, default-off experimental AAC-LC buffered music and single-session renderer ownership (#295, #308).
- Set TCP_NODELAY on the touch event channel; contributor latency observations remain device-specific (#311).
- Improve full-size multi-window home/settings appearance, ambient-setting visibility and shared menu persistence; add main-settings car-button customization (#252, #239, #281, #302).
- Add light waiting/cluster placeholders and a 300 ms cluster fade; the main waiting screen follows CarPlay day/night mode (#305, #317).
- Add Traditional Chinese (Taiwan) as the seventh app language, preserve explicit script selection and correct Simplified Chinese hotspot wording (#314, #286). The release website also gains a Traditional Chinese edition.
- Retain the multilingual website groundwork, add the smooth-wireless guide and make buffered-ownership tests deterministic without runtime/API changes (#240, #310, #312).

See [0.2.13 release notes](docs/RELEASE-NOTES-0.2.13.md) for all 35 contribution links, credits, experimental settings, compatibility limits and diagnostic export steps. Final exact-release validation is recorded in [VALIDATION](docs/VALIDATION.md). This remains a public preview; no fresh complete-release vehicle test is claimed.

# DiPlay 0.2.12 — 2026-10-04

- Add Existing Wi-Fi / Same LAN wireless CarPlay with scoped IPv4/IPv6 discovery and network-change cleanup (#223).
- Wait for a stable car-hotspot interface and recover bounded wireless attempts when no AirPlay TCP follows StartSession (#229); add observed-state, authorized-ADB hotspot fallback on firmware exposing supported commands (#235).
- Improve Apple USB attach matching and narrowly scoped optional USB-prompt assistance (#170, #224).
- Pause Android 10 station scans during eligible hotspot/P2P sessions, preserving Same LAN, with controller leases and durable retryable restoration (#225).
- Improve split-screen, launcher cards, short-screen preparation and virtual cluster/floating-map geometry (#171, #172, #181).
- Add independent system-bar controls and correct in-session save/cancel and Local/USB-CH341 authentication selection (#191, #194).
- Add system, light-sensor, day and night CarPlay appearance modes, richer custom turn cards, and live main-video picture controls (#178, #193, #211).
- Offer custom integer resolution from 30% to 160%, with shared limits, correct 30%/160% labels and decoder/canvas capability fallback; refresh connection settings on resume (#179, #230, #196).
- Reconcile opt-in DiLink 4 cluster routing/calibration into one decoder owner, retain verified HUD gates, and journal exact stock-map holds and recovery (#213, #187).
- Add DiLink 3 guidance text and projection-display support with committed recovery before mutation, partial-setup compensation and retryable stock restoration (#182).
- Add opt-in wheel map zoom and main-screen joystick while preserving press/release and call behavior; reject stale queued work across phone/screen changes (#214, #231).
- Switch supported dashboard contents live using actual delivery and safely retained paused choices; preserve selection across stream/phone replacement (#232).
- Add a five-second dashboard-song-on-change window with timer invalidation, and retain album art while the next transfer is pending (#215, #228).
- Export reports through Downloads, document picker, app-external or private fallback storage, with explicit View/Share actions (#185, #219).

See [0.2.12 release notes](docs/RELEASE-NOTES-0.2.12.md) for the complete corrections, hardware evidence and issue-reporting steps. This remains a public preview; no fresh end-to-end vehicle test of the complete repaired release is claimed.

# DiPlay 0.2.11 — 2026-10-03

- Add preferred Wi-Fi Direct channel selection for the next connection; Auto remains the default, and manual channel rejection/mismatch reports an error (#175).
- Add a movable custom dashboard turn card with 2% position steps; leave unknown arrows blank and clear expired guidance (#155).
- Offer two-, three- or four-finger settings swipes, keeping three as the default (#156).
- Add opt-in read-only legacy vehicle-data detection while preserving default DiLink 5.0 mode; reject unaccepted/stale probe publication and fix accepted-battery lock ordering (#158, integrated through #173).
- Keep wireless location/vehicle data on its runtime Wi-Fi link and defer parked-video decisions until SETUP/event readiness (#157).
- Add optional automatic car-hotspot startup, off by default, with verified own-package authorization and unified vehicle settings (#164/#173).
- Support Android TV/remote controls while preserving head-unit touchscreen Back and absolute knob X/Y; keep its workflow source-only (#146).
- Retain artists across partial title updates and publish song metadata/artwork only when changed (#161, #162).
- Correct Android 9 audio API use, release failed codecs, reconnect with settled geometry/readiness, and require own-app VPN scope (#168 and local corrections).
- Add one guarded Auto-mode API 29 P2P recovery for the exact reported pre-create Builder error, plus bounded wireless/media/theme/own-app-exit diagnostics without payload recording or automatic uploads.

See [0.2.11 release notes](docs/RELEASE-NOTES-0.2.11.md) for requirements, device-test limits and fresh-report guidance. Preferred channel selection does not establish stutter as fixed; Qin Plus, Siri, iOS 15 and day/night reports remain under investigation. Android 9 is still the minimum; Wi-Fi Direct needs Android 10+.

# DiPlay 0.2.10 — 2026-10-03

- Publish CarPlay song metadata, position and artwork to Android media sessions; bound artwork queues and reject stale work across sessions (#82).
- Preserve normal USBMUX frames while handling narrowly validated handshake padding (#114); let USB connect without saved wireless-hotspot credentials (#130).
- Handle unknown reported Wi-Fi Direct security types, retry busy channels and allow bounded 5 GHz fallback (#121).
- Select an available AirPlay port and advertise it over Bonjour and wired/wireless iAP2; close sockets on failed setup/notification (#143).
- Enable available platform echo cancellation and noise suppression for calls, restoring the previous mode afterward (#116).
- Detect BYD CAN/CANFD battery protocols and clear unsupported/stale readings (#123).
- Add a saved show/hide setting for the home-screen dashboard-map mirror (#133).
- Improve optional parked video with seeking and ten-second skip controls; validate media URLs and redirects (#129).
- Extend Ukrainian translations, including the new map-mirror setting (#128 and release localization).
- Add bounded anonymous Bluetooth/USB/boot and microphone capture/encode/send diagnostics to exported reports; omit audio and packet contents.

See [0.2.10 release notes](docs/RELEASE-NOTES-0.2.10.md) for contributor credits, requirements and validation limits. Android 9 remains the minimum supported version.

# DiPlay 0.2.9 — 2026-10-02

- Follow BYD head-unit day/night changes while CarPlay is visible, including firmware that does not reliably deliver Android configuration callbacks.
- Restore media and navigation audio stream selection to 0–20 and inherit older saved navigation settings when no new selection exists. Vendor-specific outputs depend on head-unit support.
- Keep CarPlay connected through normal surround-view window changes, preserving video proportions and touch alignment. A connection started in a narrow camera window reconnects once when the window grows to restore the full-screen canvas.
- Add Ukrainian to the app language picker, Android app-language settings, and website. Correct its audio help to describe streams 1–20.
- Add an optional CarPlay song title, artist, and play/pause display on the BYD instrument cluster, using the existing network ADB connection.
- Add a CarPlay navigation widget for launchers that host standard Android widgets, with the next turn, road, distance, arrival information, and song. Clear expired guidance and explicitly cleared song titles.
- Add an optional floating copy of the dashboard map on the centre screen, with drag, pinch-to-resize, and tap-to-open controls. Requires permission to draw over other apps; Usage Access restricts it to home screens.
- Fix floating-map resizing on head units that ignore small pinch gestures.
- Let compatible launchers embed the live dashboard map on Android 11 and newer. Sharing is off by default; turning it off closes existing shared map views.
- Add map-host and DiPlay Home sample apps for developers. DiPlay Home combines the live map, standard Android widgets, a clock, and an app list; sample builds, lint, and Home back-navigation tests are checked in CI.
- Leave the GPS course empty when its direction is unknown, instead of reporting north. Valid GPS directions are preserved.
- Thanks to @lpcheng1208 for PRs [#71](https://github.com/shihabal3amri/DiPlay/pull/71), [#88](https://github.com/shihabal3amri/DiPlay/pull/88), and [#89](https://github.com/shihabal3amri/DiPlay/pull/89).
- Thanks to @romanchukg-cloud for PRs [#93](https://github.com/shihabal3amri/DiPlay/pull/93), [#101](https://github.com/shihabal3amri/DiPlay/pull/101), [#105](https://github.com/shihabal3amri/DiPlay/pull/105), [#106](https://github.com/shihabal3amri/DiPlay/pull/106), [#107](https://github.com/shihabal3amri/DiPlay/pull/107), [#108](https://github.com/shihabal3amri/DiPlay/pull/108), and [#109](https://github.com/shihabal3amri/DiPlay/pull/109).

See [0.2.9 release notes](docs/RELEASE-NOTES-0.2.9.md) for the merged changes and validation limits.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
