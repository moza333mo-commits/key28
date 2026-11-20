package com.linguakey.keyboard.latin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import com.linguakey.keyboard.R;

import java.nio.ByteBuffer;

/**
 * High-Speed Screen Capture Engine for LinguaKey.
 * Ported and adapted from screen-translator-master's CaptureRepository.kt & ScreenInfoHolder.kt:
 *
 * 1. Reuses a single live MediaProjection + VirtualDisplay instance across captures by detaching
 *    the Surface when idle (pauseFrames: virtualDisplay.setSurface(null)) and re-attaching it on
 *    demand (resumeFrames: virtualDisplay.setSurface(imageReader.getSurface())).
 * 2. Uses physical Display.getRealMetrics() dimensions so 1 bitmap pixel == 1 physical screen pixel
 *    with zero vertical/horizontal coordinate drift.
 * 3. Protects in-flight permission/capture state from being cancelled when the system permission
 *    dialog briefly hides the IME window, retries initial blank VirtualDisplay buffers before
 *    declaring FLAG_SECURE, and waits for the IME window token to re-attach before delivering
 *    the captured screenshot.
 */
public final class LinguaKeyScreenCaptureManager {
    private static final String TAG = "LKScreenCapture";
    private static final long CAPTURE_FRAME_TIMEOUT_MS = 3200L;

    public interface CaptureCallback {
        void onScreenCaptured(Bitmap croppedBitmap, int cropTopY, int fullScreenWidth, int fullScreenHeight);
        void onCapturePreventedByDrm();
        void onCaptureError(String message);
    }

    private static LinguaKeyScreenCaptureManager sInstance;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private int mResultCode = Activity.RESULT_CANCELED;
    private Intent mMediaProjectionToken = null;
    private boolean mForegroundServiceReady = false;

    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mProjectionStopCallback;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private int mActiveDisplayWidth = 0;
    private int mActiveDisplayHeight = 0;

    private volatile boolean mCaptureOrPermissionInFlight = false;
    private boolean mAwaitingFrame = false;
    private int mBlankFrameRetryCount = 0;
    private View mPendingAnchorView;
    private int mPendingKeyboardTopY;
    private CaptureCallback mPendingCallback;
    private Runnable mFrameTimeoutRunnable;
    private Runnable mRetryPollRunnable;

    private LinguaKeyScreenCaptureManager(final Context context) {
        mContext = context.getApplicationContext();
    }

    public static synchronized LinguaKeyScreenCaptureManager getInstance(final Context context) {
        if (sInstance == null && context != null) {
            sInstance = new LinguaKeyScreenCaptureManager(context);
        }
        return sInstance;
    }

    public boolean hasToken() {
        return mResultCode == Activity.RESULT_OK && mMediaProjectionToken != null;
    }

    public boolean isCaptureInProgress() {
        return mCaptureOrPermissionInFlight || mAwaitingFrame;
    }

    public void updateAnchorViewIfActive(final View anchorView) {
        if (anchorView != null) {
            mPendingAnchorView = anchorView;
        }
    }

    public View getActiveAnchorView(final View fallback) {
        if (mPendingAnchorView != null && mPendingAnchorView.getWindowToken() != null) {
            return mPendingAnchorView;
        }
        return fallback;
    }

    public void onPermissionGranted(final int resultCode, final Intent token) {
        mResultCode = resultCode;
        mMediaProjectionToken = token;
        mCaptureOrPermissionInFlight = true;
        LinguaKeyScreenDiag.stage("PERMISSION", "MediaProjection permission granted resultCode=" + resultCode);
    }

    public void onPermissionDenied() {
        mCaptureOrPermissionInFlight = false;
        mAwaitingFrame = false;
        CaptureCallback cb = mPendingCallback;
        mPendingCallback = null;
        if (cb != null) {
            cb.onCaptureError("تم إلغاء إذن قراءة الشاشة");
        }
    }

