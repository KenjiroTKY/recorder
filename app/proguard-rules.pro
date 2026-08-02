# Bouncy CastleのASN.1/TSPクラスはリフレクション的に参照されるため難読化・縮小対象から除外
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
