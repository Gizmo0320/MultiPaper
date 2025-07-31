package puregero.multipaper.config;

import io.papermc.paper.configuration.ConfigurationPart;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.List;

@SuppressWarnings({ "InnerClassMayBeStatic" })
public class MultiPaperConfiguration extends ConfigurationPart {

    public static final String HEADER = """
            This is the main configuration file for MultiPaper.
            There's quite alot to configure. Read the docs for more information.

            Docs: https://github.com/PureGero/MultiPaper/blob/main/MULTIPAPER_YAML.md\s
            """;

    private static MultiPaperConfiguration instance;

    public static MultiPaperConfiguration get() {
        return instance;
    }

    static void set(MultiPaperConfiguration instance) {
        MultiPaperConfiguration.instance = instance;
    }

    public MasterConnection masterConnection;

    public class MasterConnection extends ConfigurationPart  {
        public boolean advertiseToBuiltInProxy = true;

        // One day we'll get comments in yaml files
        // @Comment("""
        //
        // Set the address used to connect to the master database here.
        // """)
        public String masterAddress = "localhost:35353";

        // One day we'll get comments in yaml files
        // @Comment("""
        //
        // Give this server a name. This can be the same name you used for this server in
        // BungeeCord or Velocity, or you can come up with a random name. Each server must
        // have a unique name.
        // """)
        public String myName = "server" + Double.toString(Math.random()).substring(2, 7);
    }

    public PeerConnection peerConnection;

    public class PeerConnection extends ConfigurationPart {
        public int compressionThreshold = 0;
        public int consolidationDelay = 0;
    }

    public Optimizations optimizations;

    public class Optimizations extends ConfigurationPart  {
        public boolean dontSaveJustForLightingUpdates = false;
        public int maxFootstepPacketsSentPerPlayer = -1;
        public boolean reducePlayerPositionUpdatesInUnloadedChunks = false;
        public int ticksPerInactiveEntityTracking = 1;
        public boolean useEventBasedIo = true;
    }

    public SyncSettings syncSettings;

    public class SyncSettings extends ConfigurationPart  {
        public boolean syncEntityIds = true;
        public boolean syncJsonFiles = true;
        public boolean syncPermissions = false;
        public boolean syncScoreboards = true;
        public boolean useLocalPlayerCountForServerIsFullKick = false;
        public boolean persistentPlayerEntityIds = true;
        public int persistentVehicleEntityIdsSeconds = 15;

        public Files files;

        public class Files extends ConfigurationPart {
            public List<String> filesToNotSync = List.of("plugins/bStats");
            public List<String> filesToOnlyUploadOnServerStop = List.of("plugins/MyPluginDirectory/my_big_database.db");
            public List<String> filesToSyncInRealTime = List.of("plugins/MyPluginDirectory/userdata");
            public List<String> filesToSyncOnStartup = List.of("myconfigfile.yml", "plugins/MyPlugin.jar");
            public boolean logFileSyncs = true;
        }
    }


}
