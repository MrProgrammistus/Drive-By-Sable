package edn.lakeopossmc.drivebysable.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerItem;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import edn.lakeopossmc.drivebysable.CableBlocks;
import edn.lakeopossmc.drivebysable.CableConfig;
import edn.lakeopossmc.drivebysable.CableItems;
import edn.lakeopossmc.drivebysable.DriveBySableMod;
import edn.lakeopossmc.drivebysable.cable.CableNetworkManager;
import edn.lakeopossmc.drivebysable.cable.MultiChannelCableSource;
import edn.lakeopossmc.drivebysable.cable.graph.CableNetworkNode.CableNetworkSink;
import edn.lakeopossmc.drivebysable.compat.TweakedControllerCableServerHandler;
import edn.lakeopossmc.drivebysable.items.CableItem;
import edn.lakeopossmc.drivebysable.items.CableCutterItem;
import edn.lakeopossmc.drivebysable.mixinducks.TweakedControllerDuck;
import edn.lakeopossmc.drivebysable.network.CableAddConnectionPacket;
import edn.lakeopossmc.drivebysable.network.CableNetworkRequestSyncPacket;
import edn.lakeopossmc.drivebysable.network.CableRemoveConnectionPacket;
import edn.lakeopossmc.drivebysable.util.BlockFace;
import edn.lakeopossmc.drivebysable.util.FaceOutlines;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

// --- CLIENT SIDE CABLE TOOL LOGIC --- //
// * Handles selection, connections, outlines, and hover tips
@EventBusSubscriber(modid = DriveBySableMod.MOD_ID, value = Dist.CLIENT)
public final class ClientCableNetworkHandler {
    private static final AABB UNIT_CUBE = AABB.unitCubeFromLowerCorner(Vec3.ZERO);
    private static final Map<Long, Map<String, Set<CableNetworkSink>>> EMPTY_NETWORK = Map.of();

    private static Map<Long, Map<String, Set<CableNetworkSink>>> currentNetwork = EMPTY_NETWORK;
    private static BlockPos selectedSource;
    private static String currentChannel = CableNetworkManager.WORLD_CHANNEL;
    private static int syncCooldown;
    private static String pendingSchematicSyncReason;
    private static final List<ScheduledFlash> scheduledFlashes = new ArrayList<>();

    private ClientCableNetworkHandler() {
    }

    @SubscribeEvent
    public static void onWorldUnload(final LevelEvent.Unload event) {
        clearSource();
    }

    //#region // --- MAIN CLICK HANDLING --- //
    // * Cable and non shift cutter fully take over the click
    // * Linked controller items are blocked from opening hub menus
    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        final Item eventItem = event.getItemStack().getItem();
        final BlockState hitBlock = event.getLevel().getBlockState(event.getHitVec().getBlockPos());
        final Player eventPlayer = event.getEntity();

        final boolean isCutter = eventItem instanceof CableCutterItem;
        final boolean cutterShiftDown = isCutter && eventPlayer != null && eventPlayer.isShiftKeyDown();

        if (eventItem instanceof CableItem || isCutter) {
            event.setUseBlock(TriState.FALSE);
        }
        if (isCutter && !cutterShiftDown) {
            event.setUseItem(TriState.FALSE);
        }
        if ((eventItem instanceof LinkedControllerItem && hitBlock.is(CableBlocks.CABLE_HUB) || (eventItem instanceof TweakedControllerDuck && hitBlock.is(CableBlocks.ADVANCED_CABLE_HUB)))) {
            event.setUseItem(TriState.FALSE);
        }
        if (event.getSide().isServer()) {
            return;
        }

        final Player player = eventPlayer;
        if (player == null || player.isSpectator()) {
            return;
        }

        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        final ItemStack heldItem = event.getItemStack();
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final Direction face = event.getFace() == null ? Direction.UP : event.getFace();

