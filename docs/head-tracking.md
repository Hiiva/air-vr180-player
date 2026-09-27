# Android head tracking

The USB reader calls `HeadTracker.update` synchronously for every complete IMU
report, before `NrealManager` coalesces samples for the main-thread status UI.
The main thread only reads an independent orientation snapshot. Button reports
use a separate USB read loop, so their timeout cannot delay the IMU endpoint.

The IMU endpoint keeps 512 asynchronous
[`UsbRequest`](https://developer.android.com/reference/android/hardware/usb/UsbRequest#queue(java.nio.ByteBuffer))
reads queued into reusable
direct buffers (32 KiB of packet storage). This gives roughly 512 ms of headroom
at 1000 reports/second while Java is delayed by GC or scheduling. Each completed
report is copied and its request requeued before decoding/fusion; delivery does
not wait for a batch to fill. All buffered reports still reach fusion in order,
using their original device timestamps. Shutdown cancels and drains requests
before freeing them. Command exchanges remain synchronous before streaming.

This addresses a measured input-loss failure: a September 26 diagnostic recording
contained 134 device-timestamp gaps over 50 ms (17.59 seconds in total), with
118 within 30 ms of an app GC completion. The native orientation stayed unchanged
across every gap. Buffering targets missing USB reports without changing VQF,
bias tuning, recentering, or the policy for intervals that really are missing.
Live validation on September 27 with the connected glasses covered 26 app GC
cycles (up to 223 ms total GC time), with no new long sensor gaps after connection
setup. Deliberately suspending app processing produced a measured 232.6 ms host
pause while device timestamps remained 1 ms apart across the pause. The user
reported normal movement and successfully reconnected the glasses. This verifies
buffering for that failure mode, not every possible cause of a tracking freeze.
Connection setup can still contain a timestamp gap before reads are queued, and
pauses longer than the queue's headroom can still lose reports.

Processing every report fixes an integration defect in the previous pipeline: a 100 ms
UI stall during a 90-degree turn left the original tracker at 80.82 degrees.
The missing 9.18 degrees were never recovered. UI coalescing also discarded fresh
magnetic observations needed by the original bias estimator.

Fusion uses the upstream VQF C++ implementation (MIT, pinned revision in
`android/app/src/main/cpp/vqf/README.md`). It estimates bias during rest and movement and
filters acceleration in the inertial frame. Its fixed 1 ms time grid is driven by
interpolated device timestamps, not Android callback timing. Reordered/duplicate
packets are ignored. A gap over 50 ms interrupts rest evidence and preserves the
last pose rather than integrating an unknown interval. A new USB connection
resets the sensor timeline. Recenter and playback transitions preserve bias.

The app and VQF use different up axes. Inputs are rotated from [X,Y,Z] to [X,-Z,Y],
with acceleration converted from g to m/s²; output quaternions are rotated back.
Magnetic heading is never used to steer orientation. Fresh, smoothed relative
magnetic direction can veto rest and supports initial larger-offset estimation.
Missing magnetic data does not disable small-offset rest bias estimation. After
learning an offset, a sustained corrected rate over 0.1 degree/second disables
zero-rate learning, while motion bias estimation and pose integration continue.
There is no pose freeze, angular dead zone, or manual calibration workflow.

## Verification on 2026-09-11

Validation programs were run from the OS temporary directory, not added to the
repository. The C++ replay ran both on Windows and on the connected arm64 Android
phone using the actual adapter and vendored VQF source.

- Noisy rest with 0, 0.002, 0.008, and 0.020 rad/s yaw offsets and fresh magnetic
  observations: approximately 0.0011 degree drift over the measured 60 seconds,
  after 15 seconds of automatic settling.
- Missing magnetic samples with a 0.002 rad/s offset: approximately 0.0011 degree
  over the same interval.
- Turns at 0.2, 0.5, 1, 10, and 90 degrees/second after settling: under 0.014 degree
  angular error; less than 0.006 degree additional movement during the 30 seconds
  after stopping.
- Startup during 0.2–20 degree/second yaw with fresh magnetic observations:
  motion retained, no false learned yaw offset in those traces.
- Combined pitch/yaw/roll movement for 60 seconds: 1.17 degree total pose error,
  including the initial 1.16 degree heading offset accumulated while learning bias.
- Bias warming from 0.002 to 0.003 rad/s over 120 seconds: 0.086 degree drift.
- Irregular timestamps: 0.003 degree error; duplicate/reordered packets and a
  one-second gap did not rotate the held pose.
- An Android `app_process` check exercised the actual packaged Java tracker and
  JNI library: a 10-degree turn returned 10.000001 degrees, recenter retained
  learned bias, and snapshot ownership, reset, and repeated close passed.
- Debug APK built for all four Android ABIs, installed over the existing app
  without clearing data, and launched successfully. Lint still reports the
  existing 38 unrelated errors, principally Media3 opt-in annotations.

These are synthetic sensor replays and Android integration checks, not a measured
claim about the glasses. The glasses were disconnected during verification.
Six-axis tracking cannot universally distinguish constant slow yaw from bias
without independent motion evidence. Larger initial bias without usable magnetic
observations and sustained motion with unobservable yaw remain physical limits.
Automatic settling can leave an initial heading offset; there is no hidden
automatic recenter to conceal it.

Every five seconds, the `HeadTracker` Android log tag records processed sample
count, timestamp gaps, detected rest, and the three estimated bias components in
degrees/second, so normal use supplies diagnostic evidence automatically.
