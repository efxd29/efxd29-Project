package com.termux.app.terminal;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import java.io.File;

/**
 * Native macOS-Terminal-style title bar shown above the terminal:
 *
 *   [red] [yellow] [green]      [folder icon] folder - command - COLSxROWS
 *
 * Everything shown is read live from the real terminal session:
 *  - folder  : current working directory of the shell (HOME_LABEL when in $HOME)
 *  - command : title set by the shell via OSC 0 (zsh precmd/preexec send "-zsh" or the command name)
 *  - size    : TerminalEmulator.mColumns x mRows (changes on rotate, keyboard, font zoom)
 *
 * Buttons: red = close current session, yellow = minimize (move app to background),
 * green = toggle fullscreen (hide system bars).
 */
public final class TermuxTitleBar {

    /** Shown as the folder name when the shell is in $HOME (like the user name in macOS). */
    public static final String HOME_LABEL = "efxd29";
    /** Shown while the shell is idle or no title has been reported yet. */
    private static final String IDLE_NAME = "-zsh";

    private static final int COLOR_BAR = 0xFF35353A;
    private static final int COLOR_TEXT = 0xFFA0A0A8;
    private static final int COLOR_RED = 0xFFFE5E56;
    private static final int COLOR_YELLOW = 0xFFFEBC2E;
    private static final int COLOR_GREEN = 0xFF28C840;

    private static final long REFRESH_MS = 250;

    private static final String LOG_TAG = "TermuxTitleBar";

    private final TermuxActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mTitleView;
    private String mLastText = "";
    private boolean mFullscreen = false;

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private TermuxTitleBar(TermuxActivity activity) {
        mActivity = activity;
    }

    /** Insert the title bar at the top of the activity root view. Call after setContentView(). */
    public static void install(TermuxActivity activity) {
        try {
            new TermuxTitleBar(activity).build();
        } catch (Exception e) {
            // Never break the terminal if the bar fails.
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install title bar", e);
        }
    }

    private void build() {
        ViewGroup root = mActivity.findViewById(R.id.activity_termux_root_view);
        if (root == null) return;

        FrameLayout bar = new FrameLayout(mActivity);
        bar.setBackgroundColor(COLOR_BAR);

        // --- traffic-light buttons (left) ---
        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams buttonsParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START | Gravity.CENTER_VERTICAL);
        buttonsParams.setMarginStart(dp(2));
        bar.addView(buttons, buttonsParams);

        buttons.addView(createButton(COLOR_RED, "Close", v -> closeSession()));
        buttons.addView(createButton(COLOR_YELLOW, "Minimize", v -> mActivity.moveTaskToBack(true)));
        buttons.addView(createButton(COLOR_GREEN, "Fullscreen", v -> toggleFullscreen()));

        // --- folder icon + title (center) ---
        LinearLayout center = new LinearLayout(mActivity);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        // Keep the title clear of the buttons on the left.
        centerParams.setMarginStart(dp(74));
        centerParams.setMarginEnd(dp(10));
        bar.addView(center, centerParams);

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_title_folder_home);
        icon.setAdjustViewBounds(true);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(16));
        iconParams.setMarginEnd(dp(6));
        center.addView(icon, iconParams);

        mTitleView = new TextView(mActivity);
        mTitleView.setTextColor(COLOR_TEXT);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mTitleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mTitleView.setSingleLine(true);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        mTitleView.setIncludeFontPadding(false);
        center.addView(mTitleView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bar, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        // Let the status bar blend into the title bar.
        try {
            mActivity.getWindow().setStatusBarColor(COLOR_BAR);
        } catch (Exception ignored) {
        }

        bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mHandler.removeCallbacks(mTicker);
                mHandler.post(mTicker);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mHandler.removeCallbacks(mTicker);
            }
        });
    }

    private View createButton(int color, String description, View.OnClickListener listener) {
        FrameLayout slot = new FrameLayout(mActivity);
        slot.setLayoutParams(new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.MATCH_PARENT));
        slot.setContentDescription(description);
        slot.setClickable(true);
        slot.setOnClickListener(listener);

        View dot = new View(mActivity);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        dot.setBackground(shape);
        slot.addView(dot, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER));
        return slot;
    }

    // ------------------------------------------------------------------ actions

    private void closeSession() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) {
            mActivity.finishActivityIfNotFinishing();
            return;
        }
        if (session.isRunning()) session.finishIfRunning();
        // Removes the session and shows the next one; finishes the activity if it was the last.
        mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
    }

    private void toggleFullscreen() {
        Window window = mActivity.getWindow();
        try {
            WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(window, window.getDecorView());
            if (mFullscreen) {
                controller.show(WindowInsetsCompat.Type.systemBars());
            } else {
                controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
            mFullscreen = !mFullscreen;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to toggle fullscreen", e);
        }
    }

    // ------------------------------------------------------------------ live title

    private void refresh() {
        if (mTitleView == null || !mActivity.isVisible()) return;

        String folder = HOME_LABEL;
        String command = IDLE_NAME;
        String size = "";

        TerminalSession session = mActivity.getCurrentSession();
        if (session != null) {
            String cwd = null;
            try {
                cwd = session.getCwd();
            } catch (Exception ignored) {
            }
            folder = folderName(cwd);

            String title = session.getTitle();
            if (title != null) {
                title = title.trim();
                if (!title.isEmpty()) command = title.length() > 40 ? title.substring(0, 40) : title;
            }

            TerminalEmulator emulator = session.getEmulator();
            if (emulator != null) size = emulator.mColumns + "x" + emulator.mRows;
        }

        StringBuilder text = new StringBuilder(folder).append(" — ").append(command);
        if (!size.isEmpty()) text.append(" — ").append(size);

        String result = text.toString();
        if (!result.equals(mLastText)) {
            mLastText = result;
            mTitleView.setText(result);
        }
    }

    private static String folderName(String cwd) {
        if (cwd == null || cwd.isEmpty()) return HOME_LABEL;
        while (cwd.length() > 1 && cwd.endsWith("/")) cwd = cwd.substring(0, cwd.length() - 1);
        if (cwd.equals(TermuxConstants.TERMUX_HOME_DIR_PATH)
            || cwd.equals(TermuxConstants.TERMUX_FILES_DIR_PATH + "/home")) {
            return HOME_LABEL;
        }
        if (cwd.equals("/")) return "/";
        String name = new File(cwd).getName();
        return name.isEmpty() ? "/" : name;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            mActivity.getResources().getDisplayMetrics()));
    }
}
