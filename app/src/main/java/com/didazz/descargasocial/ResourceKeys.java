// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
final class ResourceKeys {
    static final String[] NO_RETRY = {"error_expired","error_download","error_92","error_93","error_94","error_95","error_96","error_141","error_142","error_143","error_144","error_310","error_314","error_163"};
    static boolean isPhase(String key) {
        switch(key) {
            case "phase_storage":
            case "phase_recover":
            case "phase_destination":
            case "phase_file":
            case "phase_youtube":
            case "phase_combined":
            case "phase_video":
            case "phase_audio":
            case "phase_mux":
            case "phase_save":
            case "phase_preparation":
            case "phase_download":
                return true;
            default: return false;
        }
    }
}
