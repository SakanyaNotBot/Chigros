package com.wuying.phigros.game;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public class GameGLSurfaceView extends GLSurfaceView {

    private GestureDetector gestureDetector;
    private GameRenderer renderer;
    public GameGLSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
    }

    public void enableMultisample() {
        setEGLConfigChooser(new MsaaConfigChooser());
    }

    /** Disable VSync for maximum frame rate. */
    public void enableUnlimitedFrameRate() {
        setEGLContextFactory(new EGLContextFactory() {
            @Override
            public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
                int[] attribs = {
                    0x3098, 2,
                    EGL10.EGL_NONE
                };
                return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs);
            }

            @Override
            public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
                egl.eglDestroyContext(display, context);
            }
        });

        setEGLWindowSurfaceFactory(new EGLWindowSurfaceFactory() {
            @Override
            public javax.microedition.khronos.egl.EGLSurface createWindowSurface(
                    EGL10 egl, EGLDisplay display, EGLConfig config, Object nativeWindow) {
                javax.microedition.khronos.egl.EGLSurface surface = null;
                try {
                    surface = egl.eglCreateWindowSurface(display, config, nativeWindow, null);
                    try {
                        java.lang.reflect.Method method = egl.getClass().getMethod(
                            "eglSwapInterval", EGLDisplay.class, int.class);
                        method.invoke(egl, display, 0);
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    android.util.Log.e("GameGLSurfaceView", "Failed to create window surface", e);
                }
                return surface;
            }

            @Override
            public void destroySurface(EGL10 egl, EGLDisplay display,
                                      javax.microedition.khronos.egl.EGLSurface surface) {
                egl.eglDestroySurface(display, surface);
            }
        });
    }

    public void bindToRenderer(@NonNull GameRenderer renderer) {
        this.renderer = renderer;
        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                final float x = e.getX();
                final float y = e.getY();
                queueEvent(() -> {
                    if (GameGLSurfaceView.this.renderer != null) {
                        GameGLSurfaceView.this.renderer.onSingleTap(x, y);
                    }
                });
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                final float x = e.getX();
                final float y = e.getY();
                queueEvent(() -> {
                    if (GameGLSurfaceView.this.renderer != null) {
                        GameGLSurfaceView.this.renderer.onDoubleTap(x, y);
                    }
                });
                return true;
            }
        });

        setOnTouchListener((v, event) -> {
            if (GameGLSurfaceView.this.renderer != null && event != null) {
                MotionEvent copy = MotionEvent.obtain(event);
                queueEvent(() -> {
                    try {
                        if (GameGLSurfaceView.this.renderer != null) {
                            GameGLSurfaceView.this.renderer.onTouchEvent(copy);
                        }
                    } finally {
                        copy.recycle();
                    }
                });
            }
            return gestureDetector != null && gestureDetector.onTouchEvent(event);
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector != null) {
            return gestureDetector.onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    private static final class MsaaConfigChooser implements GLSurfaceView.EGLConfigChooser {
        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            int[] samples = new int[]{8, 4, 2};
            for (int sampleCount : samples) {
                int[] attribs = new int[]{
                        EGL10.EGL_RED_SIZE, 8,
                        EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 16,
                        EGL10.EGL_RENDERABLE_TYPE, 4,
                        EGL10.EGL_SAMPLE_BUFFERS, 1,
                        EGL10.EGL_SAMPLES, sampleCount,
                        EGL10.EGL_NONE
                };
                if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) {
                    return configs[0];
                }
            }
            int[] fallback = new int[]{
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_RENDERABLE_TYPE, 4,
                    EGL10.EGL_NONE
            };
            if (egl.eglChooseConfig(display, fallback, configs, 1, num) && num[0] > 0) {
                return configs[0];
            }
            return null;
        }
    }
}