        if (heldItem.is(CableItems.CABLE.get())) {
            final boolean acted = handleCableUse(player, heldItem, level, pos, face);
            event.setCancellationResult(acted ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }

        if (heldItem.is(CableItems.CABLE_CUTTER.get()) && !cutterShiftDown) {
            final boolean acted = handleCutterUse(player, level, pos, face);
            event.setCancellationResult(acted ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }
    //#endregion

    // * Scroll changes channel while a source is selected
    @SubscribeEvent
    public static void onMouseScrolled(final InputEvent.MouseScrollingEvent event) {
        final Player player = Minecraft.getInstance().player;
        if (player == null || selectedSource == null) {
            return;
        }

        final ItemStack mainHandItem = player.getMainHandItem();
        if (!mainHandItem.is(CableItems.CABLE.get()) && !mainHandItem.is(CableItems.CABLE_CUTTER.get())) {
            return;
        }

        final double delta = event.getScrollDeltaY();
        if (delta == 0) {
            return;
        }

        changeChannel(player.level(), selectedSource, delta > 0);
        event.setCanceled(true);
    }

    //#region // --- MAIN CLIENT TICK --- //
    // * Keeps mirror synced, draws outlines, shows tip
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
    public static void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft.player;
        final Level level = minecraft.level;
        if (player == null || level == null) {
            return;
        }

        // * Runs regardless of held item so a flash finishes even after switching tools
        if (!scheduledFlashes.isEmpty()) {
            final Iterator<ScheduledFlash> flashIterator = scheduledFlashes.iterator();
            while (flashIterator.hasNext()) {
                final ScheduledFlash flash = flashIterator.next();
                if (--flash.ticksRemaining <= 0) {
                    flash.action.run();
                    flashIterator.remove();
                }
            }
        }

        final ItemStack mainHand = player.getMainHandItem();
        final boolean holdingCableTool = mainHand.is(CableItems.CABLE.get()) || mainHand.is(CableItems.CABLE_CUTTER.get());
        final boolean holdingClipboard = AllBlocks.CLIPBOARD.isIn(mainHand);

        // * Clipboard needed for the empty source copy check
        if (holdingCableTool || holdingClipboard || pendingSchematicSyncReason != null) {
            final Map<Long, Map<String, Set<CableNetworkSink>>> latestNetwork = CableNetworkManager.get(level).getNetwork();
            if (!latestNetwork.equals(currentNetwork)) {
                currentNetwork = latestNetwork;
                if (pendingSchematicSyncReason != null) {
                    DriveBySableMod.LOGGER.info(
                            "[schematic-debug] Client cable mirror refreshed after {}: {} sources / {} connections.",
                            pendingSchematicSyncReason,
                            currentNetwork.size(),
                            countConnections(currentNetwork)
                    );
                    pendingSchematicSyncReason = null;
                }
            }
        }

        if (!holdingCableTool) {
            clearSource();
        }

        // * Clipboard needs sync request
        if ((holdingCableTool || holdingClipboard) && --syncCooldown <= 0) {
            syncManager();
        }

        if (!holdingCableTool) {
            return;
        }

        if (mainHand.is(CableItems.CABLE.get())) {
            showCableHoverTip(minecraft, level);
        }

        if (selectedSource != null) {
            drawOutline(level, selectedSource, LineColor.SOURCE.SELECTED.getColor());
        }
        drawOutlines(level, selectedSource, currentNetwork, currentChannel);
    }
    //#endregion

    //#region // --- WHITE HITBOX ON VALID HOVER TARGET --- //
    // * Same event and utility create uses for the clipboard target highlight
    @SubscribeEvent
    public static void onRenderBlockHighlight(final RenderHighlightEvent.Block event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft.player;
        if (player == null || player.isSpectator()) {
            return;
        }

        if (selectedSource != null || !player.getMainHandItem().is(CableItems.CABLE.get())) {
            return;
        }

        final Level level = minecraft.level;
        final BlockPos pos = event.getTarget().getBlockPos();
        if (level == null || !level.getWorldBorder().isWithinBounds(pos)) {
            return;
        }

        final VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            return;
        }

        final VertexConsumer buffer = event.getMultiBufferSource().getBuffer(RenderType.lines());
        final Vec3 camera = event.getCamera().getPosition();
        final PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        TrackBlockOutline.renderShape(shape, poseStack, buffer, true);
        event.setCanceled(true);
        poseStack.popPose();
    }
    //#endregion

