package puregero.multipaper.config;

import io.papermc.paper.configuration.ConfigurationLoaders;
import io.papermc.paper.configuration.ConfigurationPart;
import io.papermc.paper.configuration.InnerClassFieldDiscoverer;
import io.papermc.paper.configuration.constraint.Constraint;
import io.papermc.paper.configuration.constraint.Constraints;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.leangen.geantyref.GenericTypeReflector.erase;

public class MultiPaperConfigurationLoader {

    public static void init(File file) {
        try {
            YamlConfigurationLoader.Builder loaderBuilder = ConfigurationLoaders.naturallySorted();
            loaderBuilder.defaultOptions(options -> options.header(MultiPaperConfiguration.HEADER));

            Path configFile = file.toPath();
            YamlConfigurationLoader loader = loaderBuilder
                    .defaultOptions(applyObjectMapperFactory(createObjectMapper().build()))
                    .path(configFile)
                    .build();
            ConfigurationNode node;
            if (Files.exists(configFile)) {
                node = loader.load();
            } else {
                node = CommentedConfigurationNode.root(loader.defaultOptions());
            }

            String before = node.toString();
            setFromProperties(node);

            MultiPaperConfiguration instance = node.require(MultiPaperConfiguration.class);
            transformLegacyConfig(node, instance);

            for (Object key : node.childrenMap().keySet()) {
                node.removeChild(key);
            }

            node.set(instance);

            if (!node.toString().equals(before)) {
                loader.save(node);
            }

            MultiPaperConfiguration.set(instance);
        } catch (ConfigurateException e) {
            throw new RuntimeException("Could not load multipaper.yml", e);
        }
    }

    private static void setFromProperties(ConfigurationNode node) {
        for (Map.Entry<Object, Object> property : System.getProperties().entrySet()) {
            if (property.getKey().toString().startsWith("multipaper.")) {
                String key = property.getKey().toString().substring("multipaper.".length());
                try {
                    String value = ((String) property.getValue());
                    if(value.contains(";")) {
                        node.node((Object[]) key.split("\\.")).set(value.split(";"));
                    } else {
                        node.node((Object[]) key.split("\\.")).set(value);
                    }
                } catch (SerializationException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static ObjectMapper.Factory.Builder createObjectMapper() {
        return ObjectMapper.factoryBuilder()
                .addConstraint(Constraint.class, new Constraint.Factory())
                .addConstraint(Constraints.Min.class, Number.class, new Constraints.Min.Factory())
                .addDiscoverer(new InnerClassFieldDiscoverer(Collections.emptyMap()));
    }

    private static UnaryOperator<ConfigurationOptions> applyObjectMapperFactory(final ObjectMapper.Factory factory) {
        return options -> options.serializers(builder -> builder
                .register(type -> ConfigurationPart.class.isAssignableFrom(erase(type)), factory.asTypeSerializer())
                .registerAnnotatedObjects(factory));
    }

    private static void transformLegacyConfig(ConfigurationNode node, MultiPaperConfiguration config) {
        getAndRemove(node, "bungeecordName", value -> config.masterConnection.myName = value.getString());
        getAndRemove(node, "multipaperMasterAddress", value -> config.masterConnection.masterAddress = value.getString());
        getAndRemove(node, "syncJsonFiles", value -> config.syncSettings.syncJsonFiles = value.getBoolean());
        getAndRemove(node, "advertiseToBuiltInProxy", value -> config.masterConnection.advertiseToBuiltInProxy = value.getBoolean());
        getAndRemove(node, "syncScoreboards", value -> config.syncSettings.syncScoreboards = value.getBoolean());
        getAndRemove(node, "logFileSyncing", value -> config.syncSettings.files.logFileSyncs = value.getBoolean());
        getAndRemove(node, "filesToSyncOnStartup", value -> config.syncSettings.files.filesToSyncOnStartup = value.getList(String.class));
        getAndRemove(node, "filesToSyncInRealTime", value -> config.syncSettings.files.filesToSyncInRealTime = value.getList(String.class));
        getAndRemove(node, "filesToOnlyUploadOnServerStop", value -> config.syncSettings.files.filesToOnlyUploadOnServerStop = value.getList(String.class));
        getAndRemove(node, "filesToNotSync", value -> config.syncSettings.files.filesToNotSync = value.getList(String.class));
        getAndRemove(node, "ticksPerInactiveEntityTracking", value -> config.optimizations.ticksPerInactiveEntityTracking = value.getInt());
        getAndRemove(node, "optimizations.ticksPerInactiveEntityTracking", value -> config.optimizations.ticksPerInactiveEntityTracking = value.getInt());
        getAndRemove(node, "interServerCompressionThreshold", value -> config.peerConnection.compressionThreshold = value.getInt());
        getAndRemove(node, "interServerConsolidationDelay", value -> config.peerConnection.consolidationDelay = value.getInt());
        getAndRemove(node, "useLocalPlayerCountForServerIsFullKick", value -> config.syncSettings.useLocalPlayerCountForServerIsFullKick = value.getBoolean());
        getAndRemove(node, "syncPermissions", value -> config.syncSettings.syncPermissions = value.getBoolean());
        getAndRemove(node, "optimizations.reducePlayerPositionUpdatesInUnloadedChunks", value -> config.optimizations.reducePlayerPositionUpdatesInUnloadedChunks = value.getBoolean());
        getAndRemove(node, "optimizations.dontSaveLightingUpdates", value -> config.optimizations.dontSaveJustForLightingUpdates = value.getBoolean());
        getAndRemove(node, "optimizations.useFutureBasedIO", value -> config.optimizations.useEventBasedIo = value.getBoolean());
        getAndRemove(node, "optimizations.disableRedstoneSafetySync", value -> { /* removed */ });
    }

    private static void getAndRemove(ConfigurationNode node, String key, ExceptionableConsumer<ConfigurationNode> consumer) {
        if (System.getProperty(key) != null) {
            try {
                BasicConfigurationNode systemNode = BasicConfigurationNode.root();
                systemNode.set(System.getProperty(key));
                consumer.accept(systemNode);
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        String[] parts = key.split("\\.");

        for (int i = 0; i < parts.length; i++) {
            if (node.isMap() && node.childrenMap().containsKey(parts[i])) {
                if (i == parts.length - 1) {
                    try {
                        consumer.accept(node.childrenMap().get(parts[i]));
                        node.removeChild(parts[i]);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    node = node.childrenMap().get(parts[i]);
                }
            } else {
                return;
            }
        }
    }

}
