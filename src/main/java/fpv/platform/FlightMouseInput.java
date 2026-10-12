package fpv.platform;

/** Optional platform input, independent of JNA and java.logging. Deltas are right/down. */
public interface FlightMouseInput extends AutoCloseable {
    boolean focused();
    void setCaptured(boolean value);
    void poll();
    float deltaX();
    float deltaY();
    boolean captured();
    String failure();
    default boolean nativeCapture(){return true;}
    default void pause(){setCaptured(false);}
    default void resume(){}
    @Override void close();
}
