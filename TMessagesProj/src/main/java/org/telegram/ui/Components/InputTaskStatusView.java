package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.owpengram.InputTaskStatusController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * Compact one-line bar shown directly above the chat composer: circular peer
 * avatar + a softly shimmering task-status line with elapsed wait time.
 * Not a message bubble, and not inside the input island.
 */
public class InputTaskStatusView extends FrameLayout {

    public static final int HEIGHT_DP = 40;
    public static final int AVATAR_DP = 26;

    private final Theme.ResourcesProvider resourcesProvider;
    private final BackupImageView avatarImage;
    private final AvatarDrawable avatarDrawable;
    private final ShimmerTextView statusTextView;

    private String statusText;
    private long startedAtElapsedRealtime;
    private boolean shown;
    private float showProgress;
    private ValueAnimator visibilityAnimator;
    private Runnable heightListener;
    private final Runnable tickRunnable = this::onTick;

    public InputTaskStatusView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setClipToPadding(false);
        setClipChildren(false);
        setPadding(dp(16), 0, dp(16), 0);

        avatarDrawable = new AvatarDrawable();
        avatarDrawable.setTextSize(dp(12));

        avatarImage = new BackupImageView(context);
        avatarImage.setRoundRadius(dp(AVATAR_DP / 2));
        addView(avatarImage, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        statusTextView = new ShimmerTextView(context);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusTextView.setSingleLine(true);
        statusTextView.setEllipsize(TextUtils.TruncateAt.END);
        statusTextView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        statusTextView.setPadding(0, 0, 0, 0);
        addView(statusTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, AVATAR_DP + 8, 0, 0, 0));

