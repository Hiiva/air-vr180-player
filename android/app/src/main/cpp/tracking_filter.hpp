#pragma once

#include "vqf/vqf.hpp"
#include <cstdint>

// Sensor-frame convention here is the app's right-handed X/right, Y/up, Z/back.
// VQF receives [X, -Z, Y], an ordinary rotation into its Z/up convention.
class TrackingFilter {
public:
    TrackingFilter();
    void update(int64_t timestampNs, const double gyro[3], const double accelG[3],
                const double* freshMagneticDirection = nullptr);
    void quaternion(double out[4]) const;
    void diagnostics(double out[6]) const;

private:
    static constexpr int64_t periodNs = 1000000;
    static VQFParams parameters();
    void step(const double gyro[3], const double accel[3]);
    void interruptRest();
    void observeMagneticDirection(int64_t timestampNs, const double direction[3]);

    VQF filter;
    bool initialized = false;
    bool biasReady = false;
    int64_t previousNs = 0;
    int64_t nextNs = 0;
    uint64_t samples = 0;
    uint64_t gaps = 0;
    double previousGyro[3]{};
    double previousAccel[3]{};
    double meanGyro[3]{};
    double magneticMean[3]{};
    double magneticReference[3]{};
    int64_t lastMagneticNs = 0;
    int64_t magneticMotionUntilNs = 0;
    bool magneticInitialized = false;
    bool freshMagneticEvidence = false;
};
