package local.iconstudio.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.UnaryOperator;

/** Rewrites Android string pools without changing resource IDs or string indices. */
public final class BinaryResources {
    public static final String TEMPLATE_PACKAGE = "local.prism.pack.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    public static final int TEMPLATE_VERSION = 123456789;
    private static int u16(byte[] a,int p){return (a[p]&255)|((a[p+1]&255)<<8);}
    private static int i32(byte[] a,int p){return ByteBuffer.wrap(a,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    private static void put(byte[] a,int p,int n){ByteBuffer.wrap(a,p,4).order(ByteOrder.LITTLE_ENDIAN).putInt(n);}
    private static void intOut(ByteArrayOutputStream o,int n){o.write(n);o.write(n>>>8);o.write(n>>>16);o.write(n>>>24);}
    private static int[] len8(byte[] a,int p){int n=a[p++]&255; if((n&128)!=0)n=((n&127)<<8)|(a[p++]&255);return new int[]{n,p};}
    private static int[] len16(byte[] a,int p){int n=u16(a,p);p+=2;if((n&32768)!=0){n=((n&32767)<<16)|u16(a,p);p+=2;}return new int[]{n,p};}
    private static void length8(ByteArrayOutputStream o,int n){if(n>32767)throw new IllegalArgumentException("String too long");if(n>127)o.write((n>>>8)|128);o.write(n);}
    private static void length16(ByteArrayOutputStream o,int n){if(n>32767){int v=(n>>>16)|32768;o.write(v);o.write(v>>>8);}o.write(n);o.write(n>>>8);}
    public static byte[] rewrite(byte[] input, UnaryOperator<String> replace, String packageName, int version) {
        if(input.length<8 || i32(input,4)!=input.length)throw new IllegalArgumentException("Invalid resource chunk");
        byte[] result=chunk(input,replace,packageName);
        if(u16(result,0)==3){
            int found=0;
            for(int p=8;p+4<=result.length;p+=4)if(i32(result,p)==TEMPLATE_VERSION){put(result,p,version);found++;}
            // Only the manifest contains a version. appfilter contains none.
            if(found>1)throw new IllegalArgumentException("Ambiguous version marker");
        }
        return result;
    }
    private static byte[] chunk(byte[] a,UnaryOperator<String> replace,String pkg) {
        int type=u16(a,0), header=u16(a,2);
        if(type==1)return pool(a,replace);
        if(type!=2 && type!=3 && type!=0x200)return a;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] h=Arrays.copyOf(a,header);
        if(type==0x200 && pkg!=null){
            if(pkg.length()>127)throw new IllegalArgumentException("Package name too long");
            Arrays.fill(h,12,268,(byte)0); byte[] n=pkg.getBytes(StandardCharsets.UTF_16LE);System.arraycopy(n,0,h,12,n.length);
        }
        out.write(h,0,h.length);
        for(int p=header;p<a.length;){
            int size=i32(a,p+4);if(size<8||size>a.length-p)throw new IllegalArgumentException("Broken chunk");
            byte[] child=chunk(Arrays.copyOfRange(a,p,p+size),replace,pkg);out.write(child,0,child.length);p+=size;
        }
        byte[] result=out.toByteArray();put(result,4,result.length);return result;
    }
    private static byte[] pool(byte[] a,UnaryOperator<String> replace){
        int header=u16(a,2), count=i32(a,8), styles=i32(a,12), flags=i32(a,16), start=i32(a,20);
        if(styles!=0)throw new IllegalArgumentException("Styled pools unsupported");
        boolean utf8=(flags&256)!=0;
        ByteArrayOutputStream data=new ByteArrayOutputStream(), offsets=new ByteArrayOutputStream();
        for(int i=0;i<count;i++){
            int p=start+i32(a,header+4*i);String s;
            if(utf8){int[] n=len8(a,p), bytes=len8(a,n[1]);s=new String(a,bytes[1],bytes[0],StandardCharsets.UTF_8);}
            else{int[] n=len16(a,p);s=new String(a,n[1],n[0]*2,StandardCharsets.UTF_16LE);}
            s=replace.apply(s);intOut(offsets,data.size());
            if(utf8){byte[] b=s.getBytes(StandardCharsets.UTF_8);length8(data,s.length());length8(data,b.length);data.write(b,0,b.length);data.write(0);}
            else{byte[] b=s.getBytes(StandardCharsets.UTF_16LE);length16(data,s.length());data.write(b,0,b.length);data.write(0);data.write(0);}
        }
        while(data.size()%4!=0)data.write(0);
        byte[] h=Arrays.copyOf(a,header);put(h,16,flags&~1);put(h,20,header+count*4);put(h,24,0);put(h,4,header+count*4+data.size());
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(h,0,h.length);byte[] b=offsets.toByteArray();out.write(b,0,b.length);b=data.toByteArray();out.write(b,0,b.length);return out.toByteArray();
    }
}
