package com.oshi.messenger.ui.components;

import androidx.annotation.Nullable;
import androidx.compose.ui.graphics.AndroidCanvas;

/**
 * __CHAT_BACKDROP_BLUR_2026_11_21__ Lets {@code FrostedChatBar.kt} record a
 * node's content into a {@link android.graphics.RenderNode}.
 *
 * <p>Compose 1.5 (this app's BOM) has no public way to draw
 * {@code ContentDrawScope.drawContent()} into another canvas: the
 * {@code GraphicsLayer.record} API arrived in 1.7. Compose itself does it by
 * pointing its {@code AndroidCanvas} wrapper at a different native canvas for
 * the duration of a draw ({@code CanvasHolder.drawInto}). The setter is
 * {@code @PublishedApi internal} in Kotlin, i.e. public in bytecode, so it is
 * reachable from Java and nowhere else - hence this one Java file.
 *
 * <p>Every caller must handle a {@code null} return (the canvas is not an
 * {@code AndroidCanvas}, e.g. a future Compose) by drawing normally: the frost
 * then falls back to the tinted bar, never to a blank screen.
 */
public final class ComposeCanvasSwap {
    private ComposeCanvasSwap() {}

    /**
     * Points {@code composeCanvas} at {@code target} and returns the native
     * canvas it drew into before, or {@code null} when it cannot be swapped.
     */
    @Nullable
    public static android.graphics.Canvas swap(
            androidx.compose.ui.graphics.Canvas composeCanvas,
            android.graphics.Canvas target) {
        if (!(composeCanvas instanceof AndroidCanvas)) return null;
        try {
            AndroidCanvas canvas = (AndroidCanvas) composeCanvas;
            android.graphics.Canvas previous = canvas.getInternalCanvas();
            canvas.setInternalCanvas(target);
            return previous;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Restores what {@link #swap} replaced. */
    public static void restore(
            androidx.compose.ui.graphics.Canvas composeCanvas,
            android.graphics.Canvas previous) {
        if (composeCanvas instanceof AndroidCanvas) {
            ((AndroidCanvas) composeCanvas).setInternalCanvas(previous);
        }
    }
}
