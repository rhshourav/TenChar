# Safety net only. The actual rule is that no code logs secrets, in any build.
# This removes android.util.Log calls from release builds; it is not relied on.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
