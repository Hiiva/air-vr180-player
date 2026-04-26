package com.enricoros.nreal.player;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

public final class VrVideoSurfaceView extends GLSurfaceView implements Vr180Renderer.RenderInvalidator {
  private final Vr180Renderer renderer;
  private boolean released = false;

  public VrVideoSurfaceView(Context context, Vr180Renderer.SurfaceCallback surfaceCallback) {
    super(context);
    renderer = createRenderer(surfaceCallback);
  }

  public VrVideoSurfaceView(Context context, AttributeSet attrs) {
    super(context, attrs);
    renderer = createRenderer(new Vr180Renderer.SurfaceCallback() {
      @Override
      public void onVideoSurfaceCreated(android.view.Surface surface) {
      }

      @Override
      public void onVideoSurfaceDestroyed(android.view.Surface surface) {
      }
    });
  }

  public void setHeadRotationMatrix(float[] matrix) {
    renderer.setHeadRotationMatrix(matrix);
  }

  public void setZoom(float zoom) {
    renderer.setZoom(zoom);
  }

  public void setViewOffsetDegrees(float centerYawDegrees, float horizonPitchDegrees) {
    renderer.setViewOffsetDegrees(centerYawDegrees, horizonPitchDegrees);
  }

  public boolean isStereoOutput() {
    return renderer.isStereoOutput();
  }

  public void releaseRenderer() {
    if (released) {
      return;
    }
    released = true;
    queueEvent(renderer::release);
  }

  @Override
  public void requestRenderFrame() {
    requestRender();
  }

  private Vr180Renderer createRenderer(Vr180Renderer.SurfaceCallback surfaceCallback) {
    setEGLContextClientVersion(2);
    Vr180Renderer renderer = new Vr180Renderer(surfaceCallback);
    renderer.setRenderInvalidator(this);
    setRenderer(renderer);
    setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    return renderer;
  }
}
