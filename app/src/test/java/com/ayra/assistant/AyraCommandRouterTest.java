package com.ayra.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class AyraCommandRouterTest {
    @Test public void opensAllowedAppsButNeverSendsMessages() {
        AyraCommandRouter.Plan good = AyraCommandRouter.plan("open youtube");
        assertTrue(good.supported);
        assertEquals("youtube", good.action);
        assertFalse(AyraCommandRouter.plan("open WhatsApp and send a message").supported);
        assertFalse(AyraCommandRouter.plan("send my contact a message").supported);
    }

    @Test public void flashlightRequiresExactIntent() {
        assertEquals("on", AyraCommandRouter.plan("turn on flashlight").payload);
        assertEquals("off", AyraCommandRouter.plan("turn off torch").payload);
        assertFalse(AyraCommandRouter.plan("turn on flashlight and send SMS").supported);
    }

    @Test public void rejectsInvalidAlarmAndSupportsValidAlarm() {
        assertFalse(AyraCommandRouter.plan("set an alarm at 25:99").supported);
        AyraCommandRouter.Plan p = AyraCommandRouter.plan("set alarm for 7:05");
        assertTrue(p.supported);
        assertEquals("07:05", p.payload);
    }

    @Test public void acceptsSelectedBanglaCommands() {
        assertEquals("camera", AyraCommandRouter.plan("ক্যামেরা খোলো").action);
        assertEquals("battery", AyraCommandRouter.plan("ব্যাটারি কত").action);
    }

    @Test public void refusesUnlistedAndLongCommands() {
        assertFalse(AyraCommandRouter.plan("delete all photos").supported);
        assertFalse(AyraCommandRouter.plan(new String(new char[250]).replace('\0', 'X')).supported);
        assertFalse(AyraCommandRouter.isAllowedAction("send_message"));
    }
}
