#include "tracking_filter.hpp"
#include <algorithm>
#include <cmath>

namespace {
constexpr double radians = 0.017453292519943295;
constexpr double gravity = 9.80665;
}

VQFParams TrackingFilter::parameters() {
    VQFParams p;
    p.restThGyr = 0.5; // deg/s deviation from the filtered rate, NOT absolute gyro bias
    p.restThAcc = 0.3; // m/s^2; tolerate sensor noise and breathing
    p.restMinT = 2.5; // allow fresh motion evidence to veto an initial zero-rate estimate
    p.biasSigmaRest = 0.01; // follow warm-up drift during quiet viewing
    p.magDistRejectionEnabled = false; // no uncalibrated magnetic heading in the pose
    // Keep a learned yaw-axis offset during motion instead of slowly decaying it to zero.
    p.biasVerticalForgettingFactor = 1e-9;
    return p;
}

TrackingFilter::TrackingFilter() : filter(parameters(), 0.001) {}

void TrackingFilter::interruptRest() {
    filter.setRestBiasEstEnabled(false);
    filter.setRestBiasEstEnabled(true);
}

void TrackingFilter::observeMagneticDirection(int64_t timestampNs, const double direction[3]) {
    double norm = VQF::norm(direction, 3);
    if (!std::isfinite(norm) || norm < 1e-6) return;
    double unit[3] = {direction[0]/norm, direction[1]/norm, direction[2]/norm};
    if (!magneticInitialized || timestampNs-lastMagneticNs > 500000000) {
        std::copy(unit, unit+3, magneticMean);
        std::copy(unit, unit+3, magneticReference);
        magneticInitialized = true;
    } else {
        double alpha = 1-std::exp(-double(timestampNs-lastMagneticNs)*1e-9/0.25);
        for (int i = 0; i < 3; ++i) magneticMean[i] += alpha*(unit[i]-magneticMean[i]);
        VQF::normalize(magneticMean, 3);
        double dot = 0;
        for (int i = 0; i < 3; ++i) dot += magneticMean[i]*magneticReference[i];
        if (dot < std::cos(0.25*radians)) {
            magneticMotionUntilNs = timestampNs + 2500000000LL;
            std::copy(magneticMean, magneticMean+3, magneticReference);
            interruptRest();
        }
    }
    lastMagneticNs = timestampNs;
}

void TrackingFilter::update(int64_t timestampNs, const double gyro[3], const double accelG[3],
                            const double* freshMagneticDirection) {
    for (int i = 0; i < 3; ++i) {
        if (!std::isfinite(gyro[i]) || !std::isfinite(accelG[i])) {
            interruptRest();
            return;
        }
    }
    if (timestampNs < 0) return;
    double g[3] = {gyro[0], -gyro[2], gyro[1]};
    double a[3] = {accelG[0]*gravity, -accelG[2]*gravity, accelG[1]*gravity};
    if (initialized && timestampNs <= previousNs) {
        // Never rewind the integration clock for duplicate or reordered packets.
        interruptRest();
        return;
    }
    if (freshMagneticDirection) observeMagneticDirection(timestampNs, freshMagneticDirection);
    freshMagneticEvidence = magneticInitialized && timestampNs-lastMagneticNs < 500000000;
    if (!initialized || timestampNs - previousNs > 50000000) {
        if (initialized) {
            ++gaps;
            interruptRest();
        } else {
            // Align inclination without inventing angular motion on the first observation.
            filter.updateAcc(a);
        }
        initialized = true;
        nextNs = timestampNs + periodNs;
        std::copy(g, g+3, meanGyro);
    } else {
        // Interpolate onto VQF's fixed sample clock using DEVICE time, never host/UI time.
        // This also supports report jitter and non-1000-Hz devices without a wrong dt.
        while (nextNs <= timestampNs) {
            double fraction = double(nextNs - previousNs)/double(timestampNs - previousNs);
            double gi[3], ai[3];
            for (int i = 0; i < 3; ++i) {
                gi[i] = previousGyro[i] + fraction*(g[i] - previousGyro[i]);
                ai[i] = previousAccel[i] + fraction*(a[i] - previousAccel[i]);
            }
            step(gi, ai);
            nextNs += periodNs;
        }
    }
    previousNs = timestampNs;
    std::copy(g, g+3, previousGyro);
    std::copy(a, a+3, previousAccel);
    ++samples;
}

void TrackingFilter::step(const double gyro[3], const double accel[3]) {
    double bias[3];
    double sigma = filter.getBiasEstimate(bias);
    if (filter.getRestDetected() && sigma < 0.1*radians) biasReady = true;
    double residualSquared = 0;
    constexpr double alpha = 1.0 - 0.9960079893439915; // 250 ms mean, at 1 kHz
    for (int i = 0; i < 3; ++i) {
        meanGyro[i] += alpha*(gyro[i] - meanGyro[i]);
        residualSquared += (meanGyro[i] - bias[i])*(meanGyro[i] - bias[i]);
    }
    double accelNorm = VQF::norm(accel, 3);
    bool plausibleGravity = accelNorm > 0.7*gravity && accelNorm < 1.3*gravity;
    // Once an offset is established, a steady intentional turn must not become a new
    // zero-rate observation just because it has low variance. Motion bias estimation stays on.
    // Without independent stillness evidence, preserve the old tracker's conservative
    // startup rate limit. Otherwise a slow turn already in progress on connect becomes bias.
    double rateLimit = biasReady ? 0.1*radians : (freshMagneticEvidence ? 2.0*radians : 0.004);
    bool magneticMotion = magneticInitialized && nextNs < magneticMotionUntilNs;
    bool allowRest = plausibleGravity && !magneticMotion && residualSquared < rateLimit*rateLimit;
    filter.setRestBiasEstEnabled(allowRest);
    filter.updateGyr(gyro);
    if (plausibleGravity) filter.updateAcc(accel);
}

void TrackingFilter::quaternion(double out[4]) const {
    double q[4];
    filter.getQuat6D(q);
    // Conjugate the coordinate transformation back into the app's Y/up basis.
    out[0] = q[0]; out[1] = q[1]; out[2] = q[3]; out[3] = -q[2];
}

void TrackingFilter::diagnostics(double out[6]) const {
    double bias[3];
    filter.getBiasEstimate(bias);
    out[0] = bias[0]/radians;
    out[1] = bias[2]/radians;
    out[2] = -bias[1]/radians;
    out[3] = filter.getRestDetected() ? 1 : 0;
    out[4] = double(samples);
    out[5] = double(gaps);
}
