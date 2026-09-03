VQF by Daniel Laidig, licensed under MIT (see LICENSE).

Unmodified upstream vqf.cpp and vqf.hpp from:
https://github.com/dlaidig/vqf/tree/86ba56bdd3158b9b05f9f9fe5596866ba326438c

Laidig and Seel, “VQF: Highly Accurate IMU Orientation Estimation with Bias
Estimation and Magnetic Disturbance Rejection”, Information Fusion 91 (2023).
https://doi.org/10.1016/j.inffus.2022.10.014

The Android adapter uses the 6D output, including motion/rest bias estimation.
Fresh relative magnetic direction is only a motion veto / initial stillness check
for learning larger biases; magnetic heading is never applied to the view. Native input
is resampled on a 1 ms sensor-time grid; UI scheduling never drives the filter.