    public static void clearSource() {
        currentNetwork = EMPTY_NETWORK;
        selectedSource = null;
        currentChannel = CableNetworkManager.WORLD_CHANNEL;
        syncCooldown = 0;
    }

    // * Used by CableItem for the enchant glint while a source is selected
    public static boolean isInSetupMode() {
        return selectedSource != null;
    }

    public static String getCurrentChannel() {
        return currentChannel;
    }

    public static void requestSchematicSync(final String reason) {
        pendingSchematicSyncReason = reason;
        DriveBySableMod.LOGGER.info(
                "[schematic-debug] Requesting cable mirror sync for {}. Current client mirror: {} sources / {} connections.",
                reason,
                currentNetwork.size(),
                countConnections(currentNetwork)
        );
        syncManager();
    }

    // * First click selects, second click on same block deselects
    // * Otherwise toggles a connection to the clicked face
    private static boolean handleCableUse(final Player player, final ItemStack heldItem, final Level level, final BlockPos pos, final Direction face) {
        if (selectedSource == null) {
            selectedSource = pos.immutable();
            changeChannel(level, selectedSource, true);
            syncManager();
            return true;
        }

        if (selectedSource.equals(pos)) {
            clearSource();
            return true;
        }

        final Map<String, Set<CableNetworkSink>> currentSelection = currentNetwork.get(selectedSource.asLong());
        final CableNetworkSink sink = CableNetworkSink.of(pos, face);
        if (currentSelection != null && currentSelection.getOrDefault(currentChannel, Set.of()).contains(sink)) {
            PacketDistributor.sendToServer(new CableRemoveConnectionPacket(selectedSource, pos, face, currentChannel));
            return true;
        }

        PacketDistributor.sendToServer(new CableAddConnectionPacket(selectedSource, pos, face, currentChannel));
        if (CableConfig.CONFIG.shouldConsumeCables.get()) heldItem.consume(1, player);
        return true;
    }

    public static boolean hasConnections(final BlockPos pos) {
        final Map<String, Set<CableNetworkSink>> perChannel = currentNetwork.get(pos.asLong());
        return perChannel != null && perChannel.values().stream().anyMatch(sinks -> !sinks.isEmpty());
    }

    // * Same as cable but blocks selecting a source with no connections
    private static boolean handleCutterUse(final Player player, final Level level, final BlockPos pos, final Direction face) {
        if (selectedSource == null) {
            if (!hasConnections(pos)) {
                showInvalidOperationMessage(player, "drivebysable.invalid_op.no_connections");
                return false;
            }

            selectedSource = pos.immutable();
            changeChannel(level, selectedSource, true);
            syncManager();
            return true;
        }

        if (selectedSource.equals(pos)) {
            clearSource();
            return true;
        }

        final Map<String, Set<CableNetworkSink>> currentSelection = currentNetwork.get(selectedSource.asLong());
        final CableNetworkSink sink = CableNetworkSink.of(pos, face);
        if (currentSelection != null && currentSelection.getOrDefault(currentChannel, Set.of()).contains(sink)) {
            PacketDistributor.sendToServer(new CableRemoveConnectionPacket(selectedSource, pos, face, currentChannel));
            return true;
        }

        return false;
    }

