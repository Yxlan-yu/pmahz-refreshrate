package com.pmahz;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.example.liquidglass.LiquidGlassView;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class GlassBottomNavigationView extends BottomNavigationView {

    private LiquidGlassView glass;

    public GlassBottomNavigationView(Context context) {
        super(context);
        initGlass(context);
    }

    public GlassBottomNavigationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initGlass(context);
    }

    public GlassBottomNavigationView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initGlass(context);
    }

    private void initGlass(Context context) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            LiquidGlassView g = new LiquidGlassView(context, null);
            g.setEnableDynamicBackground(true);
            g.setCornerRadius(0f);
            addView(g, 0, new FrameLayout.LayoutParams(-1, -1));
            glass = g;
            super.setBackground(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable t) {
            glass = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (glass != null) {
            ViewParent p = getParent();
            if (p instanceof View) glass.setBackdropSource((View) p);
        }
    }

    @Override
    public void setBackground(Drawable background) {
        if (glass == null) {
            super.setBackground(background);
            return;
        }
        float r = 0f;
        if (background instanceof GradientDrawable) {
            r = ((GradientDrawable) background).getCornerRadius();
        }
        glass.setCornerRadius(r);
        super.setBackground(new ColorDrawable(Color.TRANSPARENT));
    }

    @Override
    public void setBackgroundColor(int color) {
        if (glass == null) {
            super.setBackgroundColor(color);
            return;
        }
        glass.setCornerRadius(0f);
        super.setBackground(new ColorDrawable(Color.TRANSPARENT));
    }
}
