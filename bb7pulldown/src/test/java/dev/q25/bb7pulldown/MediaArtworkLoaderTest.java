package dev.q25.bb7pulldown;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaArtworkLoaderTest {
    @Test public void acceptsOnlyLocalAndroidResolverSchemes() {
        assertTrue(MediaArtworkLoader.supportedScheme("content"));
        assertTrue(MediaArtworkLoader.supportedScheme("android.resource"));
        assertFalse(MediaArtworkLoader.supportedScheme("https"));
        assertFalse(MediaArtworkLoader.supportedScheme("http"));
        assertFalse(MediaArtworkLoader.supportedScheme("file"));
        assertFalse(MediaArtworkLoader.supportedScheme(null));
    }
}
