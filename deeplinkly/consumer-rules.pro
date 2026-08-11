# The SDK probes for play-services-ads-identifier by name rather than linking
# it, so R8 cannot see the reference and would happily strip or rename the
# class in an app that DID opt in.
-dontwarn com.google.android.gms.ads.identifier.**
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient { *; }
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info { *; }
