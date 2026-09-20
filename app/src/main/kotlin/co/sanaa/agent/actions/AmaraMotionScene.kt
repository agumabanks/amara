package co.sanaa.agent.actions

import android.graphics.*
import co.sanaa.agent.modules.AmaraAdSpec
import kotlin.math.*

/** Shared timeline for preview frames and the encoded ad; no generated commercial claims. */
class AmaraMotionScene(private val ad: AmaraAdSpec, private val photos: List<Bitmap>) {
    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val FPS = 20
        const val SECONDS = 12
        const val CLOSE_AT = 10f
        val INK = Color.rgb(12, 28, 31)
        val GREEN = Color.rgb(65, 185, 153)
        val PAPER = Color.rgb(246, 248, 243)
        fun ctaWhatsApp(seconds: Float, hasPhone: Boolean) = hasPhone && (seconds / 3f).toInt() % 2 == 0
        fun galleryIndex(seconds: Float, count: Int): Int = if (count <= 1) 0 else ((seconds.coerceAtLeast(0f) / (CLOSE_AT / count)).toInt()).coerceAtMost(count - 1)
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private fun ease(t: Float): Float = 1f - (1f-t.coerceIn(0f,1f)).pow(3)
    private fun panel(c: Canvas, r: RectF, color: Int, radius: Float = 24f) {
        p.shader=null;p.color=color;c.drawRoundRect(r,radius,radius,p)
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, width: Float,
                     color: Int, bold: Boolean = false, alpha: Int = 255) {
        val weight=when { size>=60f -> 900;bold -> 700;size>=25f -> 500;else -> 300 }
        p.shader=null;p.typeface=if(android.os.Build.VERSION.SDK_INT>=28) Typeface.create(Typeface.SANS_SERIF,weight,false)
            else Typeface.create(if(weight>=700) "sans-serif-black" else if(weight>=500) "sans-serif-medium" else "sans-serif-light",Typeface.NORMAL)
        p.color=color;p.alpha=alpha;p.textSize=size
        while(p.measureText(value)>width && p.textSize>23f) p.textSize-=1f
        var shown=value
        while(p.measureText(shown)>width && shown.isNotEmpty()) shown=shown.dropLast(1)
        if(shown!=value) { while(p.measureText("$shown…")>width && shown.isNotEmpty()) shown=shown.dropLast(1); shown+="…" }
        c.drawText(shown,x,y,p);p.alpha=255
    }
    fun draw(c: Canvas, seconds: Float) {
        require(photos.isNotEmpty())
        val dark=ad.template=="showcase" || ad.background==2
        val bg=if(dark) INK else PAPER;val fg=if(dark) PAPER else INK
        c.drawColor(bg)
        if(seconds>=CLOSE_AT) { closing(c,seconds-CLOSE_AT);return }
        val enter=ease(seconds/.65f)
        p.color=GREEN;p.alpha=35
        when(ad.background) {
            1 -> { c.drawCircle(40f,570f,310f+sin(seconds)*16f,p);c.drawCircle(640f,100f,160f,p) }
            2 -> { c.drawRoundRect(RectF(-120f,250f,320f,980f),150f,150f,p);c.drawCircle(650f,180f,240f,p) }
            else -> c.drawCircle(650f,150f,220f+sin(seconds)*12f,p)
        };p.alpha=255
        text(c,ad.brand.uppercase(),54f,150f,21f,530f,if(dark) GREEN else INK)
        text(c,ad.headline,54f,238f+(1-enter)*24f,64f,540f,fg,true,(enter*255).toInt())
        // The image moves gently within its own card; product edges are never cropped.
        val card=if(ad.template=="poster") RectF(54f,310f,598f,778f) else RectF(54f,290f,598f,805f)
        val index=galleryIndex(seconds,photos.size)
        val segment=CLOSE_AT/photos.size
        val phase=(seconds-index*segment).coerceAtLeast(0f)
        val transition=if(index==0) 1f else ease(phase/.5f)
        fun photo(i:Int,dx:Float,alpha:Float) {
            c.save();c.translate(dx,0f)
            if(ad.template=="poster") c.rotate(-2f+sin(seconds*.8f)*.8f,card.centerX(),card.centerY())
            panel(c,RectF(card.left+3,card.top+10,card.right+3,card.bottom+10),Color.argb(24,0,0,0))
            panel(c,card,Color.WHITE)
            val bitmap=photos[i]
            val pulse=.94f+.025f*sin(seconds*.7f)
            val scale=min((card.width()-36)/bitmap.width,(card.height()-36)/bitmap.height)*pulse
            val w=bitmap.width*scale;val h=bitmap.height*scale
            p.color=Color.WHITE;p.alpha=(alpha*255).toInt()
            c.drawBitmap(bitmap,null,RectF(card.centerX()-w/2,card.centerY()-h/2,card.centerX()+w/2,card.centerY()+h/2),p)
            p.alpha=255;c.restore()
        }
        if(index>0 && transition<1) photo(index-1,-transition*660f,1f)
        photo(index,(1-transition)*660f,1f)
        if(photos.size>1) {
            for(i in photos.indices) panel(c,RectF(54f+i*34,828f,76f+i*34,833f),if(i==index) GREEN else Color.LTGRAY,3f)
        }
        text(c,ad.price,54f,906f,45f,544f,fg,true)
        val wa=ctaWhatsApp(seconds,ad.whatsapp!=null)
        val swap=ease((seconds%3f)/.4f)
        c.save();c.translate(0f,(1-swap)*12f)
        panel(c,RectF(54f,944f,598f,1065f),if(wa) GREEN else if(dark) PAPER else INK)
        val cc=if(wa || dark) INK else PAPER
        text(c,if(wa) "LET’S TALK ON WHATSAPP" else "SHOP / EXPLORE THE DETAILS",76f,981f,19f,496f,cc)
        text(c,if(wa) ad.whatsapp!! else "soko24.co  →",76f,1033f,38f,496f,cc,true)
        c.restore()
        text(c,"Full details & item link in caption",54f,1110f,21f,540f,fg)
        // Quiet progress accent, not a flashing banner.
        panel(c,RectF(54f,1144f,54f+544f*(seconds/CLOSE_AT),1148f),GREEN,2f)
    }
    private fun closing(c: Canvas,t: Float) {
        c.drawColor(INK)
        val e=ease(t/.7f)
        p.color=GREEN;p.alpha=45
        c.drawCircle(330f,580f,80f+e*300f,p);p.alpha=255
        c.save();c.translate(326f,560f);c.rotate(-12f*(1-e));c.scale(.65f+.35f*e,.65f+.35f*e)
        panel(c,RectF(-190f,-86f,190f,86f),GREEN,36f)
        text(c,"soko24",-150f,28f,78f,310f,INK,true)
        c.restore()
        text(c,"Find it. Make it yours.",100f,744f,35f,500f,PAPER,true,(e*255).toInt())
        text(c,"soko24.co",211f,805f,29f,360f,GREEN)
        // Brand-colour light sweep; original motion, not another service's ident.
        p.shader=LinearGradient(-200+t*650,0f,t*650,0f,intArrayOf(Color.TRANSPARENT,Color.argb(60,255,255,255),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        c.drawRect(0f,460f,720f,660f,p);p.shader=null
    }
}
