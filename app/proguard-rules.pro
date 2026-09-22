# jaudiotagger discovers several tag and codec implementations dynamically.
# Keep it intact so shrinking cannot turn a successful debug edit into a
# release-only metadata failure.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
