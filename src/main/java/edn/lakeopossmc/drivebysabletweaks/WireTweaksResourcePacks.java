package edn.lakeopossmc.drivebysabletweaks;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.nio.file.Path;
import java.util.Optional;

@EventBusSubscriber(modid = DriveBySableTweaks.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class WireTweaksResourcePacks {

    private static final String PACK_ID = "drivebysabletweaks_better-dbw-1.0.0-builtin";

    @SubscribeEvent
    public static void addPackFinders(final AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }

        final Path resourcePath = ModList.get()
                .getModFileById(DriveBySableTweaks.MOD_ID)
                .getFile()
                .findResource("resourcepacks/better-dbw-1.0.0-builtin");

        event.addRepositorySource((consumer) -> {
            PackLocationInfo locationInfo = new PackLocationInfo(
                    PACK_ID,
                    Component.literal("Better Drive-By-Wire (built-in)"),
                    PackSource.BUILT_IN,
                    Optional.empty()
            );

            Pack pack = Pack.readMetaAndCreate(
                    locationInfo,
                    new PathPackResources.PathResourcesSupplier(resourcePath),
                    PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(false, Pack.Position.TOP, false)
            );

            if (pack != null) {
                consumer.accept(pack);
            }
        });
    }
}