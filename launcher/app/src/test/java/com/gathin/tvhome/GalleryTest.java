package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class GalleryTest {

    @Test
    public void picksPhotosOnly() {
        assertTrue(Gallery.isPhoto("IMG_0001.JPG"));
        assertTrue(Gallery.isPhoto("beach.jpeg"));
        assertTrue(Gallery.isPhoto("x.png"));
        assertTrue(Gallery.isPhoto("y.WebP"));
        assertFalse(Gallery.isPhoto("._IMG_0001.jpg"));
        assertFalse(Gallery.isPhoto(".thumb.jpg"));
        assertFalse(Gallery.isPhoto("clip.mp4"));
        assertFalse(Gallery.isPhoto("heic.HEIC"));
        assertFalse(Gallery.isPhoto(""));
        assertFalse(Gallery.isPhoto(null));
    }

    @Test
    public void listsSortedAndCapped() {
        assertEquals(Arrays.asList("a.jpg", "b.png"), Gallery.photos(new String[] {"b.png", "notes.txt", "a.jpg", ".x.jpg"}));
        assertTrue(Gallery.photos(null).isEmpty());
        String[] many = new String[Gallery.MAX_FILES + 50];
        for (int i = 0; i < many.length; i++) many[i] = String.format("%05d.jpg", i);
        assertEquals(Gallery.MAX_FILES, Gallery.photos(many).size());
    }

    @Test
    public void everyPhotoOncePerRound() {
        List<String> all = Arrays.asList("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg");
        Gallery.ShuffleBag bag = new Gallery.ShuffleBag(new Random(42));
        for (int round = 0; round < 20; round++) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < all.size(); i++) assertTrue(seen.add(bag.next(all)));
            assertEquals(new HashSet<>(all), seen);
        }
    }

    @Test
    public void noRepeatAcrossRounds() {
        List<String> all = Arrays.asList("1.jpg", "2.jpg");
        Gallery.ShuffleBag bag = new Gallery.ShuffleBag(new Random(7));
        String prev = bag.next(all);
        for (int i = 0; i < 200; i++) {
            String next = bag.next(all);
            assertNotEquals(prev, next);
            prev = next;
        }
    }

    @Test
    public void skipsRemovedPhotosAndEmpty() {
        Gallery.ShuffleBag bag = new Gallery.ShuffleBag(new Random(1));
        bag.next(Arrays.asList("1.jpg", "2.jpg", "3.jpg"));
        List<String> now = Collections.singletonList("3.jpg");
        assertEquals("3.jpg", bag.next(now));
        assertEquals("3.jpg", bag.next(now));
        assertNull(bag.next(Collections.emptyList()));
    }

    @Test
    public void timing() {
        long now = 10_000_000L;
        assertEquals(0, Gallery.delayUntilNext(0, now));
        assertEquals(0, Gallery.delayUntilNext(now + 1, now));   // clock went back
        assertEquals(Gallery.INTERVAL_MS - 1000, Gallery.delayUntilNext(now - 1000, now));
        assertEquals(0, Gallery.delayUntilNext(now - Gallery.INTERVAL_MS - 5, now));
        assertTrue(Gallery.relistDue(0, now));
        assertFalse(Gallery.relistDue(now - 1000, now));
        assertTrue(Gallery.relistDue(now - Gallery.RELIST_MS, now));
        assertTrue(Gallery.relistDue(now + 5, now));
    }
}
