package com.enricoros.nreal.player;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import com.enricoros.nreal.AppLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class Vr180Renderer implements android.opengl.GLSurfaceView.Renderer {
  public interface SurfaceCallback {
    void onVideoSurfaceCreated(int surfaceIndex, Surface surface);

    void onVideoSurfaceDestroyed(int surfaceIndex, Surface surface);

    void onVideoFrameAvailable(int surfaceIndex);
  }

  public interface RenderInvalidator {
    void requestRenderFrame();
  }

  public static final int VIDEO_SURFACE_COUNT = 3;
  private static final float BASE_HORIZONTAL_FOV_DEGREES = 40.605104f;
  public static final int PROJECTION_EQUIRECT_VR180 = 0;
  public static final int PROJECTION_FISHEYE_VR190 = 1;
  public static final int PROJECTION_FISHEYE_VR200 = 2;
  private static final String TAG = "Vr180Renderer";
  private static final long MIN_RENDER_INTERVAL_MS = 16L;
  private static final String VERTEX_SHADER =
      "attribute vec2 aPosition;\n" +
          "varying vec2 vScreenUv;\n" +
          "void main() {\n" +
          "  vScreenUv = aPosition * 0.5 + 0.5;\n" +
          "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
          "}\n";

  private static final String FRAGMENT_SHADER =
      "#extension GL_OES_EGL_image_external : require\n" +
          "precision highp float;\n" +
          "uniform samplerExternalOES uTexture;\n" +
          "uniform mat3 uHeadRotation;\n" +
          "uniform mat4 uVideoTransform;\n" +
          "uniform vec2 uTanHalfFov;\n" +
          "uniform vec2 uViewOffset;\n" +
          "uniform int uStereoOutput;\n" +
          "uniform int uProjectionMode;\n" +
          "varying vec2 vScreenUv;\n" +
          "const float PI = 3.141592653589793;\n" +
          "const float HALF_PI = 1.5707963267948966;\n" +
          "void main() {\n" +
          "  float eye = 0.0;\n" +
          "  vec2 eyeUv = vScreenUv;\n" +
          "  if (uStereoOutput == 1) {\n" +
          "    eye = step(0.5, vScreenUv.x);\n" +
          "    eyeUv.x = mix(vScreenUv.x * 2.0, (vScreenUv.x - 0.5) * 2.0, eye);\n" +
          "  }\n" +
          "  vec2 ndc = eyeUv * 2.0 - 1.0;\n" +
          "  vec3 headRay = normalize(vec3(ndc.x * uTanHalfFov.x, ndc.y * uTanHalfFov.y, -1.0));\n" +
          "  vec3 dir = normalize(uHeadRotation * headRay);\n" +
          "  float lon = atan(dir.x, -dir.z) + uViewOffset.x;\n" +
          "  float lat = clamp(asin(clamp(dir.y, -1.0, 1.0)) + uViewOffset.y, -HALF_PI, HALF_PI);\n" +
          "  float localU;\n" +
          "  float localV;\n" +
          "  if (uProjectionMode == 0) {\n" +
          "    if (abs(lon) > HALF_PI) {\n" +
          "      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
          "      return;\n" +
          "    }\n" +
          "    localU = lon / PI + 0.5;\n" +
          "    localV = 0.5 + lat / PI;\n" +
          "  } else {\n" +
          "    float maxAngle = HALF_PI;\n" +
          "    if (uProjectionMode == 1) {\n" +
          "      maxAngle = radians(95.0);\n" +
          "    } else if (uProjectionMode == 2) {\n" +
          "      maxAngle = radians(100.0);\n" +
          "    }\n" +
          "    vec3 lensDir = normalize(vec3(sin(lon) * cos(lat), sin(lat), -cos(lon) * cos(lat)));\n" +
          "    float theta = acos(clamp(-lensDir.z, -1.0, 1.0));\n" +
          "    if (theta > maxAngle) {\n" +
          "      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
          "      return;\n" +
          "    }\n" +
          "    float radial = theta / maxAngle;\n" +
          "    float xyLength = length(lensDir.xy);\n" +
          "    vec2 radialDir = xyLength > 0.0001 ? lensDir.xy / xyLength : vec2(0.0, 0.0);\n" +
          "    localU = 0.5 + radialDir.x * radial * 0.5;\n" +
          "    localV = 0.5 + radialDir.y * radial * 0.5;\n" +
          "  }\n" +
          "  vec2 sourceUv = vec2(eye * 0.5 + localU * 0.5, localV);\n" +
          "  vec4 transformedUv = uVideoTransform * vec4(sourceUv, 0.0, 1.0);\n" +
          "  gl_FragColor = texture2D(uTexture, transformedUv.xy);\n" +
          "}\n";

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final SurfaceCallback surfaceCallback;
  private final FloatBuffer vertexBuffer;
  private final float[][] videoTransforms = new float[VIDEO_SURFACE_COUNT][16];
  private final float[] headRotationMatrix = new float[]{
      1.0f, 0.0f, 0.0f,
      0.0f, 1.0f, 0.0f,
      0.0f, 0.0f, 1.0f
  };
  private final float[] renderHeadRotationMatrix = new float[9];
  private final Object frameLock = new Object();
  private final Object orientationLock = new Object();
  private final int[] textureIds = new int[VIDEO_SURFACE_COUNT];
  private final SurfaceTexture[] surfaceTextures = new SurfaceTexture[VIDEO_SURFACE_COUNT];
  private final Surface[] surfaces = new Surface[VIDEO_SURFACE_COUNT];
  private final boolean[] frameAvailable = new boolean[VIDEO_SURFACE_COUNT];

  private RenderInvalidator invalidator;
  private int program = 0;
  private int aPosition = -1;
  private int uTexture = -1;
  private int uHeadRotation = -1;
  private int uVideoTransform = -1;
  private int uTanHalfFov = -1;
  private int uViewOffset = -1;
  private int uStereoOutput = -1;
  private int uProjectionMode = -1;
  private int activeSurfaceIndex = 0;
  private int surfaceWidth = 1;
  private int surfaceHeight = 1;
  private long lastRenderRequestElapsedMs = 0L;
  private float zoom = 1.0f;
  private float centerYawRadians = 0.0f;
  private float horizonPitchRadians = 0.0f;
  private int projectionMode = PROJECTION_EQUIRECT_VR180;

  public Vr180Renderer(SurfaceCallback surfaceCallback) {
    AppLog.d(TAG, "Creating renderer");
    this.surfaceCallback = surfaceCallback;
    float[] vertices = new float[]{
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        1.0f, 1.0f
    };
    vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer();
    vertexBuffer.put(vertices).position(0);
    for (int i = 0; i < VIDEO_SURFACE_COUNT; i++) {
      setIdentity(videoTransforms[i]);
    }
  }

  public void setRenderInvalidator(RenderInvalidator invalidator) {
    AppLog.d(TAG, () -> "Set render invalidator: present=" + (invalidator != null));
    this.invalidator = invalidator;
  }

  public void setActiveSurfaceIndex(int surfaceIndex) {
    if (surfaceIndex < 0 || surfaceIndex >= VIDEO_SURFACE_COUNT) {
      AppLog.w(TAG, "Ignoring invalid active surface index " + surfaceIndex);
      return;
    }
    if (activeSurfaceIndex != surfaceIndex) {
      AppLog.i(TAG, () -> "Active surface changed: " + activeSurfaceIndex + " -> " + surfaceIndex);
    }
    activeSurfaceIndex = surfaceIndex;
    requestRender(false);
  }

  public void setHeadRotationMatrix(float[] matrix) {
    if (matrix == null || matrix.length < 9) {
      return;
    }
    synchronized (orientationLock) {
      System.arraycopy(matrix, 0, headRotationMatrix, 0, 9);
    }
    requestRender(true);
  }

  public void setZoom(float zoom) {
    float clampedZoom = Math.max(0.60f, Math.min(1.80f, zoom));
    if (this.zoom != clampedZoom) {
      AppLog.d(TAG, () -> "Zoom changed: " + this.zoom + " -> " + clampedZoom);
    }
    this.zoom = clampedZoom;
    requestRender(false);
  }

  public void setViewOffsetDegrees(float centerYawDegrees, float horizonPitchDegrees) {
    AppLog.d(TAG, () -> "View offset changed: centerYaw=" + centerYawDegrees
        + ", horizonPitch=" + horizonPitchDegrees);
    centerYawRadians = (float) Math.toRadians(Math.max(-45.0f, Math.min(45.0f, centerYawDegrees)));
    horizonPitchRadians = (float) Math.toRadians(Math.max(-30.0f, Math.min(30.0f, horizonPitchDegrees)));
    requestRender(false);
  }

  public void setProjectionMode(int projectionMode) {
    int nextProjectionMode = projectionMode;
    if (projectionMode < PROJECTION_EQUIRECT_VR180 || projectionMode > PROJECTION_FISHEYE_VR200) {
      AppLog.w(TAG, "Invalid projection mode " + projectionMode + "; falling back to VR180");
      nextProjectionMode = PROJECTION_EQUIRECT_VR180;
    }
    if (this.projectionMode != nextProjectionMode) {
      final int previousProjectionMode = this.projectionMode;
      final int selectedProjectionMode = nextProjectionMode;
      AppLog.i(TAG, () -> "Projection mode changed: " + previousProjectionMode + " -> " + selectedProjectionMode);
    }
    this.projectionMode = nextProjectionMode;
    requestRender(false);
  }

  public boolean isStereoOutput() {
    return shouldRenderStereo(surfaceWidth, surfaceHeight);
  }

  @Override
  public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
    AppLog.i(TAG, "GL surface created");
    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
    aPosition = GLES20.glGetAttribLocation(program, "aPosition");
    uTexture = GLES20.glGetUniformLocation(program, "uTexture");
    uHeadRotation = GLES20.glGetUniformLocation(program, "uHeadRotation");
    uVideoTransform = GLES20.glGetUniformLocation(program, "uVideoTransform");
    uTanHalfFov = GLES20.glGetUniformLocation(program, "uTanHalfFov");
    uViewOffset = GLES20.glGetUniformLocation(program, "uViewOffset");
    uStereoOutput = GLES20.glGetUniformLocation(program, "uStereoOutput");
    uProjectionMode = GLES20.glGetUniformLocation(program, "uProjectionMode");

    GLES20.glGenTextures(VIDEO_SURFACE_COUNT, textureIds, 0);
    for (int i = 0; i < VIDEO_SURFACE_COUNT; i++) {
      final int surfaceIndex = i;
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[i]);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

      SurfaceTexture surfaceTexture = new SurfaceTexture(textureIds[i]);
      surfaceTexture.setOnFrameAvailableListener(ignored -> onFrameAvailable(surfaceIndex));
      surfaceTextures[i] = surfaceTexture;
      surfaces[i] = new Surface(surfaceTexture);
      Surface createdSurface = surfaces[i];
      AppLog.i(TAG, () -> "Created video texture/surface: index=" + surfaceIndex
          + ", textureId=" + textureIds[surfaceIndex]);
      mainHandler.post(() -> surfaceCallback.onVideoSurfaceCreated(surfaceIndex, createdSurface));
    }
  }

  @Override
  public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
    surfaceWidth = Math.max(1, width);
    surfaceHeight = Math.max(1, height);
    AppLog.i(TAG, () -> "GL surface changed: width=" + surfaceWidth
        + ", height=" + surfaceHeight
        + ", stereo=" + shouldRenderStereo(surfaceWidth, surfaceHeight));
    GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
  }

  @Override
  public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    if (program == 0) {
      return;
    }

    updateAvailableVideoFrames();

    GLES20.glUseProgram(program);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[activeSurfaceIndex]);
    GLES20.glUniform1i(uTexture, 0);
    GLES20.glUniformMatrix4fv(uVideoTransform, 1, false, videoTransforms[activeSurfaceIndex], 0);

    synchronized (orientationLock) {
      System.arraycopy(headRotationMatrix, 0, renderHeadRotationMatrix, 0, 9);
    }
    GLES20.glUniformMatrix3fv(uHeadRotation, 1, false, renderHeadRotationMatrix, 0);

    float eyeAspect = shouldRenderStereo(surfaceWidth, surfaceHeight)
        ? (surfaceWidth * 0.5f) / surfaceHeight
        : (float) surfaceWidth / surfaceHeight;
    float tanHalfHorizontal = (float) Math.tan(Math.toRadians(BASE_HORIZONTAL_FOV_DEGREES * 0.5f)) / zoom;
    float tanHalfVertical = tanHalfHorizontal / Math.max(0.1f, eyeAspect);
    GLES20.glUniform2f(uTanHalfFov, tanHalfHorizontal, tanHalfVertical);
    GLES20.glUniform2f(uViewOffset, centerYawRadians, horizonPitchRadians);
    GLES20.glUniform1i(uStereoOutput, shouldRenderStereo(surfaceWidth, surfaceHeight) ? 1 : 0);
    GLES20.glUniform1i(uProjectionMode, projectionMode);

    vertexBuffer.position(0);
    GLES20.glEnableVertexAttribArray(aPosition);
    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    GLES20.glDisableVertexAttribArray(aPosition);
  }

  public void release() {
    AppLog.i(TAG, "Releasing renderer GL resources");
    for (int i = 0; i < VIDEO_SURFACE_COUNT; i++) {
      Surface releasedSurface = surfaces[i];
      if (releasedSurface != null) {
        final int surfaceIndex = i;
        AppLog.i(TAG, () -> "Destroying video surface: index=" + surfaceIndex);
        mainHandler.post(() -> surfaceCallback.onVideoSurfaceDestroyed(surfaceIndex, releasedSurface));
      }
      if (surfaces[i] != null) {
        surfaces[i].release();
        surfaces[i] = null;
      }
      if (surfaceTextures[i] != null) {
        surfaceTextures[i].release();
        surfaceTextures[i] = null;
      }
    }
    GLES20.glDeleteTextures(VIDEO_SURFACE_COUNT, textureIds, 0);
    for (int i = 0; i < VIDEO_SURFACE_COUNT; i++) {
      textureIds[i] = 0;
      frameAvailable[i] = false;
      setIdentity(videoTransforms[i]);
    }
    if (program != 0) {
      AppLog.d(TAG, () -> "Deleting GL program: id=" + program);
      GLES20.glDeleteProgram(program);
      program = 0;
    }
  }

  private void onFrameAvailable(int surfaceIndex) {
    synchronized (frameLock) {
      if (surfaceIndex >= 0 && surfaceIndex < VIDEO_SURFACE_COUNT) {
        frameAvailable[surfaceIndex] = true;
      }
    }
    requestRender(false);
  }

  private void updateAvailableVideoFrames() {
    boolean[] shouldUpdate = new boolean[VIDEO_SURFACE_COUNT];
    synchronized (frameLock) {
      for (int i = 0; i < VIDEO_SURFACE_COUNT; i++) {
        shouldUpdate[i] = frameAvailable[i];
        frameAvailable[i] = false;
      }
    }
    for (int i = 0; i < VIDEO_SURFACE_COUNT; i++) {
      SurfaceTexture surfaceTexture = surfaceTextures[i];
      if (!shouldUpdate[i] || surfaceTexture == null) {
        continue;
      }
      surfaceTexture.updateTexImage();
      surfaceTexture.getTransformMatrix(videoTransforms[i]);
      final int surfaceIndex = i;
      mainHandler.post(() -> surfaceCallback.onVideoFrameAvailable(surfaceIndex));
    }
  }

  private void requestRender(boolean throttle) {
    RenderInvalidator localInvalidator = invalidator;
    if (localInvalidator != null) {
      long now = SystemClock.elapsedRealtime();
      if (throttle && now - lastRenderRequestElapsedMs < MIN_RENDER_INTERVAL_MS) {
        return;
      }
      lastRenderRequestElapsedMs = now;
      localInvalidator.requestRenderFrame();
    }
  }

  private static boolean shouldRenderStereo(int width, int height) {
    if (height <= 0) {
      return false;
    }
    return width >= 3000 || ((float) width / height) >= 2.4f;
  }

  private static int buildProgram(String vertexShaderSource, String fragmentShaderSource) {
    AppLog.d(TAG, "Building VR180 shader program");
    int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource);
    int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource);
    int program = GLES20.glCreateProgram();
    GLES20.glAttachShader(program, vertexShader);
    GLES20.glAttachShader(program, fragmentShader);
    GLES20.glLinkProgram(program);
    int[] linkStatus = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
    if (linkStatus[0] == 0) {
      String log = GLES20.glGetProgramInfoLog(program);
      AppLog.e(TAG, "Could not link VR180 shader program: " + log);
      GLES20.glDeleteProgram(program);
      throw new IllegalStateException("Could not link VR180 shader program: " + log);
    }
    GLES20.glDeleteShader(vertexShader);
    GLES20.glDeleteShader(fragmentShader);
    return program;
  }

  private static int compileShader(int type, String source) {
    AppLog.d(TAG, () -> "Compiling shader: type=" + type);
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
    if (compileStatus[0] == 0) {
      String log = GLES20.glGetShaderInfoLog(shader);
      AppLog.e(TAG, "Could not compile VR180 shader: " + log);
      GLES20.glDeleteShader(shader);
      throw new IllegalStateException("Could not compile VR180 shader: " + log);
    }
    return shader;
  }

  private static void setIdentity(float[] matrix) {
    for (int i = 0; i < matrix.length; i++) {
      matrix[i] = 0.0f;
    }
    matrix[0] = 1.0f;
    matrix[5] = 1.0f;
    matrix[10] = 1.0f;
    matrix[15] = 1.0f;
  }
}
