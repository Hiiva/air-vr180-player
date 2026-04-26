package com.enricoros.nreal.player;

final class Quaternion {
  float w;
  float x;
  float y;
  float z;

  Quaternion() {
    setIdentity();
  }

  Quaternion(float w, float x, float y, float z) {
    this.w = w;
    this.x = x;
    this.y = y;
    this.z = z;
  }

  void setIdentity() {
    w = 1.0f;
    x = 0.0f;
    y = 0.0f;
    z = 0.0f;
  }

  void set(Quaternion other) {
    w = other.w;
    x = other.x;
    y = other.y;
    z = other.z;
  }

  Quaternion copy() {
    return new Quaternion(w, x, y, z);
  }

  Quaternion conjugated() {
    return new Quaternion(w, -x, -y, -z);
  }

  void normalize() {
    float length = (float) Math.sqrt(w * w + x * x + y * y + z * z);
    if (length < 1.0e-6f) {
      setIdentity();
      return;
    }
    w /= length;
    x /= length;
    y /= length;
    z /= length;
  }

  void multiplyRight(Quaternion rhs) {
    float nw = w * rhs.w - x * rhs.x - y * rhs.y - z * rhs.z;
    float nx = w * rhs.x + x * rhs.w + y * rhs.z - z * rhs.y;
    float ny = w * rhs.y - x * rhs.z + y * rhs.w + z * rhs.x;
    float nz = w * rhs.z + x * rhs.y - y * rhs.x + z * rhs.w;
    w = nw;
    x = nx;
    y = ny;
    z = nz;
  }

  static Quaternion multiply(Quaternion lhs, Quaternion rhs) {
    Quaternion result = lhs.copy();
    result.multiplyRight(rhs);
    return result;
  }

  static Quaternion fromAngularVelocity(float wx, float wy, float wz, float dtSeconds) {
    float magnitude = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
    if (magnitude < 1.0e-6f || dtSeconds <= 0.0f) {
      return new Quaternion();
    }

    float angle = magnitude * dtSeconds;
    float halfAngle = angle * 0.5f;
    float scale = (float) Math.sin(halfAngle) / magnitude;
    return new Quaternion(
        (float) Math.cos(halfAngle),
        wx * scale,
        wy * scale,
        wz * scale
    );
  }

  float[] rotate(float vx, float vy, float vz) {
    float ix = w * vx + y * vz - z * vy;
    float iy = w * vy + z * vx - x * vz;
    float iz = w * vz + x * vy - y * vx;
    float iw = -x * vx - y * vy - z * vz;

    return new float[]{
        ix * w + iw * -x + iy * -z - iz * -y,
        iy * w + iw * -y + iz * -x - ix * -z,
        iz * w + iw * -z + ix * -y - iy * -x
    };
  }

  float[] inverseRotate(float vx, float vy, float vz) {
    return conjugated().rotate(vx, vy, vz);
  }

  float[] toColumnMajorMatrix3() {
    float xx = x * x;
    float yy = y * y;
    float zz = z * z;
    float xy = x * y;
    float xz = x * z;
    float yz = y * z;
    float wx = w * x;
    float wy = w * y;
    float wz = w * z;

    return new float[]{
        1.0f - 2.0f * (yy + zz),
        2.0f * (xy + wz),
        2.0f * (xz - wy),
        2.0f * (xy - wz),
        1.0f - 2.0f * (xx + zz),
        2.0f * (yz + wx),
        2.0f * (xz + wy),
        2.0f * (yz - wx),
        1.0f - 2.0f * (xx + yy)
    };
  }
}