    public void onProjectionServiceFailed() {
        clearResources();
        mForegroundServiceReady = false;
        mMediaProjectionToken = null;
        mCaptureOrPermissionInFlight = false;
        mAwaitingFrame = false;
        CaptureCallback cb = mPendingCallback;
        mPendingCallback = null;
        if (cb != null) {
            cb.onCaptureError("تعذر تشغيل خدمة التقاط الشاشة");
        }
    }

    public void onForegroundServiceReady() {
        mForegroundServiceReady = true;
        LinguaKeyScreenDiag.stage("FGS", "LinguaKeyMediaProjectionService foreground ready");
        if (mPendingCallback != null && mPendingAnchorView != null) {
            // Wait 260ms for the system MediaProjection dialog to finish closing and keyboard focus to settle
            final View anchor = mPendingAnchorView;
            final int kbdTop = mPendingKeyboardTopY;
            final CaptureCallback cb = mPendingCallback;
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startOrResumeCaptureInternal(getActiveAnchorView(anchor), kbdTop, cb);
                }
            }, 260L);
        } else {
            mCaptureOrPermissionInFlight = false;
        }
    }

    public void requestScreenCapture(
            final View anchorView,
            final int keyboardTopY,
            final CaptureCallback callback
    ) {
        mPendingAnchorView = anchorView;
        mPendingKeyboardTopY = keyboardTopY;
        mPendingCallback = callback;
        mCaptureOrPermissionInFlight = true;
        mBlankFrameRetryCount = 0;

        DisplayMetrics realMetrics = getRealDisplayMetrics();
        if (mVirtualDisplay != null
                && (realMetrics.widthPixels != mActiveDisplayWidth
                || realMetrics.heightPixels != mActiveDisplayHeight)) {
            recreateVirtualDisplayForOrientation(realMetrics);
        }

        if (mMediaProjection != null && mVirtualDisplay != null && mImageReader != null) {
            startOrResumeCaptureInternal(anchorView, keyboardTopY, callback);
            return;
        }

        if (hasToken() && mForegroundServiceReady) {
            startOrResumeCaptureInternal(anchorView, keyboardTopY, callback);
            return;
        }

        if (hasToken() && !mForegroundServiceReady) {
            LinguaKeyMediaProjectionService.startService(mContext);
            return;
        }

        // First-time in session: launch transparent permission activity
        try {
            Intent permIntent = new Intent(mContext, ScreenCapturePermissionActivity.class);
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            mContext.startActivity(permIntent);
        } catch (Throwable e) {
            Log.e(TAG, "Unable to start ScreenCapturePermissionActivity", e);
            mCaptureOrPermissionInFlight = false;
            if (callback != null) {
                callback.onCaptureError("تعذر طلب إذن التقاط الشاشة");
            }
        }
    }

    private void startOrResumeCaptureInternal(
            final View anchorView,
            final int keyboardTopY,
            final CaptureCallback callback
    ) {
        cancelTimeoutAndPoll();
        mPendingAnchorView = anchorView;
        mPendingKeyboardTopY = keyboardTopY;
        mPendingCallback = callback;
        mCaptureOrPermissionInFlight = true;
        mBlankFrameRetryCount = 0;

        if (mMediaProjection == null || mVirtualDisplay == null || mImageReader == null) {
            if (!initializeProjectionPipeline()) {
                return;
            }
        } else {
            try {
                Image stale = mImageReader.acquireLatestImage();
                if (stale != null) stale.close();
            } catch (Throwable ignored) {
            }
            resumeFrames();
        }

        mAwaitingFrame = true;

        if (anchorView != null) {
            anchorView.postInvalidate();
        }

        mRetryPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mAwaitingFrame || mImageReader == null) return;
                if (!tryAcquireAndDeliverFrame()) {
                    if (anchorView != null) anchorView.postInvalidate();
                    mMainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (mAwaitingFrame && mImageReader != null) {
                                tryAcquireAndDeliverFrame();
                            }
                        }
                    }, 95L);
                }
            }
        };
        mMainHandler.postDelayed(mRetryPollRunnable, 55L);

        mFrameTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mAwaitingFrame) return;
                if (tryAcquireAndDeliverFrame()) return;

                Log.w(TAG, "No capture frame within timeout — resetting projection");
                mAwaitingFrame = false;
                mCaptureOrPermissionInFlight = false;
                PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                boolean interactive = pm == null || pm.isInteractive();
                clearResources();
                if (!interactive) {
                    mMediaProjectionToken = null;
                }
                CaptureCallback cb = mPendingCallback;
                mPendingCallback = null;
                if (cb != null) {
                    cb.onCaptureError("انتهت مهلة التقاط الشاشة — اضغط مجدداً للمحاولة");
                }
            }
        };
        mMainHandler.postDelayed(mFrameTimeoutRunnable, CAPTURE_FRAME_TIMEOUT_MS);
    }

    private boolean initializeProjectionPipeline() {
        clearResources();
        if (!hasToken()) {
            try {
                Intent permIntent = new Intent(mContext, ScreenCapturePermissionActivity.class);
                permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                mContext.startActivity(permIntent);
            } catch (Throwable e) {
                Log.e(TAG, "Error launching permission activity", e);
                mCaptureOrPermissionInFlight = false;
            }
            return false;
        }

        try {
            DisplayMetrics realMetrics = getRealDisplayMetrics();
            final int width = realMetrics.widthPixels;
            final int height = realMetrics.heightPixels;
            final int dpi = realMetrics.densityDpi;
            mActiveDisplayWidth = width;
            mActiveDisplayHeight = height;

            MediaProjectionManager mpm =
                    (MediaProjectionManager) mContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mMediaProjection = mpm.getMediaProjection(mResultCode, mMediaProjectionToken);
            if (mMediaProjection == null) {
                mMediaProjectionToken = null;
                requestScreenCapture(mPendingAnchorView, mPendingKeyboardTopY, mPendingCallback);
                return false;
            }

            mProjectionStopCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w(TAG, "MediaProjection stopped by system");
                    clearResources();
                    mMediaProjectionToken = null;
                    mForegroundServiceReady = false;
                    mCaptureOrPermissionInFlight = false;
                    LinguaKeyMediaProjectionService.stopService(mContext);
                }
            };
            mMediaProjection.registerCallback(mProjectionStopCallback, mMainHandler);

            mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(final ImageReader reader) {
                    if (mAwaitingFrame) {
                        tryAcquireAndDeliverFrame();
                    } else {
                        try {
                            Image extra = reader.acquireLatestImage();
                            if (extra != null) extra.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, mMainHandler);

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "LinguaKeyScreenTranslator",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(),
                    null,
                    mMainHandler
            );
            LinguaKeyScreenDiag.stage("CAPTURE", "VirtualDisplay created " + width + "x" + height + " dpi=" + dpi);

            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize MediaProjection pipeline", e);
            clearResources();
            mMediaProjectionToken = null;
            try {
                Intent permIntent = new Intent(mContext, ScreenCapturePermissionActivity.class);
                permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                mContext.startActivity(permIntent);
            } catch (Throwable ignored) {
                mCaptureOrPermissionInFlight = false;
            }
            return false;
        }
    }

    private void recreateVirtualDisplayForOrientation(final DisplayMetrics realMetrics) {
        if (mMediaProjection == null || mVirtualDisplay == null) return;
        try {
            if (mImageReader != null) {
                mImageReader.setOnImageAvailableListener(null, null);
                mImageReader.close();
            }
            mActiveDisplayWidth = realMetrics.widthPixels;
            mActiveDisplayHeight = realMetrics.heightPixels;
            mImageReader = ImageReader.newInstance(
                    mActiveDisplayWidth,
                    mActiveDisplayHeight,
                    PixelFormat.RGBA_8888,
                    2
            );
            mVirtualDisplay.resize(
                    mActiveDisplayWidth,
                    mActiveDisplayHeight,
                    realMetrics.densityDpi
            );
            mVirtualDisplay.setSurface(mImageReader.getSurface());
            mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(final ImageReader reader) {
                    if (mAwaitingFrame) {
                        tryAcquireAndDeliverFrame();
                    } else {
                        try {
                            Image extra = reader.acquireLatestImage();
                            if (extra != null) extra.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, mMainHandler);
        } catch (Throwable e) {
            Log.w(TAG, "VirtualDisplay resize failed, resetting", e);
            clearResources();
        }
    }

    private boolean tryAcquireAndDeliverFrame() {
        if (!mAwaitingFrame || mImageReader == null) return false;
        Image capturedImage = null;
        try {
            capturedImage = mImageReader.acquireLatestImage();
            if (capturedImage == null) {
                return false;
            }

            final int width = capturedImage.getWidth();
            final int height = capturedImage.getHeight();
            Image.Plane[] planes = capturedImage.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            int paddedWidth = width + (pixelStride > 0 ? (rowPadding / pixelStride) : 0);
            Bitmap rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            rawBitmap.copyPixelsFromBuffer(buffer);

            // Measure exact top edge of R.id.keyboard_container (NOT mInputView, which is MATCH_PARENT full-screen!)
            int kbdTopY = mPendingKeyboardTopY;
            if (mPendingAnchorView != null) {
                View kbdContainer = mPendingAnchorView.findViewById(R.id.keyboard_container);
                if (kbdContainer != null && kbdContainer.getHeight() > 0) {
                    int[] kbdLoc = new int[2];
                    kbdContainer.getLocationOnScreen(kbdLoc);
                    if (kbdLoc[1] > dpToPx(100)) {
                        kbdTopY = kbdLoc[1];
                    }
                }
            }
            if (kbdTopY <= dpToPx(100) || kbdTopY > height) {
                kbdTopY = (int) (height * 0.58f);
            }

            int statusBarH = getStatusBarHeight();
            int cropTopY = Math.max(statusBarH, dpToPx(24));
            int cropHeight = Math.max(dpToPx(80), kbdTopY - cropTopY - dpToPx(2));
            if (cropTopY + cropHeight > height) {
                cropHeight = height - cropTopY;
            }

            Bitmap cropped = Bitmap.createBitmap(rawBitmap, 0, cropTopY, width, cropHeight);
            if (rawBitmap != cropped) {
                rawBitmap.recycle();
            }

            Bitmap cleanBitmap = removeAlphaChannel(cropped);
            if (cropped != cleanBitmap) {
                cropped.recycle();
            }

            if (isCapturePrevented(cleanBitmap)) {
                cleanBitmap.recycle();
                // On some OEMs, the first 1-2 buffers right after VirtualDisplay creation are solid black
                // before SurfaceFlinger composites the real display. Retry up to 3 times before declaring DRM!
                if (mBlankFrameRetryCount < 3) {
                    mBlankFrameRetryCount++;
                    if (mPendingAnchorView != null) {
                        mPendingAnchorView.postInvalidate();
                    }
                    mMainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (mAwaitingFrame && mImageReader != null) {
                                tryAcquireAndDeliverFrame();
                            }
                        }
                    }, 90L);
                    return false;
                }

                mAwaitingFrame = false;
                mCaptureOrPermissionInFlight = false;
                cancelTimeoutAndPoll();
                pauseFrames();
                final CaptureCallback cb = mPendingCallback;
                mPendingCallback = null;
                if (cb != null) {
                    cb.onCapturePreventedByDrm();
                }
                return true;
            }

            mAwaitingFrame = false;
            cancelTimeoutAndPoll();
            pauseFrames();

            final CaptureCallback cb = mPendingCallback;
            mPendingCallback = null;

            LinguaKeyScreenDiag.stage("CAPTURE", "Frame captured & cropped: " + cleanBitmap.getWidth() + "x" + cleanBitmap.getHeight() + " cropTopY=" + cropTopY + " kbdTopY=" + kbdTopY);
            deliverCapturedBitmapWhenWindowReady(cleanBitmap, cropTopY, width, height, cb, 0);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Error processing captured frame", e);
            mAwaitingFrame = false;
            mCaptureOrPermissionInFlight = false;
            cancelTimeoutAndPoll();
            pauseFrames();
            CaptureCallback cb = mPendingCallback;
            mPendingCallback = null;
            if (cb != null) {
                cb.onCaptureError("حدث خطأ أثناء معالجة صورة الشاشة");
            }
            return false;
        } finally {
            if (capturedImage != null) {
                try {
                    capturedImage.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void deliverCapturedBitmapWhenWindowReady(
            final Bitmap cleanBitmap,
            final int cropTopY,
            final int width,
            final int height,
            final CaptureCallback cb,
            final int attempt
    ) {
        if (cb == null) {
            mCaptureOrPermissionInFlight = false;
            cleanBitmap.recycle();
            return;
        }
        // If the keyboard window token is still re-attaching after the system permission dialog closed, wait up to 600ms
        if (mPendingAnchorView != null && mPendingAnchorView.getWindowToken() == null && attempt < 6) {
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    deliverCapturedBitmapWhenWindowReady(cleanBitmap, cropTopY, width, height, cb, attempt + 1);
                }
            }, 100L);
            return;
        }
        mCaptureOrPermissionInFlight = false;
        cb.onScreenCaptured(cleanBitmap, cropTopY, width, height);
    }

    private void resumeFrames() {
        if (mVirtualDisplay != null && mImageReader != null) {
            try {
                mVirtualDisplay.setSurface(mImageReader.getSurface());
            } catch (Throwable e) {
                Log.w(TAG, "resumeFrames failed, reinitializing", e);
                clearResources();
                initializeProjectionPipeline();
            }
        }
    }

    private void pauseFrames() {
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.setSurface(null);
            } catch (Throwable ignored) {
            }
        }
    }

    private Bitmap removeAlphaChannel(final Bitmap original) {
        Bitmap opaque = Bitmap.createBitmap(original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(opaque);
        Paint paint = new Paint();
        canvas.drawBitmap(original, 0f, 0f, paint);
        return opaque;
    }

    private boolean isCapturePrevented(final Bitmap bitmap) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int checkW = (w * 3) / 5;
            int checkH = (h * 3) / 5;
            if (checkW <= 10 || checkH <= 10) return false;

            int startX = w / 5;
            int startY = h / 5;
            int firstPixel = bitmap.getPixel(startX, startY);
            int stepX = Math.max(4, checkW / 12);
            int stepY = Math.max(4, checkH / 12);

            for (int y = startY; y < startY + checkH; y += stepY) {
                for (int x = startX; x < startX + checkW; x += stepX) {
                    if (bitmap.getPixel(x, y) != firstPixel) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public DisplayMetrics getRealDisplayMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    display.getRealMetrics(dm);
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return dm;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return mContext.getResources().getDisplayMetrics();
    }

    private int getStatusBarHeight() {
        try {
            int resId = mContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) {
                return mContext.getResources().getDimensionPixelSize(resId);
            }
        } catch (Throwable ignored) {
        }
        return dpToPx(26);
    }

    private int dpToPx(final int dp) {
        return (int) (dp * mContext.getResources().getDisplayMetrics().density);
    }

    private void cancelTimeoutAndPoll() {
        if (mFrameTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mFrameTimeoutRunnable);
            mFrameTimeoutRunnable = null;
        }
        if (mRetryPollRunnable != null) {
            mMainHandler.removeCallbacks(mRetryPollRunnable);
            mRetryPollRunnable = null;
        }
    }

    public void clearResources() {
        cancelTimeoutAndPoll();
        mAwaitingFrame = false;
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.release();
            } catch (Throwable ignored) {
            }
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            try {
                mImageReader.setOnImageAvailableListener(null, null);
                mImageReader.close();
            } catch (Throwable ignored) {
            }
            mImageReader = null;
        }
        if (mMediaProjection != null) {
            if (mProjectionStopCallback != null) {
                try {
                    mMediaProjection.unregisterCallback(mProjectionStopCallback);
                } catch (Throwable ignored) {
                }
                mProjectionStopCallback = null;
            }
            try {
                mMediaProjection.stop();
            } catch (Throwable ignored) {
            }
            mMediaProjection = null;
        }
    }
}
