// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import org.junit.Test;
import static org.junit.Assert.*;

public class HearingOutputsTest {
    @Test public void productionFilterRejectsBuiltInSpeaker() {
        for (int sdk:new int[]{29,30,31,35,36,37,38})
            assertFalse(HearingOutputs.accepts(2,sdk));
    }
    @Test public void onlyHearingOutputs() {
        for (int sdk:new int[]{29,30,31,35,36,37,38})
            for (int type=-1;type<=40;type++)
                assertEquals(type==23 || type==26 || (sdk>=37 && type==33),HearingOutputs.accepts(type,sdk));
    }
    @Test public void addressIdentitySurvivesProductRename() {
        assertTrue(HearingOutputs.matches(23,"AA","old",23,"AA","new"));
    }
    @Test public void addressMustMatchAndBePresent() {
        // LE Audio pair reporting the other aid's address: same product name.
        assertTrue(HearingOutputs.matches(26,"AA","Aid",26,"BB","Aid"));
        assertTrue(HearingOutputs.matches(23,"AA","aid",23,"","aid"));
        assertFalse(HearingOutputs.matches(23,"AA","aid",23,"BB","other"));
        assertFalse(HearingOutputs.matches(23,"AA","aid",26,"AA","aid"));
        assertFalse(HearingOutputs.matches(23,"","",23,"","aid"));
    }
    @Test public void typeMustAlwaysMatch() {
        assertFalse(HearingOutputs.matches(23,"AA","aid",26,"AA","aid"));
        assertFalse(HearingOutputs.matches(23,"","aid",26,"","aid"));
    }
    @Test public void emptySavedAddressFallsBackToExactProductName() {
        assertTrue(HearingOutputs.matches(23,"","aid",23,"","aid"));
        assertTrue(HearingOutputs.matches(23,"","aid",23,"AA","aid"));
        assertFalse(HearingOutputs.matches(23,"","aid",23,"","other"));
    }
}
