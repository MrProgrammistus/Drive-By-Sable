package edn.lakeopossmc.drivebysable.cable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

public interface MultiChannelCableSource {
    List<String> cable$getChannels(Level level, BlockPos pos);

    String cable$nextChannel(Level level, BlockPos pos, String current, boolean forward);
}