    //#region // --- INVALID OP FLASH MESSAGE --- //
    // * Display message in red, then flash white
    public static void showInvalidOperationMessage(final Player player, final String langKey) {
        player.displayClientMessage(Component.translatable(langKey).withStyle(ChatFormatting.RED), true);
        scheduledFlashes.add(new ScheduledFlash(2, () -> {
            player.displayClientMessage(Component.translatable(langKey).withStyle(ChatFormatting.WHITE), true);
            final BlockPos pos = player.blockPosition();
            player.level().playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    AllSoundEvents.DENY.getMainEvent(), SoundSource.PLAYERS, 1.0F, 0.5F, false);
        }));
        scheduledFlashes.add(new ScheduledFlash(4, () ->
                player.displayClientMessage(Component.translatable(langKey).withStyle(ChatFormatting.RED), true)));
    }

    // * Tiny delayed task, ticked down in onClientTick
    private static final class ScheduledFlash {
        int ticksRemaining;
        final Runnable action;

        ScheduledFlash(final int ticksRemaining, final Runnable action) {
            this.ticksRemaining = ticksRemaining;
            this.action = action;
        }
    }
    //#endregion

    //#region // --- CABLE ACTIONS HOVER TIP --- //
    // * Text changes depending on setup mode and current target
    private static void showCableHoverTip(final Minecraft minecraft, final Level level) {
        final List<MutableComponent> tip = new ArrayList<>();
        tip.add(Component.translatable("drivebysable.cable_actions.header"));

        if (selectedSource != null) {
            final HitResult hitResult = minecraft.hitResult;
            final boolean targetingSource = hitResult instanceof final BlockHitResult blockHit
                    && blockHit.getType() == HitResult.Type.BLOCK
                    && selectedSource.equals(blockHit.getBlockPos());

            if (targetingSource) {
                tip.add(Component.translatable("drivebysable.cable_actions.exit_setup", Component.keybind("key.use")));
            } else {
                tip.add(Component.translatable("drivebysable.cable_actions.select_channel"));
                tip.add(Component.translatable("drivebysable.cable_actions.toggle_output", Component.keybind("key.use")));
            }

            CreateClient.VALUE_SETTINGS_HANDLER.showHoverTip(tip);
            return;
        }

        final HitResult hitResult = minecraft.hitResult;
        if (!(hitResult instanceof final BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        tip.add(Component.translatable("drivebysable.cable_actions.enter_setup", Component.keybind("key.use")));
        CreateClient.VALUE_SETTINGS_HANDLER.showHoverTip(tip);
    }
    //#endregion

    private static void syncManager() {
        PacketDistributor.sendToServer(CableNetworkRequestSyncPacket.INSTANCE);
        syncCooldown = 20;
    }

    // * Falls back to world channel for non multi-channel sources
    private static void changeChannel(final Level level, final BlockPos pos, final boolean forward) {
        final Block source = level.getBlockState(pos).getBlock();
        currentChannel = source instanceof MultiChannelCableSource channelSource
                ? channelSource.cable$nextChannel(level, pos, currentChannel, forward)
                : CableNetworkManager.WORLD_CHANNEL;

        if (currentChannel == null) {
            currentChannel = CableNetworkManager.WORLD_CHANNEL;
        }

        final Player player = Minecraft.getInstance().player;
        if (player != null) {
            // * Look up display name, fall back to raw channel id
            String langKey = TweakedControllerCableServerHandler.CHANNEL_TO_LANG_KEY
                    .getOrDefault(currentChannel,currentChannel);
            Component displayName = Component.translatable(langKey);
            player.displayClientMessage(
                    Component.translatable("drivebysable.cable.channel.selected", displayName),
                    true
            );
        }
    }

    //#region // --- DRAW ALL NETWORK OUTLINES --- //
    // * Selected source gets full connection detail
    // * Everything else just gets a plain box
    private static void drawOutlines(
            final Level level,
            final BlockPos selectedSource,
            final Map<Long, Map<String, Set<CableNetworkSink>>> network,
            final String activeChannel
    ) {
        for (final Map.Entry<Long, Map<String, Set<CableNetworkSink>>> entry : network.entrySet()) {
            final BlockPos source = BlockPos.of(entry.getKey());
            final Map<String, Set<CableNetworkSink>> perChannel = entry.getValue();

            if (selectedSource != null && source.equals(selectedSource)) {
                // * Track faces already used by active channel so other channels skip drawing over them
                final Set<BlockFace> activeFaces = new java.util.HashSet<>();
                final Set<CableNetworkSink> activeSinks = perChannel.get(activeChannel);
                if (activeSinks != null) {
                    for (final CableNetworkSink sink : activeSinks) {
                        activeFaces.add(BlockFace.of(sink.position(), sink.direction()));
                    }
                }

                for (final Map.Entry<String, Set<CableNetworkSink>> channelEntry : perChannel.entrySet()) {
                    final boolean active = channelEntry.getKey().equals(activeChannel);
                    for (final CableNetworkSink sink : channelEntry.getValue()) {
                        if (!active && activeFaces.contains(BlockFace.of(sink.position(), sink.direction()))) {
                            continue;
                        }
                        drawConnection(
                                level,
                                source,
                                BlockPos.of(sink.position()),
                                Direction.from3DDataValue(sink.direction()),
                                channelEntry.getKey(),
                                active ? LineColor.SINK.SELECTED.getColor() : LineColor.SINK.SAME_SOURCE_DIFFERENT_CHANNEL.getColor(),
                                active ? LineColor.CABLE.SELECTED.getColor() : LineColor.CABLE.SAME_SOURCE_DIFFERENT_CHANNEL.getColor()
                        );
                    }
                }
            } else {
                drawOutline(level, source, LineColor.SOURCE.SAME_NETWORK.getColor());
            }
        }
    }
    //#endregion

    private static void drawConnection(
            final Level level,
            final BlockPos start,
            final BlockPos end,
            final Direction direction,
            final String channel,
            final int faceColor,
            final int cableColor
    ) {
        drawOutlineFace(end, direction, channel, faceColor);
        Outliner.getInstance()
                .showLine(
                        net.createmod.catnip.data.Pair.of("cableConnection", net.createmod.catnip.data.Pair.of(net.createmod.catnip.data.Pair.of(start, end), net.createmod.catnip.data.Pair.of(direction, channel))),
                        Vec3.atCenterOf(start),
                        Vec3.atCenterOf(end).add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.5D))
                )
                .colored(cableColor);
    }

    private static void drawOutlineFace(final BlockPos pos, final Direction direction, final String channel, final int color) {
        Outliner.getInstance()
                .showAABB(net.createmod.catnip.data.Pair.of("cableFace", net.createmod.catnip.data.Pair.of(BlockFace.of(pos, direction), channel)), FaceOutlines.getOutline(direction).move(pos))
                .colored(color)
                .lineWidth(0.0625F);
    }

    // * Uses approximate bounding box
    private static void drawOutline(final Level level, final BlockPos pos, final int color) {
        final BlockState state = level.getBlockState(pos);
        final AABB box = state.getShape(level, pos).isEmpty() ? UNIT_CUBE : state.getShape(level, pos).bounds();
        Outliner.getInstance()
                .showAABB(net.createmod.catnip.data.Pair.of("cableBlock", pos), box.move(pos))
                .colored(color)
                .lineWidth(0.0625F);
    }

    private static void notifyPlayer(final Player player, final String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    private static int countConnections(final Map<Long, Map<String, Set<CableNetworkSink>>> network) {
        int count = 0;
        for (final Map<String, Set<CableNetworkSink>> perChannel : network.values()) {
            for (final Set<CableNetworkSink> sinks : perChannel.values()) {
                count += sinks.size();
            }
        }
        return count;
    }

    // --- COLORS FOR EACH OUTLINE STATE --- //
    private interface LineColor {
        int getColor();

        enum SINK implements LineColor {
            SELECTED(Mode.DEPOSIT.getColor()),
            SAME_SOURCE_DIFFERENT_CHANNEL(ChatFormatting.DARK_GRAY.getColor());

            private final int color;

            SINK(final int color) {
                this.color = color;
            }

            @Override
            public int getColor() {
                return color;
            }
        }

        enum SOURCE implements LineColor {
            SELECTED(Mode.TAKE.getColor()),
            SAME_NETWORK(0x5773d8);

            private final int color;

            SOURCE(final int color) {
                this.color = color;
            }

            @Override
            public int getColor() {
                return color;
            }
        }

        enum CABLE implements LineColor {
            SELECTED(Color.RED.getRGB()),
            SAME_SOURCE_DIFFERENT_CHANNEL(ChatFormatting.DARK_GRAY.getColor());

            private final int color;

            CABLE(final int color) {
                this.color = color;
            }

            @Override
            public int getColor() {
                return color;
            }
        }
    }
}