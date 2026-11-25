package com.muse.workspace.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import java.util.ArrayList;
import java.util.List;

/** 仅设备本地的卡面偏好；不进入账号、Cookie、代理或跨端传输数据。 */
final class AccountAppearance {
    private static final String PREFS = "muse-account-appearance";
    static final Skin[] ALL = {
        new Skin("ice", "霜棱银", "d9e4e8 f7f9f5 a5bdc9 233b44 45606b 547f8d 648995 87a4b2", false),
        new Skin("wave", "潮汐蓝", "245a80 3e7a94 133a60 f2f8f3 dae8e6 a1dedc 83d2d3 d6b67a", true),
        new Skin("fan", "绯金折扇", "722f3e 914554 4e2639 fff4e7 f0ddd3 ecc18c e4ae75 f4d9ae", true),
        new Skin("porcelain", "青花回纹", "ebece7 fffdf5 cad8df 233954 46576a 406995 315b91 789fba", false),
        new Skin("weave", "蔷薇织锦", "e5c1c5 f8e0d6 c09ba9 452c40 624651 92536b 9b5c74 bc8b79", false),
        new Skin("pine", "松针青", "305646 48715b 203e37 f3f5df d3e3d4 d7d99e c3d4a0 96ba9a", true),
        new Skin("carbon2", "曜石碳纤", "292c30 41454a 171b21 f1f2ed d0d4d1 c4d8d3 889da4 54656e", true),
        new Skin("terrace", "赤陶梯田", "edc7a8 f7dfbd d99d80 4a312b 68473b 9c553e b45e42 d49360", false),
        new Skin("orbit", "暮紫星图", "483d64 655882 302b4a f9f5ff e0dbe9 decbf0 c3b4e4 e5ce9b", true),
        new Skin("record", "琥珀唱纹", "dab565 edce8c bd8a45 3f321e 594527 705321 8a652e f2dca6", false),
        new Skin("sage", "鼠尾草绿", "c9ddcf eff6e9 afcbbb 213c33 425c4b 426b53 466d55 a7bdab", false),
        new Skin("clay", "陶粉", "edc9b5 fbdfd1 dfb29e 4c3027 705044 93604d 98634f ca9e8e", false),
        new Skin("carbon", "深墨", "27343e 4e6265 1b2533 faf4e7 d3d8d4 edc98a d4bf90 a8bebd", true)
    };

    static Skin find(String id) {
        for (Skin skin : ALL) if (skin.id.equals(id)) return skin;
        return ALL[10];
    }

    static Skin read(Context context, String accountId) {
        try { return find(accountId == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(accountId, null)); }
        catch (ClassCastException invalidPreference) { return find(null); }
    }

    static void save(Context context, String accountId, String skinId) {
        if (accountId != null && !accountId.isEmpty())
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(accountId, find(skinId).id).apply();
    }

    static int alpha(int color, int alpha) { return ColorUtils.setAlphaComponent(color, alpha); }

    static final class Skin {
        final String id, name;
        final int paper, high, low, ink, secondary, foil, line, second, copy, hair, glaze, ripple;
        final boolean dark;

        Skin(String id, String name, String palette, boolean dark) {
            this.id = id; this.name = name; this.dark = dark;
            String[] colors = palette.split(" ");
            paper = Color.parseColor("#" + colors[0]); high = Color.parseColor("#" + colors[1]);
            low = Color.parseColor("#" + colors[2]); ink = Color.parseColor("#" + colors[3]);
            secondary = Color.parseColor("#" + colors[4]); foil = Color.parseColor("#" + colors[5]);
            line = Color.parseColor("#" + colors[6]); second = Color.parseColor("#" + colors[7]);
            copy = ColorUtils.blendARGB(ink, secondary, .18f);
            hair = alpha(line, dark ? 104 : 88);
            glaze = dark ? 0x13ffffff : 0x38fff9ea;
            ripple = alpha(ink, dark ? 40 : 24);
        }

        Drawable surface(Context context, boolean selected) { return surface(context, selected, 23); }
        Drawable surface(Context context, boolean selected, float radius) { return new Surface(context, this, selected, radius); }

        Drawable background(Context context, boolean selected) {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(23 * context.getResources().getDisplayMetrics().density);
            return new RippleDrawable(ColorStateList.valueOf(ripple), surface(context, selected), mask);
        }

        void press(TextView view) {
            float density = view.getResources().getDisplayMetrics().density;
            view.setTextColor(ink);
            view.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            // 极浅的下缘反光，不给普通小字加模糊阴影。
            view.setShadowLayer(.1f * density, 0, .5f * density, dark ? 0x38fff8d7 : 0x88ffffff);
        }

        void badge(TextView view, MobileUi ui) {
            view.setTextSize(11);
            view.setTextColor(ink);
            view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            view.setBackground(ui.shape(glaze, 4, hair));
            view.setPadding(ui.dp(5), ui.dp(2), ui.dp(5), ui.dp(2));
        }
    }

