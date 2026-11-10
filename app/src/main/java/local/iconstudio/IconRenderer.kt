package local.iconstudio

import android.graphics.*

/** The preview and APK use this same renderer. Padding changes visible artwork size. */
object IconRenderer {
    fun render(source: Bitmap, shape: String, scale: Float, normalizeVisible: Boolean = false): Bitmap {
        val result=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result)
        val extent=192f*scale.coerceIn(0.6f,1f)
        var bounds=Rect(0,0,source.width,source.height)
        if(normalizeVisible){
            val pixels=IntArray(source.width*source.height);source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
            var left=source.width;var top=source.height;var right=-1;var bottom=-1
            for(y in 0 until source.height)for(x in 0 until source.width)if(Color.alpha(pixels[y*source.width+x])>=16){left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)}
            if(right>=left && bottom>=top)bounds=Rect(left,top,right+1,bottom+1)
        }
        val factor=minOf(extent/bounds.width(),extent/bounds.height())
        val w=bounds.width()*factor;val h=bounds.height()*factor
        val rect=RectF((192-w)/2,(192-h)/2,(192+w)/2,(192+h)/2)
        canvas.drawBitmap(source,bounds,rect,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if(shape!="Original") {
            val mask=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE}
            Canvas(mask).apply {
                if(shape=="Device") {
                    val adaptive=android.graphics.drawable.AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE),android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    adaptive.setBounds(0,0,192,192)
                    val path=Path(adaptive.iconMask)
                    path.transform(Matrix().apply {setRectToRect(RectF(0f,0f,192f,192f),rect,Matrix.ScaleToFit.FILL)})
                    drawPath(path,paint)
                } else if(shape=="Circle")drawOval(rect,paint)
                else drawRoundRect(rect,w*0.24f,h*0.24f,paint)
            }
            canvas.drawBitmap(mask,0f,0f,Paint(Paint.ANTI_ALIAS_FLAG).apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)})
            mask.recycle()
        }
        return result
    }
}
