package com.tdiqsy.app;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/** 统一按压微交互：可点击控件按下时轻微缩小、抬起回弹，不拦截原有点击/长按逻辑 */
public class TouchEffects {

    private static final float PRESS_SCALE = 0.95f;

    private TouchEffects() {}

    /** 对整棵视图树中所有可点击控件套用按压特效 */
    public static void apply(View root) {
        if (root == null) return;
        applyRecursive(root);
    }

    private static void applyRecursive(View view) {
        if (view.isClickable()) {
            attach(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyRecursive(group.getChildAt(i));
            }
        }
    }

    private static void attach(final View v) {
        if (v.getTag(R.id.tag_touch_effect) != null) return;
        v.setTag(R.id.tag_touch_effect, Boolean.TRUE);
        final float baseScale = v.getScaleX();
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(baseScale * PRESS_SCALE).scaleY(baseScale * PRESS_SCALE)
                                .setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(baseScale).scaleY(baseScale).setDuration(150).start();
                        break;
                }
                return false;
            }
        });
    }
}