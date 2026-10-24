package jp.pioneer.ceam.MediaManager;

/** Test double for the head unit's microphone ownership API, loaded reflectively by production. */
public final class MediaController {
    private static final MediaController INSTANCE = new MediaController();
    public int requests, releases;
    public boolean grant = true;
    public String token;
    public static MediaController getInstance() { return INSTANCE; }
    public boolean requestMicOn(String owner) {
        requests++;
        if (grant) token = owner;
        return grant;
    }
    public String getCurMicToken() { return token; }
    public boolean requestMicOff(String owner) {
        releases++;
        if (!owner.equals(token)) return false;
        token = null;
        return true;
    }
    public void reset() { requests = releases = 0; grant = true; token = null; }
}
