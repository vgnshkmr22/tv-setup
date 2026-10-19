package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TickerTest {

    @Test
    public void parsesSpot() throws Exception {
        assertEquals(82435.205, Ticker.parseSpot("{\"data\":{\"amount\":\"82435.205\",\"base\":\"BTC\",\"currency\":\"USD\"}}"), 1e-9);
    }

    @Test
    public void refusesOtherPairsAndBadPrices() {
        for (String body : new String[] {
                "{\"data\":{\"amount\":\"3000\",\"base\":\"ETH\",\"currency\":\"USD\"}}",
                "{\"data\":{\"amount\":\"0\",\"base\":\"BTC\",\"currency\":\"USD\"}}",
                "{\"data\":{\"amount\":\"NaN\",\"base\":\"BTC\",\"currency\":\"USD\"}}",
                "{\"data\":{\"amount\":\"-5\",\"base\":\"BTC\",\"currency\":\"USD\"}}",
                "not json"}) {
            try {
                Ticker.parseSpot(body);
                fail(body);
            } catch (Exception expected) {
                // refused
            }
        }
    }

    @Test
    public void parsesOpenAndChange() throws Exception {
        double open = Ticker.parseOpen("{\"open\":\"81429\",\"high\":\"83076.23\",\"last\":\"82448.27\"}");
        assertEquals(81429, open, 1e-9);
        assertEquals(1.2517, Ticker.change(82448.27, open), 1e-3);
        assertTrue(Double.isNaN(Ticker.change(Double.NaN, open)));
        assertTrue(Double.isNaN(Ticker.change(1, 0)));
    }

    @Test
    public void parsesHeight() {
        assertEquals(972150, Ticker.parseHeight("972150\n"));
        for (String body : new String[] {"", "-1", "12a", "<html>", "1234567890"}) {
            try {
                Ticker.parseHeight(body);
                fail(body);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void formats() {
        assertEquals("$81,002", Ticker.formatPrice(81002.4));
        assertEquals("▼ 0.3%", Ticker.formatChange(-0.31));
        assertEquals("▲ 1.3%", Ticker.formatChange(1.25));
        assertEquals("0.0%", Ticker.formatChange(-0.04));
        assertEquals("972,026", Ticker.formatHeight(972026));
    }
}
