package com.enricoros.nreal.player;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.enricoros.nreal.AppLog;

public final class VrVideoSurfaceView extends GLSurfaceView implements Vr180Renderer.RenderInvalidator {
  private static final String TAG = "VrVideoSurfaceView";

  private final Vr180Renderer renderer;
  private boolean released = false;

  public VrVideoSurfaceView(Context context, Vr180Renderer.SurfaceCallback surfaceCallback) {
    super(context);
    AppLog.d(TAG, "Creating VR video surface view");
    renderer = createRenderer(surfaceCallback);
  }

  public VrVideoSurfaceView(Context context, AttributeSet attrs) {
    super(context, attrs);
    AppLog.d(TAG, "Creating VR video surface view from XML attributes");
    renderer = createRenderer(new Vr180Renderer.SurfaceCallback() {
      @Override
      public void onVideoSurfaceCreated(int surfaceIndex, Surface surface) {
      }

      @Override
      public void onVideoSurfaceDestroyed(int surfaceIndex, Surface surface) {
      }

      @Override
      public void onVideoFrameAvailable(int surfaceIndex) {
      }
    });
  }

  public void setActiveVideoSurfaceIndex(int surfaceIndex) {
    if (released) {
      AppLog.d(TAG, () -> "Ignoring active surface change after release: index=" + surfaceIndex);
      return;
    }
    AppLog.d(TAG, () -> "Queue active surface change: index=" + surfaceIndex);
    queueEvent(() -> renderer.setActiveSurfaceIndex(surfaceIndex));
  }

  public void setHeadRotationMatrix(float[] matrix) {
    renderer.setHeadRotationMatrix(matrix);
  }

  public void setZoom(float zoom) {
    AppLog.d(TAG, () -> "Set zoom: " + zoom);
    renderer.setZoom(zoom);
  }

  public void setViewOffsetDegrees(float centerYawDegrees, float horizonPitchDegrees) {
    AppLog.d(TAG, () -> "Set view offset: centerYaw=" + centerYawDegrees
        + ", horizonPitch=" + horizonPitchDegrees);
    renderer.setViewOffsetDegrees(centerYawDegrees, horizonPitchDegrees);
  }

  public void setProjectionMode(int projectionMode) {
    AppLog.d(TAG, () -> "Set projection mode: " + projectionMode);
    renderer.setProjectionMode(projectionMode);
  }

  public boolean isStereoOutput() {
    return renderer.isStereoOutput();
  }

  public float getRenderFramesPerSecond() { return renderer.getRenderFramesPerSecond(); }

  public void releaseRenderer() {
    if (released) {
      AppLog.d(TAG, "releaseRenderer ignored: already released");
      return;
    }
    AppLog.i(TAG, "Queue renderer release");
    released = true;
    queueEvent(renderer::release);
  }

  @Override
  public void requestRenderFrame() {
    if (!released) requestRender();
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    super.surfaceCreated(holder);
    requestOutputFrameRate();
  }

  public void requestOutputFrameRate() {
    Surface surface = getHolder().getSurface();
    if (!released && surface != null && surface.isValid()) {
      // Head-tracked rendering can adapt to the compositor's cadence; this is not
      // the fixed frame rate of the underlying movie.
      if (Build.VERSION.SDK_INT >= 31) {
        surface.setFrameRate(90f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            Surface.CHANGE_FRAME_RATE_ALWAYS);
      } else {
        surface.setFrameRate(90f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
      }
    }
  }

  private Vr180Renderer createRenderer(Vr180Renderer.SurfaceCallback surfaceCallback) {
    AppLog.d(TAG, "Creating VR180 renderer");
    setEGLContextClientVersion(2);
    Vr180Renderer renderer = new Vr180Renderer(surfaceCallback);
    renderer.setRenderInvalidator(this);
    setRenderer(renderer);
    // The renderer owns its deadlines and supplies EGL presentation timestamps.
    // Do not add a separate timer that can queue render requests while swaps are blocked.
    setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    return renderer;
  }
}
