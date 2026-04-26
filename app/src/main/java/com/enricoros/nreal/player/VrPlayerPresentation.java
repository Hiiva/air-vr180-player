package com.enricoros.nreal.player;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Window;
import android.view.WindowManager;

public final class VrPlayerPresentation extends Presentation {
  private final Vr180Renderer.SurfaceCallback surfaceCallback;
  private VrVideoSurfaceView videoSurfaceView;
  private boolean rendererReleased = false;

  public VrPlayerPresentation(Context outerContext, Display display, Vr180Renderer.SurfaceCallback surfaceCallback) {
    super(outerContext, display);
    this.surfaceCallback = surfaceCallback;
  }

  @Override
  @SuppressWarnings("deprecation")
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Window window = getWindow();
    if (window != null) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    videoSurfaceView = new VrVideoSurfaceView(getContext(), surfaceCallback);
    setContentView(videoSurfaceView);
    videoSurfaceView.post(this::applyImmersiveMode);
  }

  public void setHeadRotationMatrix(float[] matrix) {
    if (videoSurfaceView != null) {
      videoSurfaceView.setHeadRotationMatrix(matrix);
    }
  }

  public void setZoom(float zoom) {
    if (videoSurfaceView != null) {
      videoSurfaceView.setZoom(zoom);
    }
  }

  public void setViewOffsetDegrees(float centerYawDegrees, float horizonPitchDegrees) {
    if (videoSurfaceView != null) {
      videoSurfaceView.setViewOffsetDegrees(centerYawDegrees, horizonPitchDegrees);
    }
  }

  public boolean isStereoOutput() {
    return videoSurfaceView != null && videoSurfaceView.isStereoOutput();
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (videoSurfaceView != null) {
      videoSurfaceView.onResume();
      videoSurfaceView.post(this::applyImmersiveMode);
    }
  }

  @Override
  protected void onStop() {
    releaseRenderer();
    super.onStop();
  }

  public void releaseRenderer() {
    if (rendererReleased) {
      return;
    }
    rendererReleased = true;
    if (videoSurfaceView != null) {
      videoSurfaceView.releaseRenderer();
      videoSurfaceView.onPause();
    }
  }

  @SuppressWarnings("deprecation")
  private void applyImmersiveMode() {
    Window window = getWindow();
    if (window == null || videoSurfaceView == null) {
      return;
    }

    window.setDecorFitsSystemWindows(false);
    WindowInsetsController controller = videoSurfaceView.getWindowInsetsController();
    if (controller != null) {
      controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
      controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      return;
    }

    View decorView = window.getDecorView();
    if (decorView != null) {
      decorView.setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      );
    }
  }
}
