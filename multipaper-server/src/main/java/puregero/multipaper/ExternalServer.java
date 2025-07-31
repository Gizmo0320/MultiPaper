package puregero.multipaper;

public class ExternalServer {
    private final String name;
    private final boolean me;
    private int averageTickTime;
    private double tps;
    private long lastAlive;
    private ExternalServerConnection connection;

    public ExternalServer(String name, boolean me) {
        this.name = name;
        this.me = me;
    }

    public int getAverageTickTime() {
        return averageTickTime;
    }

    public void setAverageTickTime(int averageTickTime) {
        this.averageTickTime = averageTickTime;
    }

    public long getLastAlive() {
        return lastAlive;
    }

    public void setLastAlive(long lastAlive) {
        this.lastAlive = lastAlive;
    }

    public String getName() {
        return name;
    }

    public boolean isMe() {
        return me;
    }

    public boolean isAlive() {
        return getLastAlive() > System.currentTimeMillis() - 2500 && getTps() > 0;
    }

    public double getTps() {
        return tps;
    }

    public void setTps(double tps) {
        this.tps = tps;
    }

    public void setConnection(ExternalServerConnection connection) {
        this.connection = connection;
    }

    public ExternalServerConnection getConnection() {
        return connection;
    }
}
