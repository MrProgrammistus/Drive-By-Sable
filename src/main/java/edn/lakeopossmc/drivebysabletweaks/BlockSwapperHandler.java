package edn.lakeopossmc.drivebysabletweaks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

@EventBusSubscriber(modid = "drivebysabletweaks", bus = EventBusSubscriber.Bus.GAME)
public class BlockSwapperHandler {

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() == null || event.getLevel().isClientSide()) return;

        // Trace the exact identity string of the original mod's placed item
        String blockIdentity = event.getPlacedBlock().getBlock().getDescriptionId();

        if (blockIdentity.equals("block.drivebywire.controller_hub")) {
            event.setCanceled(true); // Stop the broken vanilla block layout allocation

            BlockPos targetPos = event.getPos();
            BlockState replacementState = WireTweaksBlocks.CABLE_HUB.get().defaultBlockState()
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, event.getEntity().getDirection().getOpposite())
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR);

            event.getLevel().setBlock(targetPos, replacementState, 3);
        }
    }
}