        updateColors();
        setAlpha(0f);
        setVisibility(GONE);
        showProgress = 0f;
    }

    public void setHeightListener(Runnable listener) {
        heightListener = listener;
    }

    public void updateColors() {
        int color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        int fallback = Theme.getColor(Theme.key_chat_inTimeText, resourcesProvider);
        if (color == 0) {
            color = fallback;
        }
        // Keep the line readable on both dark and light chat backgrounds.
        statusTextView.setTextColor(color);
        statusTextView.setShimmerColors(color);
        invalidate();
    }

    public void bindPeer(TLRPC.User user, TLRPC.Chat chat) {
        if (user != null) {
            avatarDrawable.setInfo(user);
            avatarImage.setForUserOrChat(user, avatarDrawable);
        } else if (chat != null) {
            avatarDrawable.setInfo(chat);
            avatarImage.setForUserOrChat(chat, avatarDrawable);
        } else {
            avatarImage.setImage((ImageLocation) null, null, avatarDrawable, null);
        }
    }

    public void showOrUpdate(String text, long startedAtElapsedRealtime) {
        this.statusText = TextUtils.isEmpty(text) ? InputTaskStatusController.defaultStatusText() : text;
        this.startedAtElapsedRealtime = startedAtElapsedRealtime > 0 ? startedAtElapsedRealtime : SystemClock.elapsedRealtime();
        refreshStatusText();
        if (!shown) {
            shown = true;
            animateVisibility(true);
        }
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        AndroidUtilities.runOnUIThread(tickRunnable, 1000);
    }

    public void hide() {
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        if (!shown && showProgress == 0f) {
            if (getVisibility() != GONE) {
                setVisibility(GONE);
                notifyHeightChanged();
            }
            return;
        }
        shown = false;
        animateVisibility(false);
    }

    public boolean isShown() {
        return shown;
    }

    /** Animated reserved height used to pad the message list. */
    public float getReservedHeight() {
        return dp(HEIGHT_DP) * showProgress;
    }

    private void animateVisibility(boolean show) {
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
            visibilityAnimator = null;
        }
        if (show) {
            setVisibility(VISIBLE);
            statusTextView.startShimmer();
        }
        float from = showProgress;
        float to = show ? 1f : 0f;
        if (Math.abs(from - to) < 0.01f) {
            showProgress = to;
            setAlpha(to);
            setTranslationY(dp(6) * (1f - to));
            if (!show) {
                statusTextView.stopShimmer();
                setVisibility(GONE);
            }
            notifyHeightChanged();
            return;
        }
        visibilityAnimator = ValueAnimator.ofFloat(from, to);
        visibilityAnimator.setDuration(200);
        visibilityAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        visibilityAnimator.addUpdateListener(a -> {
            showProgress = (float) a.getAnimatedValue();
            setAlpha(showProgress);
            setTranslationY(dp(6) * (1f - showProgress));
            notifyHeightChanged();
        });
        visibilityAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                visibilityAnimator = null;
                showProgress = to;
                setAlpha(to);
                setTranslationY(dp(6) * (1f - to));
                if (!show) {
                    statusTextView.stopShimmer();
                    setVisibility(GONE);
                }
                notifyHeightChanged();
            }
        });
        visibilityAnimator.start();
    }

    private void notifyHeightChanged() {
        if (heightListener != null) {
            heightListener.run();
        }
    }

    private void onTick() {
        if (!shown) {
            return;
        }
        refreshStatusText();
        AndroidUtilities.runOnUIThread(tickRunnable, 1000);
    }

    private void refreshStatusText() {
        long elapsed = SystemClock.elapsedRealtime() - startedAtElapsedRealtime;
        statusTextView.setText(InputTaskStatusController.formatStatusLine(statusText, elapsed));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        statusTextView.stopShimmer();
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
            visibilityAnimator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateColors();
        if (shown) {
            statusTextView.startShimmer();
            AndroidUtilities.cancelRunOnUIThread(tickRunnable);
            AndroidUtilities.runOnUIThread(tickRunnable, 1000);
        }
    }

    /**
     * Soft moving highlight plus a gentle alpha pulse. Not a hard blink.
     */
    private static class ShimmerTextView extends TextView {

        private final Matrix shaderMatrix = new Matrix();
        private LinearGradient shader;
        private int baseColor;
        private float shimmerTx;
        private float pulse;
        private ValueAnimator shimmerAnimator;
        private ValueAnimator pulseAnimator;
        private boolean running;

        ShimmerTextView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        void setShimmerColors(int color) {
            baseColor = color;
            int highlight = ColorUtils_lerpWhite(color, 0.45f);
            shader = new LinearGradient(
                0, 0, dp(80), 0,
                new int[]{color, highlight, color},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
            );
            getPaint().setShader(shader);
            invalidate();
        }

        void startShimmer() {
            if (running) {
                return;
            }
            running = true;
            if (shimmerAnimator != null) {
                shimmerAnimator.cancel();
            }
            shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
            shimmerAnimator.setDuration(1800);
            shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
            shimmerAnimator.setInterpolator(new LinearInterpolator());
            shimmerAnimator.addUpdateListener(a -> {
                shimmerTx = (float) a.getAnimatedValue();
                applyShader();
            });
            shimmerAnimator.start();

            if (pulseAnimator != null) {
                pulseAnimator.cancel();
            }
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
            pulseAnimator.setDuration(1600);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            pulseAnimator.addUpdateListener(a -> {
                pulse = (float) a.getAnimatedValue();
                setAlpha(0.72f + 0.28f * pulse);
            });
            pulseAnimator.start();
        }

        void stopShimmer() {
            running = false;
            if (shimmerAnimator != null) {
                shimmerAnimator.cancel();
                shimmerAnimator = null;
            }
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            setAlpha(1f);
            getPaint().setShader(shader);
            applyShader();
        }

        private void applyShader() {
            if (shader == null) {
                return;
            }
            float width = Math.max(getWidth(), dp(120));
            float travel = width + dp(80);
            shaderMatrix.reset();
            shaderMatrix.setTranslate(shimmerTx * travel - dp(80), 0);
            shader.setLocalMatrix(shaderMatrix);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (baseColor != 0) {
                setShimmerColors(baseColor);
            }
            applyShader();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopShimmer();
        }
    }

    private static int ColorUtils_lerpWhite(int color, float amount) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.min(255, (int) (r + (255 - r) * amount));
        g = Math.min(255, (int) (g + (255 - g) * amount));
        b = Math.min(255, (int) (b + (255 - b) * amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
