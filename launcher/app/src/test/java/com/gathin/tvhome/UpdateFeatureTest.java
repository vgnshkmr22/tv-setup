package com.gathin.tvhome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The guard that switches self-update off for the session instead of taking the home screen down. */
public class UpdateFeatureTest {

    private final List<Throwable> reported = new ArrayList<>();

    @Before
    public void setUp() {
        UpdateFeature.reset();
        UpdateFeature.reporter = reported::add;
    }

    @After
    public void tearDown() {
        UpdateFeature.reset();
    }

    @Test
    public void aFailureSwitchesUpdatesOffOnce() {
        assertEquals("ok", UpdateFeature.run(() -> "ok", "fallback"));
        assertEquals("fallback", UpdateFeature.run(() -> {
            throw new IllegalStateException("bad state");
        }, "fallback"));
        assertFalse(UpdateFeature.working());
        assertEquals("fallback", UpdateFeature.run(() -> "would work", "fallback"));
        assertEquals(1, reported.size());
        UpdateFeature.reset();
        assertTrue(UpdateFeature.working());
    }
}
