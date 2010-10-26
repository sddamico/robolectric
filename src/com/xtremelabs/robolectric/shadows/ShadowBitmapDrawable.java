package com.xtremelabs.robolectric.shadows;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import com.xtremelabs.robolectric.util.Implementation;
import com.xtremelabs.robolectric.util.Implements;
import com.xtremelabs.robolectric.util.RealObject;

@SuppressWarnings({"UnusedDeclaration"})
@Implements(BitmapDrawable.class)
public class ShadowBitmapDrawable extends ShadowDrawable {
    public int loadedFromResourceId;

    @RealObject private BitmapDrawable realBitmapDrawable;

    @Implementation
    public void draw(Canvas canvas) {
        canvas.drawBitmap(realBitmapDrawable.getBitmap(), 0, 0, new Paint());
    }
}
