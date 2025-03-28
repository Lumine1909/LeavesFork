package org.leavesmc.leaves.protocol.servux.litematics.schematic.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.leavesmc.leaves.command.NoBlockUpdateCommand;
import org.leavesmc.leaves.protocol.servux.litematics.ServuxLitematicsProtocol;
import org.leavesmc.leaves.protocol.servux.litematics.malilib.IntBoundingBox;
import org.leavesmc.leaves.protocol.servux.litematics.schematic.LitematicaSchematic;
import org.leavesmc.leaves.protocol.servux.litematics.schematic.container.LitematicaBlockStateContainer;
import org.leavesmc.leaves.protocol.servux.litematics.schematic.placement.SchematicPlacement;
import org.leavesmc.leaves.protocol.servux.litematics.schematic.placement.SubRegionPlacement;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SchematicPlacingUtils {
    public static boolean placeToWorldWithinChunk(Level world,
                                                  ChunkPos chunkPos,
                                                  SchematicPlacement schematicPlacement,
                                                  ReplaceBehavior replace,
                                                  boolean notifyNeighbors) {
        LitematicaSchematic schematic = schematicPlacement.getSchematic();
        Set<String> regionsTouchingChunk = schematicPlacement.getRegionsTouchingChunk(chunkPos.x, chunkPos.z);
        BlockPos origin = schematicPlacement.getOrigin();
        boolean allSuccess = true;

        try {
            if (notifyNeighbors == false) {
                NoBlockUpdateCommand.setPreventBlockUpdate(true);
            }

            for (String regionName : regionsTouchingChunk) {
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);

                if (container == null) {
                    allSuccess = false;
                    continue;
                }

                SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(regionName);

                if (placement.isEnabled()) {
                    Map<BlockPos, CompoundTag> blockEntityMap = schematic.getBlockEntityMapForRegion(regionName);
                    Map<BlockPos, ScheduledTick<Block>> scheduledBlockTicks = schematic.getScheduledBlockTicksForRegion(regionName);
                    Map<BlockPos, ScheduledTick<Fluid>> scheduledFluidTicks = schematic.getScheduledFluidTicksForRegion(regionName);

                    if (placeBlocksWithinChunk(world, chunkPos, regionName, container, blockEntityMap,
                        origin, schematicPlacement, placement, scheduledBlockTicks,
                        scheduledFluidTicks, replace, notifyNeighbors) == false) {
                        allSuccess = false;
                        ServuxLitematicsProtocol.LOGGER.warn("Invalid/missing schematic data in schematic '{}' for sub-region '{}'", schematic.getMetadata().getName(), regionName);
                    }

                    List<LitematicaSchematic.EntityInfo> entityList = schematic.getEntityListForRegion(regionName);

                    if (schematicPlacement.ignoreEntities() == false &&
                        placement.ignoreEntities() == false && entityList != null) {
                        placeEntitiesToWorldWithinChunk(world, chunkPos, entityList, origin, schematicPlacement, placement);
                    }
                }
            }
        } finally {
            NoBlockUpdateCommand.setPreventBlockUpdate(false);
        }

        return allSuccess;
    }

    public static boolean placeBlocksWithinChunk(Level world, ChunkPos chunkPos, String regionName,
                                                 LitematicaBlockStateContainer container,
                                                 Map<BlockPos, CompoundTag> blockEntityMap,
                                                 BlockPos origin,
                                                 SchematicPlacement schematicPlacement,
                                                 SubRegionPlacement placement,
                                                 @Nullable Map<BlockPos, ScheduledTick<Block>> scheduledBlockTicks,
                                                 @Nullable Map<BlockPos, ScheduledTick<Fluid>> scheduledFluidTicks,
                                                 ReplaceBehavior replace, boolean notifyNeighbors) {
        IntBoundingBox bounds = schematicPlacement.getBoxWithinChunkForRegion(regionName, chunkPos.x, chunkPos.z);
        Vec3i regionSize = schematicPlacement.getSchematic().getAreaSize(regionName);

        if (bounds == null || container == null || blockEntityMap == null || regionSize == null) {
            return false;
        }

        BlockPos regionPos = placement.getPos();

        // These are the untransformed relative positions
        BlockPos posEndRel = (new BlockPos(fi.dy.masa.servux.util.position.PositionUtils.getRelativeEndPositionFromAreaSize(regionSize))).offset(regionPos);
        BlockPos posMinRel = fi.dy.masa.servux.util.position.PositionUtils.getMinCorner(regionPos, posEndRel);

        // The transformed sub-region origin position
        BlockPos regionPosTransformed = fi.dy.masa.servux.util.position.PositionUtils.getTransformedBlockPos(regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());

        // The relative offset of the affected region's corners, to the sub-region's origin corner
        BlockPos boxMinRel = new BlockPos(bounds.minX - origin.getX() - regionPosTransformed.getX(), 0, bounds.minZ - origin.getZ() - regionPosTransformed.getZ());
        BlockPos boxMaxRel = new BlockPos(bounds.maxX - origin.getX() - regionPosTransformed.getX(), 0, bounds.maxZ - origin.getZ() - regionPosTransformed.getZ());

        // Reverse transform that relative offset, to get the untransformed orientation's offsets
        boxMinRel = fi.dy.masa.servux.util.position.PositionUtils.getReverseTransformedBlockPos(boxMinRel, placement.getMirror(), placement.getRotation());
        boxMaxRel = fi.dy.masa.servux.util.position.PositionUtils.getReverseTransformedBlockPos(boxMaxRel, placement.getMirror(), placement.getRotation());

        boxMinRel = fi.dy.masa.servux.util.position.PositionUtils.getReverseTransformedBlockPos(boxMinRel, schematicPlacement.getMirror(), schematicPlacement.getRotation());
        boxMaxRel = fi.dy.masa.servux.util.position.PositionUtils.getReverseTransformedBlockPos(boxMaxRel, schematicPlacement.getMirror(), schematicPlacement.getRotation());

        // Get the offset relative to the sub-region's minimum corner, instead of the origin corner (which can be at any corner)
        boxMinRel = boxMinRel.subtract(posMinRel.subtract(regionPos));
        boxMaxRel = boxMaxRel.subtract(posMinRel.subtract(regionPos));

        BlockPos posMin = fi.dy.masa.servux.util.position.PositionUtils.getMinCorner(boxMinRel, boxMaxRel);
        BlockPos posMax = fi.dy.masa.servux.util.position.PositionUtils.getMaxCorner(boxMinRel, boxMaxRel);

        final int startX = posMin.getX();
        final int startZ = posMin.getZ();
        final int endX = posMax.getX();
        final int endZ = posMax.getZ();

        final int startY = 0;
        final int endY = Math.abs(regionSize.getY()) - 1;
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();

        //System.out.printf("sx: %d, sy: %d, sz: %d => ex: %d, ey: %d, ez: %d\n", startX, startY, startZ, endX, endY, endZ);

        if (startX < 0 || startZ < 0 || endX >= container.getSize().getX() || endZ >= container.getSize().getZ()) {
            System.out.printf("DEBUG ============= OUT OF BOUNDS - region: %s, sx: %d, sz: %d, ex: %d, ez: %d - size x: %d z: %d =============\n",
                regionName, startX, startZ, endX, endZ, container.getSize().getX(), container.getSize().getZ());
            return false;
        }

        final Rotation rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation());
        final Mirror mirrorMain = schematicPlacement.getMirror();
        final BlockState barrier = Blocks.BARRIER.defaultBlockState();
        Mirror mirrorSub = placement.getMirror();
        final boolean ignoreInventories = false;

        if (mirrorSub != Mirror.NONE &&
            (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90 ||
                schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        final int posMinRelMinusRegX = posMinRel.getX() - regionPos.getX();
        final int posMinRelMinusRegY = posMinRel.getY() - regionPos.getY();
        final int posMinRelMinusRegZ = posMinRel.getZ() - regionPos.getZ();

        for (int y = startY; y <= endY; ++y) {
            for (int z = startZ; z <= endZ; ++z) {
                for (int x = startX; x <= endX; ++x) {
                    BlockState state = container.get(x, y, z);

                    if (state.getBlock() == Blocks.STRUCTURE_VOID) {
                        continue;
                    }

                    posMutable.set(x, y, z);
                    CompoundTag teNBT = blockEntityMap.get(posMutable);

                    posMutable.set(posMinRelMinusRegX + x,
                        posMinRelMinusRegY + y,
                        posMinRelMinusRegZ + z);

                    BlockPos pos = fi.dy.masa.servux.util.position.PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                    pos = pos.add(regionPosTransformed).add(origin);

                    BlockState stateOld = world.getBlockState(pos);

                    if ((replace == ReplaceBehavior.NONE && stateOld.isAir() == false) ||
                        (replace == ReplaceBehavior.WITH_NON_AIR && state.isAir() == true)) {
                        continue;
                    }

                    if (mirrorMain != Mirror.NONE) {
                        state = state.mirror(mirrorMain);
                    }
                    if (mirrorSub != Mirror.NONE) {
                        state = state.mirror(mirrorSub);
                    }
                    if (rotationCombined != Rotation.NONE) {
                        state = state.rotate(rotationCombined);
                    }

                    BlockEntity te = world.getBlockEntity(pos);

                    if (te != null) {
                        if (te instanceof Container) {
                            ((Container) te).clearContent();
                        }

                        world.setBlock(pos, barrier, 0x14);
                    }

                    if (world.setBlock(pos, state, 0x12) && teNBT != null) {
                        te = world.getBlockEntity(pos);

                        if (te != null) {
                            teNBT = teNBT.copy();
                            teNBT.putInt("x", pos.getX());
                            teNBT.putInt("y", pos.getY());
                            teNBT.putInt("z", pos.getZ());

                            if (ignoreInventories) {
                                teNBT.remove("Items");
                            }

                            try {
                                te.loadWithComponents(teNBT, world.registryAccess().freeze());

                                if (ignoreInventories && te instanceof Container) {
                                    ((Container) te).clearContent();
                                }
                            } catch (Exception e) {
                                ServuxLitematicsProtocol.LOGGER.warn("Failed to load BlockEntity data for {} @ {}", state, pos);
                            }
                        }
                    }
                }
            }
        }

        if (world instanceof ServerLevel serverWorld) {
            IntBoundingBox box = new IntBoundingBox(startX, startY, startZ, endX, endY, endZ);

            if (scheduledBlockTicks != null && scheduledBlockTicks.isEmpty() == false) {
                LevelTicks<Block> scheduler = serverWorld.getBlockTicks();

                for (Map.Entry<BlockPos, ScheduledTick<Block>> entry : scheduledBlockTicks.entrySet()) {
                    BlockPos pos = entry.getKey();

                    if (box.containsPos(pos)) {
                        posMutable.set(posMinRelMinusRegX + pos.getX(),
                            posMinRelMinusRegY + pos.getY(),
                            posMinRelMinusRegZ + pos.getZ());

                        pos = fi.dy.masa.servux.util.position.PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                        pos = pos.offset(regionPosTransformed).offset(origin);
                        ScheduledTick<Block> tick = entry.getValue();

                        if (world.getBlockState(pos).getBlock() == tick.type()) {
                            scheduler.schedule(new ScheduledTick<>(tick.type(), pos, tick.triggerTick(), tick.priority(), tick.subTickOrder()));
                        }
                    }
                }
            }

            if (scheduledFluidTicks != null && scheduledFluidTicks.isEmpty() == false) {
                LevelTicks<Fluid> scheduler = serverWorld.getFluidTicks();

                for (Map.Entry<BlockPos, ScheduledTick<Fluid>> entry : scheduledFluidTicks.entrySet()) {
                    BlockPos pos = entry.getKey();

                    if (box.containsPos(pos)) {
                        posMutable.set(posMinRelMinusRegX + pos.getX(),
                            posMinRelMinusRegY + pos.getY(),
                            posMinRelMinusRegZ + pos.getZ());

                        pos = fi.dy.masa.servux.util.position.PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                        pos = pos.offset(regionPosTransformed).offset(origin);
                        ScheduledTick<Fluid> tick = entry.getValue();

                        if (world.getBlockState(pos).getFluidState().getType() == tick.type()) {
                            scheduler.schedule(new ScheduledTick<>(tick.type(), pos, tick.triggerTick(), tick.priority(), tick.subTickOrder()));
                        }
                    }
                }
            }
        }

        if (notifyNeighbors) {
            for (int y = startY; y <= endY; ++y) {
                for (int z = startZ; z <= endZ; ++z) {
                    for (int x = startX; x <= endX; ++x) {
                        posMutable.set(posMinRelMinusRegX + x,
                            posMinRelMinusRegY + y,
                            posMinRelMinusRegZ + z);
                        BlockPos pos = fi.dy.masa.servux.util.position.PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
                        pos = pos.offset(regionPosTransformed).offset(origin);
                        world.updateNeighborsAt(pos, world.getBlockState(pos).getBlock());
                    }
                }
            }
        }

        return true;
    }

    public static void placeEntitiesToWorldWithinChunk(Level world, ChunkPos chunkPos,
                                                       List<LitematicaSchematic.EntityInfo> entityList,
                                                       BlockPos origin,
                                                       SchematicPlacement schematicPlacement,
                                                       SubRegionPlacement placement) {
        BlockPos regionPos = placement.getPos();

        if (entityList == null) {
            return;
        }

        BlockPos regionPosRelTransformed = fi.dy.masa.servux.util.position.PositionUtils.getTransformedBlockPos(regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());
        final int offX = regionPosRelTransformed.getX() + origin.getX();
        final int offY = regionPosRelTransformed.getY() + origin.getY();
        final int offZ = regionPosRelTransformed.getZ() + origin.getZ();
        final double minX = (chunkPos.x << 4);
        final double minZ = (chunkPos.z << 4);
        final double maxX = (chunkPos.x << 4) + 16;
        final double maxZ = (chunkPos.z << 4) + 16;

        final Rotation rotationCombined = schematicPlacement.getRotation().rotate(placement.getRotation());
        final Mirror mirrorMain = schematicPlacement.getMirror();
        Mirror mirrorSub = placement.getMirror();

        if (mirrorSub != Mirror.NONE &&
            (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90 ||
                schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }

        for (LitematicaSchematic.EntityInfo info : entityList) {
            Vec3 pos = info.posVec;
            pos = fi.dy.masa.servux.util.position.PositionUtils.getTransformedPosition(pos, schematicPlacement.getMirror(), schematicPlacement.getRotation());
            pos = fi.dy.masa.servux.util.position.PositionUtils.getTransformedPosition(pos, placement.getMirror(), placement.getRotation());
            double x = pos.x + offX;
            double y = pos.y + offY;
            double z = pos.z + offZ;
            float[] origRot = new float[2];

            if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
                CompoundTag tag = info.nbt.copy();
                String id = tag.getString("id");

                // Avoid warning about invalid hanging position.
                // Note that this position isn't technically correct, but it only needs to be within 16 blocks
                // of the entity position to avoid the warning.
                if (id.equals("minecraft:glow_item_frame") ||
                    id.equals("minecraft:item_frame") ||
                    id.equals("minecraft:leash_knot") ||
                    id.equals("minecraft:painting")) {
                    Vec3 p = NbtUtils.readEntityPositionFromTag(tag);

                    if (p == null) {
                        p = new Vec3(x, y, z);
                        NbtUtils.writeEntityPositionToTag(p, tag);
                    }

                    tag.putInt("TileX", (int) p.x);
                    tag.putInt("TileY", (int) p.y);
                    tag.putInt("TileZ", (int) p.z);
                }

                ListTag rotation = tag.getList("Rotation", Tag.TAG_FLOAT);
                origRot[0] = rotation.getFloat(0);
                origRot[1] = rotation.getFloat(1);

                Entity entity = EntityUtils.createEntityAndPassengersFromNBT(tag, world);

                if (entity != null) {
                    rotateEntity(entity, x, y, z, rotationCombined, mirrorMain, mirrorSub);
                    //System.out.printf("post: %.1f - rot: %s, mm: %s, ms: %s\n", rotationYaw, rotationCombined, mirrorMain, mirrorSub);

                    // Update the sleeping position to the current position
                    if (entity instanceof LivingEntity living && living.isSleeping()) {
                        living.setSleepingPos(BlockPos.containing(x, y, z));
                    }

                    // Hack fix to fix the painting position offsets.
                    // The vanilla code will end up moving the position by one in two of the orientations,
                    // because it sets the hanging position to the given position (floored)
                    // and then it offsets the position from the hanging position
                    // by 0.5 or 1.0 blocks depending on the painting size.
                    if (entity instanceof Painting paintingEntity) {
                        Direction right = PositionUtils.rotateYCounterclockwise(paintingEntity.getDirection());

                        if ((paintingEntity.getVariant().value().width() % 2) == 0 &&
                            right.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
                            x -= 1.0 * right.getStepX();
                            z -= 1.0 * right.getStepZ();
                        }

                        if ((paintingEntity.getVariant().value().height() % 2) == 0) {
                            y -= 1.0;
                        }

                        entity.teleportTo(x, y, z);
                    }
                    if (entity instanceof ItemFrame frameEntity) {
                        if (frameEntity.getYRot() != origRot[0] && (frameEntity.getXRot() == 90.0F || frameEntity.getXRot() == -90.0F)) {
                            // Fix Yaw only if Pitch is +/- 90.0F (Floor, Ceiling mounted)
                            frameEntity.setYRot(origRot[0]);
                        }
                    }

                    EntityUtils.spawnEntityAndPassengersInWorld(entity, world);

                    if (entity instanceof Display) {
                        entity.tick(); // Required to set the full data for rendering
                    }
                }
            }
        }
    }

    public static void rotateEntity(Entity entity, double x, double y, double z,
                                    Rotation rotationCombined, Mirror mirrorMain, Mirror mirrorSub) {
        float rotationYaw = entity.getYaw();

        if (mirrorMain != Mirror.NONE) {
            rotationYaw = entity.applyMirror(mirrorMain);
        }
        if (mirrorSub != Mirror.NONE) {
            rotationYaw = entity.applyMirror(mirrorSub);
        }
        if (rotationCombined != Rotation.NONE) {
            rotationYaw += entity.getYaw() - entity.applyRotation(rotationCombined);
        }

        entity.refreshPositionAndAngles(x, y, z, rotationYaw, entity.getPitch());
        EntityUtils.setEntityRotations(entity, rotationYaw, entity.getPitch());
    }
}