    /** 与真实卡、编辑预览、13 款缩略图共享的离线矢量材质。 */
    private static final class Surface extends Drawable {
        private final Skin skin;
        private final float density;
        private final boolean selected;
        private final float radiusDp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path etch = new Path();
        private final List<Path> fields = new ArrayList<>();
        private final RectF box = new RectF();
        private int opacity = 255;

        Surface(Context context, Skin skin, boolean selected, float radiusDp) {
            this.skin = skin; this.selected = selected;
            this.radiusDp = radiusDp;
            density = context.getResources().getDisplayMetrics().density;
            build();
        }

        private void segment(float x, float y, float ex, float ey) { etch.moveTo(x, y); etch.lineTo(ex, ey); }
        private void circle(float x, float y, float radius) { etch.addCircle(x, y, radius, Path.Direction.CW); }
        private void polygon(float... xy) {
            Path path = new Path(); path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            path.close(); fields.add(path); etch.addPath(path);
        }

        private void build() {
            switch (skin.id) {
                case "ice" -> {
                    polygon(224,0,313,0,291,68); polygon(313,0,398,0,338,62);
                    polygon(291,68,338,62,311,143); polygon(338,62,420,26,389,114);
                    polygon(311,143,389,114,358,211); polygon(389,114,420,65,420,166);
                    polygon(358,211,420,166,420,240); polygon(224,240,311,143,358,211);
                    for (int i=0;i<13;i++) segment(284+i*2.5f,84+i*2.4f,314+i*2.5f,32+i*2.4f);
                }
                case "wave" -> {
                    for (int row=-1;row<6;row++) for (int col=-1;col<5;col++) {
                        float x=222+col*57+(row%2!=0?28.5f:0), y=row*32+25;
                        for (int i=0;i<5;i++) { float r=7+6*i; etch.addArc(new RectF(x-r,y-r,x+r,y+r),180,180); }
                    }
                }
                case "fan" -> {
                    for (int i=0;i<13;i++) {
                        float angle=185+i*9;
                        Path p=new Path(); p.moveTo(375,205);
                        p.arcTo(new RectF(214,44,536,366),angle,9); p.close(); fields.add(p);
                    }
                    for (int i=0;i<16;i++) { double a=Math.toRadians(182+i*8.7); segment(375,205,375+(float)Math.cos(a)*168,205+(float)Math.sin(a)*168); }
                    for (int i=0;i<5;i++) { float r=141+i*5; etch.addArc(new RectF(375-r,205-r,375+r,205+r),180,135); }
                }
                case "porcelain" -> {
                    for (int row=-1;row<6;row++) for (int col=0;col<5;col++) {
                        float x=245+col*42+(row%2!=0?21:0),y=9+row*44;
                        etch.moveTo(x,y+36); etch.rLineTo(0,-32); etch.rLineTo(32,0); etch.rLineTo(0,24);
                        etch.rLineTo(-24,0); etch.lineTo(x+8,y+12); etch.rLineTo(16,0); etch.rLineTo(0,8); etch.rLineTo(-8,0);
                        segment(x-8,y+36,x+32,y+36);
                    }
                    polygon(326,0,336,0,336,240,326,240);
                }
                case "weave" -> {
                    for(int i=-4;i<12;i++){float x=164+i*17;segment(x,-5,x+173,245);segment(x,245,x+173,-5);}
                    for(int row=0;row<5;row++)for(int col=0;col<4;col++){float x=250+col*48+(row%2!=0?24:0),y=8+row*45;polygon(x,y-10,x+10,y,x,y+10,x-10,y);}
                }
                case "pine" -> {
                    float[][] branches={{380,248,285,47},{365,211,378,50},{342,172,239,116},{332,151,317,35},{320,136,260,64},{355,191,417,105},{303,92,289,18}};
                    for(float[] b:branches){segment(b[0],b[1],b[2],b[3]);double a=Math.atan2(b[3]-b[1],b[2]-b[0]);
                        for(int k=2;k<=15;k++){float t=k/16f,x=b[0]+(b[2]-b[0])*t,y=b[1]+(b[3]-b[1])*t,len=8+19*(float)Math.sin(t*Math.PI);
                            for(int side:new int[]{-1,1})segment(x,y,x+len*(float)Math.cos(a+side*.64),y+len*(float)Math.sin(a+side*.64));}}
                }
                case "carbon2" -> {
                    for(int row=0;row<25;row++)for(int col=0;col<18;col++){if((row+col)%4>1)continue;float x=214+col*13+(row%2!=0?5:0),y=row*11-6;polygon(x,y,x+12,y-4,x+16,y,x+4,y+4);}
                    for(int i=0;i<16;i++)segment(231+i*13,249,331+i*13,-21);
                }
                case "terrace" -> {
                    for(int i=0;i<7;i++){float x=175+i*10,y=211-i*22;Path p=new Path();p.moveTo(x,247);p.lineTo(x,y);p.quadTo(x+18,y-20,x+48,y-11);p.cubicTo(x+80,y-1,x+100,y-41,x+112,y-27);p.quadTo(x+130,y-50,x+168,y-44);p.cubicTo(x+200,y-38,x+230,y-70,x+248,y-79);p.lineTo(445,248);p.close();fields.add(p);etch.addPath(p);}
                }
                case "orbit" -> {
                    for(int i=0;i<5;i++)circle(346,114,26+i*24);
                    segment(197,114,439,114);segment(346,-20,346,247);segment(246,214,436,24);
                    for(int i=0;i<40;i++){double a=i*Math.PI/20;float n=i%5==0?8:3;segment(346+97*(float)Math.cos(a),114+97*(float)Math.sin(a),346+(97+n)*(float)Math.cos(a),114+(97+n)*(float)Math.sin(a));}
                    float[] stars={228,131,259,79,297,96,328,59,357,83,388,44,412,67};
                    for(int i=0;i<stars.length;i+=2){if(i>0)segment(stars[i-2],stars[i-1],stars[i],stars[i+1]);float x=stars[i],y=stars[i+1];polygon(x,y-5,x+1,y-1,x+4,y,x+1,y+1,x,y+5,x-1,y+1,x-4,y,x-1,y-1);}
                }
                case "record" -> {
                    for(int i=0;i<36;i++)circle(395,118,38+i*3.25f);circle(395,118,6);circle(395,118,28);segment(237,227,337,156);segment(401,80,449,27);
                }
                default -> {
                    for(int i=0;i<30;i++){float f=i/29f;etch.moveTo(171+i*2.4f,284);etch.cubicTo(197+i*1.6f,158+20*f,395-81*f,235-57*f,361-43*f,153-41*f);etch.cubicTo(327-50*f,80+12*f,288-40*f,48+14*f,457-18*f,18-46*f);}
                }
            }
        }

