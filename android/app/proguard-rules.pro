# Methods the web pages call through window.SpendWitzApp
-keepclassmembers class com.spendwitz.app.MainActivity$AppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
