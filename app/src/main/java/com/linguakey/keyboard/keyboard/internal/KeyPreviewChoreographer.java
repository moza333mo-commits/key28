package com.linguakey.keyboard.keyboard.internal;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayDeque;
import java.util.HashMap;

import com.linguakey.keyboard.keyboard.Key;
import com.linguakey.keyboard.latin.common.CoordinateUtils;
import com.linguakey.keyboard.latin.utils.ViewLayoutUtils;

/**
 * Ultra-fast, zero-stutter Google Gboard-style key preview choreographer:
 * - Zero XML AnimatorInflater allocations on key press.
 * - Authentic Gboard rounded pill/teardrop proportions floating cleanly above the pressed key,
 *   clamped horizontally within screen bounds so edge keys never clip.
 * - Hardware-accelerated elevation shadow (4dp) set once per pooled view.
 */
public final class KeyPreviewChoreographer {
    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();
    private final HashMap<Key, KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();
    private final KeyPreviewDrawParams mParams;

    public KeyPreviewChoreographer(final KeyPreviewDrawParams params) {
        mParams = params;
    }

    public KeyPreviewView getKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView != null) {
            return keyPreviewView;
        }
        keyPreviewView = mFreeKeyPreviewViews.poll();
        if (keyPreviewView != null) {
            return keyPreviewView;
        }
        final Context context = placerView.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        keyPreviewView = new KeyPreviewView(context, null);
        keyPreviewView.setElevation(4f * density);
        placerView.addView(keyPreviewView, ViewLayoutUtils.newLayoutParam(placerView, 0, 0));
        return keyPreviewView;
    }

    public void dismissKeyPreview(final Key key, final boolean withAnimation) {
        if (key == null) {
            return;
        }
        final KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView == null) {
            return;
        }
        keyPreviewView.setTag(null);
        keyPreviewView.setVisibility(View.INVISIBLE);
        mFreeKeyPreviewViews.add(keyPreviewView);
    }

    public void placeAndShowKeyPreview(
            final Key key,
            final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams,
            final int[] keyboardOrigin,
            final ViewGroup placerView,
            final boolean withAnimation,
            final int backgroundColor
    ) {
        final KeyPreviewView keyPreviewView = getKeyPreviewView(key, placerView);
        placeKeyPreview(key, keyPreviewView, iconsSet, drawParams, keyboardOrigin, placerView, backgroundColor);
        showKeyPreview(key, keyPreviewView, false);
    }

    private void placeKeyPreview(
            final Key key,
            final KeyPreviewView keyPreviewView,
            final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams,
            final int[] originCoords,
            final ViewGroup placerView,
            final int backgroundColor
    ) {
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams, backgroundColor);
        final float density = keyPreviewView.getResources().getDisplayMetrics().density;
        final int keyWidth = key.getWidth();
        final int keyHeight = key.getHeight();

        // Exact Google Gboard default pill proportions: slightly wider than the key, 50dp-54dp tall
        final int previewWidth = Math.max((int) (38f * density), Math.min((int) (54f * density), keyWidth + (int) (8f * density)));
        final int previewHeight = Math.max((int) (48f * density), Math.min((int) (54f * density), keyHeight + (int) (10f * density)));

        mParams.setGeometry(keyPreviewView);

        int previewX = key.getX() - (previewWidth - keyWidth) / 2
                + CoordinateUtils.x(originCoords);
        final int edgeMargin = (int) (3f * density);
        final int maxPlacerW = placerView != null ? placerView.getWidth() : 0;
        if (maxPlacerW > previewWidth + edgeMargin * 2) {
            previewX = Math.max(edgeMargin, Math.min(previewX, maxPlacerW - previewWidth - edgeMargin));
        }

        final int overlapIntoKey = (int) (4f * density);
        final int previewY = key.getY() - previewHeight + overlapIntoKey
                + CoordinateUtils.y(originCoords);

        ViewLayoutUtils.placeViewAt(
                keyPreviewView, previewX, previewY, previewWidth, previewHeight);
    }

    void showKeyPreview(
            final Key key,
            final KeyPreviewView keyPreviewView,
            final boolean withAnimation
    ) {
        keyPreviewView.setVisibility(View.VISIBLE);
        mShowingKeyPreviewViews.put(key, keyPreviewView);
    }
}
