# ============================================================================
#  Steven Piano - Android player for the self-playing acoustic piano
#  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
#  Original author & creator: Steven Jin.
#  Licensed under the MIT License (see LICENSE). This copyright and attribution
#  notice MUST be preserved in all copies or substantial portions of the work.
#  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
# ============================================================================

# The compiled-in authorship string must survive R8 so it is present in the release DEX.
-keep class dev.stevenjin.stevenpiano.Provenance { *; }

# Release builds log warnings and errors only: verbose, debug and info calls (request URLs,
# artwork keys, the link's chatter) are removed with their messages.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
