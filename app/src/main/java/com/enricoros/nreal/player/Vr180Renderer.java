package com.enricoros.nreal.player;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class Vr180Renderer implements android.opengl.GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
  public interface SurfaceCallback {
    void onVideoSurfaceCreated(Surface surface);

    void onVideoSurfaceDestroyed(Surface surface);
  }

  public interface RenderInvalidator {
    void requestRenderFrame();
  }

  private static final float BASE_HORIZONTAL_FOV_DEGREES = 40.605104f;
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
          "  if (abs(lon) > HALF_PI) {\n" +
          "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
          "    return;\n" +
          "  }\n" +
          "  float lat = clamp(asin(clamp(dir.y, -1.0, 1.0)) + uViewOffset.y, -HALF_PI, HALF_PI);\n" +
          "  float localU = lon / PI + 0.5;\n" +
          "  float localV = 0.5 + lat / PI;\n" +
          "  vec2 sourceUv = vec2(eye * 0.5 + localU * 0.5, localV);\n" +
          "  vec4 transformedUv = uVideoTransform * vec4(sourceUv, 0.0, 1.0);\n" +
          "  gl_FragColor = texture2D(uTexture, transformedUv.xy);\n" +
          "}\n";

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final SurfaceCallback surfaceCallback;
  private final FloatBuffer vertexBuffer;
  private final float[] videoTransform = new float[16];
  private final float[] headRotationMatrix = new float[]{
      1.0f, 0.0f, 0.0f,
      0.0f, 1.0f, 0.0f,
      0.0f, 0.0f, 1.0f
  };
  private final Object frameLock = new Object();
  private final Object orientationLock = new Object();

  private RenderInvalidator invalidator;
  private int program = 0;
  private int textureId = 0;
  private int aPosition = -1;
  private int uTexture = -1;
  private int uHeadRotation = -1;
  private int uVideoTransform = -1;
  private int uTanHalfFov = -1;
  private int uViewOffset = -1;
  private int uStereoOutput = -1;
  private SurfaceTexture surfaceTexture;
  private Surface surface;
  private boolean frameAvailable = false;
  private int surfaceWidth = 1;
  private int surfaceHeight = 1;
  private long lastRenderRequestElapsedMs = 0L;
  private float zoom = 1.0f;
  private float centerYawRadians = 0.0f;
  private float horizonPitchRadians = 0.0f;

  public Vr180Renderer(SurfaceCallback surfaceCallback) {
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
    setIdentity(videoTransform);
  }

  public void setRenderInvalidator(RenderInvalidator invalidator) {
    this.invalidator = invalidator;
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
    this.zoom = Math.max(0.60f, Math.min(1.80f, zoom));
    requestRender(false);
  }

  public void setViewOffsetDegrees(float centerYawDegrees, float horizonPitchDegrees) {
    centerYawRadians = (float) Math.toRadians(Math.max(-45.0f, Math.min(45.0f, centerYawDegrees)));
    horizonPitchRadians = (float) Math.toRadians(Math.max(-30.0f, Math.min(30.0f, horizonPitchDegrees)));
    requestRender(false);
  }

  public boolean isStereoOutput() {
    return shouldRenderStereo(surfaceWidth, surfaceHeight);
  }

  @Override
  public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
    aPosition = GLES20.glGetAttribLocation(program, "aPosition");
    uTexture = GLES20.glGetUniformLocation(program, "uTexture");
    uHeadRotation = GLES20.glGetUniformLocation(program, "uHeadRotation");
    uVideoTransform = GLES20.glGetUniformLocation(program, "uVideoTransform");
    uTanHalfFov = GLES20.glGetUniformLocation(program, "uTanHalfFov");
    uViewOffset = GLES20.glGetUniformLocation(program, "uViewOffset");
    uStereoOutput = GLES20.glGetUniformLocation(program, "uStereoOutput");

    int[] textures = new int[1];
    GLES20.glGenTextures(1, textures, 0);
    textureId = textures[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

    surfaceTexture = new SurfaceTexture(textureId);
    surfaceTexture.setOnFrameAvailableListener(this);
    surface = new Surface(surfaceTexture);
    Surface createdSurface = surface;
    mainHandler.post(() -> surfaceCallback.onVideoSurfaceCreated(createdSurface));
  }

  @Override
  public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
    surfaceWidth = Math.max(1, width);
    surfaceHeight = Math.max(1, height);
    GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
  }

  @Override
  public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    if (program == 0 || surfaceTexture == null) {
      return;
    }

    synchronized (frameLock) {
      if (frameAvailable) {
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(videoTransform);
        frameAvailable = false;
      }
    }

    GLES20.glUseProgram(program);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
    GLES20.glUniform1i(uTexture, 0);
    GLES20.glUniformMatrix4fv(uVideoTransform, 1, false, videoTransform, 0);

    float[] rotation = new float[9];
    synchronized (orientationLock) {
      System.arraycopy(headRotationMatrix, 0, rotation, 0, 9);
    }
    GLES20.glUniformMatrix3fv(uHeadRotation, 1, false, rotation, 0);

    float eyeAspect = shouldRenderStereo(surfaceWidth, surfaceHeight)
        ? (surfaceWidth * 0.5f) / surfaceHeight
        : (float) surfaceWidth / surfaceHeight;
    float tanHalfHorizontal = (float) Math.tan(Math.toRadians(BASE_HORIZONTAL_FOV_DEGREES * 0.5f)) / zoom;
    float tanHalfVertical = tanHalfHorizontal / Math.max(0.1f, eyeAspect);
    GLES20.glUniform2f(uTanHalfFov, tanHalfHorizontal, tanHalfVertical);
    GLES20.glUniform2f(uViewOffset, centerYawRadians, horizonPitchRadians);
    GLES20.glUniform1i(uStereoOutput, shouldRenderStereo(surfaceWidth, surfaceHeight) ? 1 : 0);

    vertexBuffer.position(0);
    GLES20.glEnableVertexAttribArray(aPosition);
    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    GLES20.glDisableVertexAttribArray(aPosition);
  }

  @Override
  public void onFrameAvailable(SurfaceTexture surfaceTexture) {
    synchronized (frameLock) {
      frameAvailable = true;
    }
    requestRender(false);
  }

  public void release() {
    Surface releasedSurface = surface;
    if (releasedSurface != null) {
      mainHandler.post(() -> surfaceCallback.onVideoSurfaceDestroyed(releasedSurface));
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (surfaceTexture != null) {
      surfaceTexture.release();
      surfaceTexture = null;
    }
    if (textureId != 0) {
      GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
      textureId = 0;
    }
    if (program != 0) {
      GLES20.glDeleteProgram(program);
      program = 0;
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
      GLES20.glDeleteProgram(program);
      throw new IllegalStateException("Could not link VR180 shader program: " + log);
    }
    GLES20.glDeleteShader(vertexShader);
    GLES20.glDeleteShader(fragmentShader);
    return program;
  }

  private static int compileShader(int type, String source) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
    if (compileStatus[0] == 0) {
      String log = GLES20.glGetShaderInfoLog(shader);
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
