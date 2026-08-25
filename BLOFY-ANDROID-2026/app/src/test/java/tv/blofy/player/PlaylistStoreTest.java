package tv.blofy.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaylistStoreTest {
    @Test public void xtreamFallbackRequiresAllCredentials() {
        PlaylistStore.Playlist playlist = new PlaylistStore.Playlist();
        playlist.kind = "xtream";
        playlist.serverUrl = "https://provider.example";
        playlist.username = "viewer";
        assertFalse(playlist.canConnectLocally());
        playlist.password = "secret";
        assertTrue(playlist.canConnectLocally());
    }

    @Test public void m3uFallbackOnlyRequiresPlaylistUrl() {
        PlaylistStore.Playlist playlist = new PlaylistStore.Playlist();
        playlist.kind = "m3u";
        assertFalse(playlist.canConnectLocally());
        playlist.url = "https://provider.example/list.m3u8";
        assertTrue(playlist.canConnectLocally());
    }
}
