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

  void setConjugated(Quaternion other) {
    w = other.w;
    x = -other.x;
    y = -other.y;
    z = -other.z;
  }

  void normalize() {
    float length = (float) Math.sqrt(w * w + x * x + y * y + z * z);
    if (!Float.isFinite(length) || length < 1.0e-6f) {
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

  void setFromAngularVelocity(float wx, float wy, float wz, float dtSeconds) {
    float magnitude = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
    if (magnitude < 1.0e-6f || dtSeconds <= 0.0f) {
      setIdentity();
      return;
    }

    float angle = magnitude * dtSeconds;
    float halfAngle = angle * 0.5f;
    float scale = (float) Math.sin(halfAngle) / magnitude;
    w = (float) Math.cos(halfAngle);
    x = wx * scale;
    y = wy * scale;
    z = wz * scale;
  }

  void setFromUnitVectors(
      float fromX,
      float fromY,
      float fromZ,
      float toX,
      float toY,
      float toZ) {
    float dot = Math.max(-1.0f, Math.min(1.0f,
        fromX * toX + fromY * toY + fromZ * toZ));
    if (dot < -0.999999f) {
      // The vectors are opposite. Pick a stable axis perpendicular to the source.
      if (Math.abs(fromX) < Math.abs(fromZ)) {
        x = 0.0f;
        y = -fromZ;
        z = fromY;
      } else {
        x = -fromY;
        y = fromX;
        z = 0.0f;
      }
      w = 0.0f;
      normalize();
      return;
    }

    w = 1.0f + dot;
    x = fromY * toZ - fromZ * toY;
    y = fromZ * toX - fromX * toZ;
    z = fromX * toY - fromY * toX;
    normalize();
  }

  void rotate(float vx, float vy, float vz, float[] out) {
    float tx = 2.0f * (y * vz - z * vy);
    float ty = 2.0f * (z * vx - x * vz);
    float tz = 2.0f * (x * vy - y * vx);
    out[0] = vx + w * tx + (y * tz - z * ty);
    out[1] = vy + w * ty + (z * tx - x * tz);
    out[2] = vz + w * tz + (x * ty - y * tx);
  }

  void inverseRotate(float vx, float vy, float vz, float[] out) {
    float rw = w;
    float rx = -x;
    float ry = -y;
    float rz = -z;

    float ix = rw * vx + ry * vz - rz * vy;
    float iy = rw * vy + rz * vx - rx * vz;
    float iz = rw * vz + rx * vy - ry * vx;
    float iw = -rx * vx - ry * vy - rz * vz;

    out[0] = ix * rw + iw * -rx + iy * -rz - iz * -ry;
    out[1] = iy * rw + iw * -ry + iz * -rx - ix * -rz;
    out[2] = iz * rw + iw * -rz + ix * -ry - iy * -rx;
  }

  void toColumnMajorMatrix3(float[] out) {
    float xx = x * x;
    float yy = y * y;
    float zz = z * z;
    float xy = x * y;
    float xz = x * z;
    float yz = y * z;
    float wx = w * x;
    float wy = w * y;
    float wz = w * z;

    out[0] = 1.0f - 2.0f * (yy + zz);
    out[1] = 2.0f * (xy + wz);
    out[2] = 2.0f * (xz - wy);
    out[3] = 2.0f * (xy - wz);
    out[4] = 1.0f - 2.0f * (xx + zz);
    out[5] = 2.0f * (yz + wx);
    out[6] = 2.0f * (xz + wy);
    out[7] = 2.0f * (yz - wx);
    out[8] = 1.0f - 2.0f * (xx + yy);
  }
}
