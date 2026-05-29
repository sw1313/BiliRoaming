-keep class me.custom.biliextras.XposedInit {
    <init>();
}

-keepclasseswithmembers class me.iacn.biliroaming.utils.DexHelper {
    native <methods>;
    long token;
    java.lang.ClassLoader classLoader;
}
