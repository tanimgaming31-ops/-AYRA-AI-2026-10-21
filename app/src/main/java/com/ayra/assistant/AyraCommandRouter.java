package com.ayra.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, deliberately conservative ON-DEVICE command planner.
 * Plans never execute themselves. No network/model output is treated as an Android command.
 */
public final class AyraCommandRouter {
    private AyraCommandRouter() {}

    public static final class Plan {
        public final boolean supported;
        public final String action;
        public final String payload;
        public final String title;
        public final String reason;

        Plan(boolean supported, String action, String payload, String title, String reason) {
            this.supported = supported;
            this.action = action;
            this.payload = payload;
            this.title = title;
            this.reason = reason;
        }
    }

    private static Plan ready(String action, String payload, String title) {
        return new Plan(true, action, payload, title, "Requires a separate tap to run on Android.");
    }

    private static Plan unsupported(String reason) {
        return new Plan(false, "", "", "No supported phone action", reason);
    }

    public static boolean isAllowedAction(String action) {
        if (action == null) return false;
        switch (action) {
            case "youtube": case "whatsapp": case "messenger": case "imo":
            case "instagram": case "camera": case "torch": case "wifi":
            case "battery": case "notifications": case "contacts":
            case "permissions": case "alarm":
                return true;
            default: return false;
        }
    }

    public static Plan plan(String input) {
        if (input == null || input.trim().isEmpty()) return unsupported("Enter a command first.");
        if (input.length() > 240) return unsupported("Command is too long.");
        String s = input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (s.matches("(?:open|launch) (?:the )?(?:youtube|yt)|ইউটিউব (?:খোলো|খুলুন)"))
            return ready("youtube", "", "Open YouTube");
        if (s.matches("(?:open|launch) (?:the )?whatsapp|হোয়াটসঅ্যাপ (?:খোলো|খুলুন)"))
            return ready("whatsapp", "", "Open WhatsApp");
        if (s.matches("(?:open|launch) (?:the )?messenger"))
            return ready("messenger", "", "Open Messenger");
        if (s.matches("(?:open|launch) (?:the )?imo"))
            return ready("imo", "", "Open IMO");
        if (s.matches("(?:open|launch) (?:the )?instagram"))
            return ready("instagram", "", "Open Instagram");
        if (s.matches("(?:open|launch) (?:the )?camera|(?:take (?:a )?(?:photo|selfie))|ক্যামেরা (?:খোলো|খুলুন)"))
            return ready("camera", "", "Open camera");
        if (s.matches("(?:turn|switch) on (?:the )?(?:torch|flashlight)|টর্চ চালু|ফ্ল্যাশলাইট চালু"))
            return ready("torch", "on", "Turn flashlight on");
        if (s.matches("(?:turn|switch) off (?:the )?(?:torch|flashlight)|টর্চ বন্ধ|ফ্ল্যাশলাইট বন্ধ"))
            return ready("torch", "off", "Turn flashlight off");
        if (s.matches("(?:open|show) (?:wi-fi|wifi)(?: settings)?|ওয়াইফাই সেটিংস খোলো"))
            return ready("wifi", "", "Open Wi-Fi settings");
        if (s.matches("(?:check|show) (?:my )?battery(?: level)?|what(?:'s| is) my battery(?: level)?|ব্যাটারি কত"))
            return ready("battery", "", "Read battery level");
        if (s.matches("(?:read|show) (?:my )?notifications|নোটিফিকেশন পড়ো"))
            return ready("notifications", "", "Read available notifications");
        if (s.matches("(?:open|show) (?:my )?contacts"))
            return ready("contacts", "", "Open contact picker");
        if (s.matches("(?:open|show) (?:app )?(?:permissions|permission settings)"))
            return ready("permissions", "", "Open app permissions");
        Matcher alarm = Pattern.compile("^set (?:an? )?alarm (?:for |at )?(\\d{1,2})[:.](\\d{2})$").matcher(s);
        if (alarm.matches()) {
            int h = Integer.parseInt(alarm.group(1)), m = Integer.parseInt(alarm.group(2));
            if (h <= 23 && m <= 59)
                return ready("alarm", String.format(Locale.ROOT, "%02d:%02d", h, m), "Open alarm setup");
            return unsupported("Use a valid 24-hour alarm time.");
        }
        return unsupported("This planner only supports exact, allowlisted commands. Messages and app automation are not enabled.");
    }
}