        @Override public void draw(Canvas canvas) {
            box.set(getBounds());
            float radius=Math.min(radiusDp*density,Math.min(box.width(),box.height())/2);
            paint.setStyle(Paint.Style.FILL); paint.setShader(new LinearGradient(box.left,box.top,box.right,box.bottom,
                new int[]{skin.high,skin.paper,skin.low},new float[]{0,.47f,1},Shader.TileMode.CLAMP));
            paint.setAlpha(opacity); canvas.drawRoundRect(box,radius,radius,paint); paint.setShader(null);
            int save=canvas.save(); Path clip=new Path();clip.addRoundRect(box,radius,radius,Path.Direction.CW);canvas.clipPath(clip);
            canvas.translate(box.left,box.top);canvas.scale(box.width()/420f,box.height()/240f);
            paint.setStyle(Paint.Style.FILL);
            for(int i=0;i<fields.size();i++){paint.setColor(alpha(i%2==0?skin.second:skin.line,Math.round(opacity*.085f)));canvas.drawPath(fields.get(i),paint);}
            paint.setStrokeWidth(skin.id.equals("porcelain")?1.7f:skin.id.equals("carbon2")?.65f:1f);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            drawEtch(canvas,skin.dark?Color.BLACK:0xff40382f,-.35f,-.7f,.18f);
            drawEtch(canvas,skin.dark?0xfffff2cf:0xfffffdf4,.45f,.85f,.36f);
            drawEtch(canvas,skin.line,0,0,.38f);
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(alpha(skin.ink,Math.round(opacity*.025f)));
            for(int y=1;y<240;y+=8)for(int x=0;x<420;x+=9)canvas.drawCircle(x+(y%3),y,.35f,paint);
            canvas.restoreToCount(save);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);paint.setColor(alpha(selected?skin.foil:skin.low,opacity));
            box.inset(density*.5f,density*.5f);canvas.drawRoundRect(box,radius,radius,paint);
            box.set(getBounds());box.inset(3*density,3*density);paint.setColor(alpha(skin.line,Math.round(opacity*.42f)));canvas.drawRoundRect(box,Math.max(0,radius-3*density),Math.max(0,radius-3*density),paint);
            box.inset(-density,-density);paint.setStrokeWidth(.6f*density);paint.setColor(alpha(Color.WHITE,Math.round(opacity*(skin.dark?.16f:.52f))));canvas.drawRoundRect(box,Math.max(0,radius-2*density),Math.max(0,radius-2*density),paint);
        }

        private void drawEtch(Canvas canvas,int color,float dx,float dy,float strength){
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(158,0,420,0,new int[]{alpha(color,0),alpha(color,Math.round(opacity*strength))},null,Shader.TileMode.CLAMP));
            canvas.save();canvas.translate(dx,dy);canvas.drawPath(etch,paint);canvas.restore();
        }
        @Override public void getOutline(Outline outline){outline.setRoundRect(getBounds(),Math.min(radiusDp*density,Math.min(getBounds().width(),getBounds().height())/2f));}
        @Override public void setAlpha(int value){opacity=value;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter value){paint.setColorFilter(value);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
